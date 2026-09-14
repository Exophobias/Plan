package com.djrapitops.plan.community;

import com.djrapitops.plan.SubSystem;
import com.djrapitops.plan.delivery.web.ResolverSvc;
import com.djrapitops.plan.gathering.cache.SessionCache;
import com.djrapitops.plan.gathering.domain.FinishedSession;
import com.djrapitops.plan.identification.ServerInfo;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.settings.config.paths.TimeSettings;
import com.djrapitops.plan.storage.database.DBSystem;
import com.djrapitops.plan.storage.database.Database;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Independent all-player activity recording. A reload or crash never certifies an offline gap. */
@Singleton
public final class CommunityAnalyticsSvc implements CommunityAnalyticsService, SubSystem {
    private final DBSystem databases;
    private final ServerInfo serverInfo;
    private final PlanConfig config;
    private final ResolverSvc resolver;
    private volatile boolean enabled;
    private ScheduledExecutorService executor;
    private ExecutorService reportExecutor;
    private volatile long runGeneration = -1;
    private long runStart, previousFlush;
    private String runId;
    private volatile CommittedRun committedRun;
    private volatile boolean failed;
    private static final long PROCESS_START = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    private static final String PROCESS_TOKEN = ProcessHandle.current().pid() + ":" + PROCESS_START;

    @Inject public CommunityAnalyticsSvc(DBSystem databases, ServerInfo serverInfo, PlanConfig config, ResolverSvc resolver) {
        this.databases = databases; this.serverInfo = serverInfo; this.config = config; this.resolver = resolver;
    }

    @Override public void enable() {
        enabled = true;
        runGeneration = -1;
        CommunityCapture.pause(false, System.currentTimeMillis());
        Holder.set(this);
        resolver.registerPermission("page.server.reports", "manage.groups");
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Plan-community-analytics"); thread.setDaemon(true); return thread;
        });
        executor.scheduleWithFixedDelay(this::collectSafely, 1, 30, TimeUnit.SECONDS);
        // No request queue: a timed-out JDBC operation keeps this worker occupied until it actually exits.
        reportExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
            Thread thread = new Thread(r, "Plan-community-report"); thread.setDaemon(true); return thread;
        });
    }

    @Override public void disable() {
        enabled = false;
        CommunityCapture.pause(true, System.currentTimeMillis());
        Holder.set(null);
        if (executor != null) executor.shutdownNow();
        if (reportExecutor != null) reportExecutor.shutdownNow();
    }

    @Override public void setCollectionPaused(boolean paused) {
        CommunityCapture.pause(!enabled || paused, System.currentTimeMillis());
    }

    @Override public CompletionStage<Void> prepareServerShutdown() {
        long requestedAt = System.currentTimeMillis();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { prepareServerShutdown(requestedAt, completion); }
                catch (RuntimeException failure) { completion.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException stopped) {
            completion.completeExceptionally(new IllegalStateException("Community collector is stopped", stopped));
        }
        return completion;
    }

    /** The caller never waits for the collection monitor; its deadline includes worker and lock queues. */
    private synchronized void prepareServerShutdown(long requestedAt, CompletableFuture<Void> completion) {
        long now = System.currentTimeMillis();
        if (completion.isCancelled() || now < requestedAt || now - requestedAt >= 14000)
            throw new IllegalStateException("Community clean stop request expired before observation");
        CommunityCapture.State gate = CommunityCapture.state();
        CommittedRun durable = committedRun;
        if (!enabled || gate.paused() || failed || durable == null || durable.generation() != gate.generation()
                || now < durable.flushed() || now - durable.flushed() > 90000 || PROCESS_START == 0)
            throw new IllegalStateException("Clean community stop could not be proven");
        List<CommunitySessionTransaction> writes = new ArrayList<>();
        for (FinishedSession session : SessionCache.referralShutdownSessions(now, config.get(TimeSettings.AFK_THRESHOLD)))
            if (session.getServerUUID().equals(serverInfo.getServerUUID())) writes.add(new CommunitySessionTransaction(session));
        long afterSnapshot = System.currentTimeMillis();
        if (completion.isCancelled() || afterSnapshot < requestedAt || afterSnapshot - requestedAt >= 14000
                || CommunityCapture.state().generation() != gate.generation())
            throw new IllegalStateException("Community collection changed or expired during stop");
        // Use the exact final observation boundary, not a later wall-clock read after taking snapshots.
        CommunityCapture.pause(true, now);
        long stopGeneration = CommunityCapture.state().generation();
        String id = durable.id(), server = serverInfo.getServerUUID().toString();
        CommunityTables.commit(database(), new CommunityTables.Tx() {
            private void check() {
                long current = System.currentTimeMillis();
                if (!enabled || completion.isCancelled() || current < requestedAt || current - requestedAt >= 14000
                        || CommunityCapture.state().generation() != stopGeneration || !CommunityCapture.state().paused())
                    throw new IllegalStateException("Community clean stop proof expired or collection changed");
            }
            @Override protected void performCommunityOperations() {
                check();
                if (!one("SELECT 1 FROM " + CommunityTables.COVERAGE + " WHERE run_id=?", r -> true, false, id))
                    throw new IllegalStateException("Community coverage was not persisted");
                for (CommunitySessionTransaction write : writes) { check(); executeOther(write); }
                sql("UPDATE " + CommunityTables.COVERAGE + " SET end_ms=?,flushed_at=? WHERE run_id=?", now, now, id);
                check();
                sql("DELETE FROM " + CommunityTables.STOPS + " WHERE server_uuid=?", server);
                sql("INSERT INTO " + CommunityTables.STOPS + " (server_uuid,process_token,stopped_at) VALUES (?,?,?)", server, PROCESS_TOKEN, now);
                check();
            }
        }).whenComplete((unused, error) -> { if (error == null) completion.complete(null); else completion.completeExceptionally(error); });
    }

    private Database database() {
        Database db = databases.getDatabase();
        if (!enabled || db == null || db.getState() != Database.State.OPEN)
            throw new IllegalStateException("Community activity storage is unavailable");
        return db;
    }

    private void collectSafely() {
        try { collect(System.currentTimeMillis()); failed = false; }
        catch (RuntimeException failure) { failed = true; runGeneration = -1; }
    }

    synchronized void collect(long now) {
        CommunityCapture.State gate = CommunityCapture.state();
        if (!enabled || gate.paused()) { runGeneration = -1; return; }
        long threshold = config.get(TimeSettings.AFK_THRESHOLD);
        if (threshold < 0 || threshold > TimeUnit.DAYS.toMillis(1))
            throw new IllegalStateException("Unsupported AFK threshold");
        if (runGeneration != gate.generation() || now < previousFlush || now - previousFlush > 90000) {
            runId = UUID.randomUUID().toString();
            runGeneration = gate.generation();
            runStart = !failed && now >= gate.changedAt() && now - gate.changedAt() <= 90000 ? gate.changedAt() : now;
        }
        long through = Math.max(runStart, now - threshold);
        List<CommunitySessionTransaction> writes = new ArrayList<>();
        for (FinishedSession session : SessionCache.referralActivitySessions(now))
            if (session.getServerUUID().equals(serverInfo.getServerUUID())) writes.add(new CommunitySessionTransaction(session));
        String id = runId, server = serverInfo.getServerUUID().toString();
        long start = runStart;
        CommunityTables.commit(database(), new CommunityTables.Tx() {
            private void check() {
                if (!enabled || CommunityCapture.state().generation() != gate.generation())
                    throw new IllegalStateException("Community collection changed before commit");
            }
            @Override protected void performCommunityOperations() {
                check();
                Stop stop = one("SELECT process_token,stopped_at FROM " + CommunityTables.STOPS + " WHERE server_uuid=?",
                        r -> new Stop(r.getString(1), r.getLong(2)), null, server);
                if (stop != null) {
                    if (validDowntime(stop.process(), stop.at(), PROCESS_TOKEN, PROCESS_START, start))
                        sql("INSERT INTO " + CommunityTables.COVERAGE + " (run_id,server_uuid,start_ms,end_ms,flushed_at) VALUES (?,?,?,?,?)",
                                UUID.randomUUID().toString(), server, stop.at(), start, now);
                    sql("DELETE FROM " + CommunityTables.STOPS + " WHERE server_uuid=?", server);
                }
                writes.forEach(this::executeOther);
                if (one("SELECT 1 FROM " + CommunityTables.COVERAGE + " WHERE run_id=?", r -> true, false, id))
                    sql("UPDATE " + CommunityTables.COVERAGE + " SET end_ms=?,flushed_at=? WHERE run_id=?", through, now, id);
                else {
                    if (one("SELECT COUNT(*) FROM " + CommunityTables.COVERAGE + " WHERE server_uuid=?", r -> r.getLong(1), 0L, server) >= 100000)
                        throw new IllegalStateException("Community coverage capacity requires archival");
                    sql("INSERT INTO " + CommunityTables.COVERAGE + " (run_id,server_uuid,start_ms,end_ms,flushed_at) VALUES (?,?,?,?,?)", id, server, start, through, now);
                }
                check();
            }
        }).join();
        previousFlush = now;
        committedRun = new CommittedRun(id, gate.generation(), now);
    }

    /** Heavy aggregation runs off the server thread with one bounded worker request at a time. */
    public Map<String, Object> report(UUID server, LocalDate start, LocalDate endExclusive) {
        if (!serverInfo.getServerUUID().asUUID().equals(server)) throw new IllegalArgumentException("Unknown community server");
        Database db = database();
        final Future<Map<String, Object>> work;
        try {
            work = reportExecutor.submit(() -> CommunityReport.load(db, server, start, endExclusive, System.currentTimeMillis()));
        } catch (RejectedExecutionException busy) {
            throw new IllegalStateException("A community report is already being generated or reporting is stopped", busy);
        }
        try { return work.get(20, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            work.cancel(true); Thread.currentThread().interrupt();
            throw new IllegalStateException("Community report was interrupted", interrupted);
        } catch (TimeoutException timeout) {
            work.cancel(true);
            throw new IllegalStateException("Community report exceeded its processing budget", timeout);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Community report is unavailable", failure.getCause());
        }
    }

    private record CommittedRun(String id, long generation, long flushed) { }
    private record Stop(String process, long at) { }
    static boolean validDowntime(String priorProcess, long stoppedAt, String currentProcess, long processStart, long resumedAt) {
        return !priorProcess.equals(currentProcess) && stoppedAt > 0 && processStart > stoppedAt && resumedAt >= processStart;
    }
}
