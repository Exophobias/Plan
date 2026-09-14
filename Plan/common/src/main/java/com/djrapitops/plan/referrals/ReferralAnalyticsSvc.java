package com.djrapitops.plan.referrals;

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
import java.util.*;
import java.util.concurrent.*;

/** Plan owns activity, persistence and aggregates; a trusted adapter supplies only referral events. */
@Singleton
public class ReferralAnalyticsSvc implements ReferralAnalyticsService, SubSystem {
    private final DBSystem databases;
    private final ServerInfo serverInfo;
    private final PlanConfig config;
    private final ResolverSvc resolver;
    private ScheduledExecutorService executor;
    private volatile boolean enabled;
    private volatile String runId;
    private volatile long runGeneration = -1, runStart, previousFlush;
    private volatile CommittedRun committedRun;
    private volatile boolean failed;
    private static final long PROCESS_START = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    private static final String PROCESS_TOKEN = ProcessHandle.current().pid() + ":" + PROCESS_START;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    @Inject public ReferralAnalyticsSvc(DBSystem databases, ServerInfo serverInfo, PlanConfig config, ResolverSvc resolver) {
        this.databases = databases; this.serverInfo = serverInfo; this.config = config; this.resolver = resolver;
    }
    @Override public void enable() {
        setCollectionPaused(true);
        enabled = true;
        Holder.set(this);
        resolver.registerPermission("page.server.referrals", "manage.groups");
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Plan-referral-analytics"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this::collectSafely, 1, 30, TimeUnit.SECONDS);
    }
    @Override public void disable() {
        setCollectionPaused(true);
        enabled = false;
        Holder.set(null);
        if (executor != null) executor.shutdownNow();
        cache.clear();
    }
    @Override public void setCollectionPaused(boolean paused) {
        ReferralCapture.pause(paused, System.currentTimeMillis());
    }
    @Override public CompletionStage<Void> prepareServerShutdown() {
        long now = System.currentTimeMillis(); ReferralCapture.State gate = ReferralCapture.state();
        CommittedRun durable = committedRun;
        if (gate.paused() || failed || durable == null || durable.generation != gate.generation() || durable.flushed <= 0
                || now < durable.flushed || now - durable.flushed > 90000 || PROCESS_START == 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("Clean activity stop could not be proven"));
        }
        long threshold = config.get(TimeSettings.AFK_THRESHOLD);
        List<ReferralSessionTransaction> writes = new ArrayList<>();
        for (FinishedSession session : SessionCache.referralShutdownSessions(now,threshold))
            if (session.getServerUUID().equals(serverInfo.getServerUUID())) writes.add(new ReferralSessionTransaction(session));
        if (ReferralCapture.state().generation() != gate.generation())
            return CompletableFuture.failedFuture(new IllegalStateException("Activity collection changed during stop"));
        setCollectionPaused(true);
        String id = durable.id, server = serverInfo.getServerUUID().toString();
        long stopGeneration = ReferralCapture.state().generation();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ReferralTables.commit(database(), new ReferralTables.Tx() {
            private void check() {
                long current = System.currentTimeMillis();
                if (!enabled || completion.isCancelled() || current < now || current - now >= 14000
                        || ReferralCapture.state().generation() != stopGeneration || !ReferralCapture.state().paused())
                    throw new IllegalStateException("Clean stop proof expired or collection changed");
            }
            @Override protected void performReferralOperations() {
                check();
                if (!one("SELECT run_id FROM " + ReferralTables.COVERAGE + " WHERE run_id=?",r -> true,false,id))
                    throw new IllegalStateException("Clean stop coverage was not persisted");
                for (ReferralSessionTransaction write : writes) { check(); executeOther(write); }
                sql("UPDATE " + ReferralTables.COVERAGE + " SET end_ms=?,flushed_at=? WHERE run_id=?",now,now,id);
                check();
                sql("DELETE FROM " + ReferralTables.STOPS + " WHERE server_uuid=?",server);
                sql("INSERT INTO " + ReferralTables.STOPS + " (server_uuid,process_token,stopped_at) VALUES (?,?,?)",server,PROCESS_TOKEN,now);
                try { check(); } catch (IllegalStateException expired) {
                    sql("DELETE FROM " + ReferralTables.STOPS + " WHERE server_uuid=?",server);
                    throw expired;
                }
            }
        }).whenComplete((unused,error) -> { if (error == null) completion.complete(null); else completion.completeExceptionally(error); });
        return completion;
    }
    private Database database() {
        Database database = databases.getDatabase();
        if (!enabled || database == null || database.getState() != Database.State.OPEN) throw new IllegalStateException("Referral analytics unavailable");
        return database;
    }
    private void local(UUID server) {
        if (server == null || !serverInfo.getServerUUID().asUUID().equals(server)) throw new IllegalArgumentException("Referral source must be this server");
    }
    @Override public Checkpoint getCheckpoint(UUID server) {
        local(server);
        return database().queryOptional("SELECT stream_id,cursor_value FROM " + ReferralTables.FEED + " WHERE server_uuid=?",
                r -> new Checkpoint(r.getString(1), r.getLong(2)), server.toString()).orElse(new Checkpoint(null, 0));
    }
    @Override public CompletionStage<Void> applyBatch(UUID server, Batch batch) {
        local(server);
        return ReferralTables.commit(database(), new ReferralJournal.Apply(server, batch, System.currentTimeMillis()))
                .thenRun(() -> { /* The periodic background refresh publishes one complete aggregate generation. */ });
    }
    private void collectSafely() {
        try { collect(System.currentTimeMillis()); failed = false; }
        catch (RuntimeException failure) { failed = true; runGeneration = -1; }
        UUID server = serverInfo.getServerUUID().asUUID(); long now = System.currentTimeMillis();
        try { cache.put(server, new Cached(now, ReferralReport.load(database(), server, now, failed))); }
        catch (RuntimeException failure) { cache.put(server, new Cached(now, empty(server, now))); }
    }
    synchronized void collect(long now) {
        ReferralCapture.State gate = ReferralCapture.state();
        if (gate.paused()) { runGeneration = -1; return; }
        long afkThreshold = config.get(TimeSettings.AFK_THRESHOLD);
        if (afkThreshold < 0 || afkThreshold > TimeUnit.DAYS.toMillis(1)) throw new IllegalStateException("Unsupported AFK threshold");
        if (runGeneration != gate.generation() || previousFlush > now || now - previousFlush > 90000) {
            runId = UUID.randomUUID().toString();
            runGeneration = gate.generation();
            runStart = now - gate.changedAt() <= 90000 ? gate.changedAt() : now;
        }
        // On first collection, no actions before runStart can acquire a covered reporting window.
        long through = Math.max(runStart, now - afkThreshold);
        List<FinishedSession> sessions = SessionCache.referralActivitySessions(now);
        List<ReferralSessionTransaction> writes = new ArrayList<>();
        for (FinishedSession session : sessions) {
            if (session.getServerUUID().equals(serverInfo.getServerUUID())) writes.add(new ReferralSessionTransaction(session));
        }
        if (ReferralCapture.state().generation() != gate.generation()) { runGeneration = -1; return; }
        String id = runId, server = serverInfo.getServerUUID().toString(); long start = runStart;
        ReferralTables.commit(database(), new ReferralTables.Tx() {
            @Override protected void performReferralOperations() {
                if (ReferralCapture.state().generation() != gate.generation()) return;
                Stop stop = one("SELECT process_token,stopped_at FROM " + ReferralTables.STOPS + " WHERE server_uuid=?",
                        r -> new Stop(r.getString(1),r.getLong(2)),null,server);
                if (stop != null) {
                    if (validDowntime(stop.process,stop.at,PROCESS_TOKEN,PROCESS_START,start))
                        sql("INSERT INTO " + ReferralTables.COVERAGE + " (run_id,server_uuid,start_ms,end_ms,flushed_at) VALUES (?,?,?,?,?)",
                                UUID.randomUUID().toString(),server,stop.at,start,now);
                    sql("DELETE FROM " + ReferralTables.STOPS + " WHERE server_uuid=?",server);
                }
                writes.forEach(this::executeOther);
                boolean exists = one("SELECT run_id FROM " + ReferralTables.COVERAGE + " WHERE run_id=?", r -> true, false, id);
                if (exists) sql("UPDATE " + ReferralTables.COVERAGE + " SET end_ms=?,flushed_at=? WHERE run_id=?", through, now, id);
                else sql("INSERT INTO " + ReferralTables.COVERAGE + " (run_id,server_uuid,start_ms,end_ms,flushed_at) VALUES (?,?,?,?,?)", id, server, start, through, now);
            }
        }).join();
        if (ReferralCapture.state().generation() == gate.generation()) {
            previousFlush = now;
            committedRun = new CommittedRun(id, gate.generation(), now);
        }
    }
    public Map<String, Object> report(UUID server) {
        if (database().query(com.djrapitops.plan.storage.database.queries.objects.ServerQueries.fetchServerId(
                com.djrapitops.plan.identification.ServerUUID.from(server))).isEmpty()) throw new IllegalArgumentException("Unknown server");
        Cached found = cache.get(server); long now = System.currentTimeMillis();
        if (found != null && now - found.at <= 300000) return found.report;
        return empty(server, now);
    }
    private static Map<String,Object> empty(UUID server, long now) {
        return ReferralReport.calculate(server, now, List.of(), List.of(), List.of(), List.of(), new ReferralReport.Feed(0,0,0),0,true);
    }
    private record Cached(long at, Map<String, Object> report) { }
    private record CommittedRun(String id, long generation, long flushed) { }
    private record Stop(String process, long at) { }
    static boolean validDowntime(String priorProcess,long stoppedAt,String currentProcess,long processStart,long resumedAt) {
        return !priorProcess.equals(currentProcess) && stoppedAt > 0 && processStart > stoppedAt && resumedAt >= processStart;
    }
}
