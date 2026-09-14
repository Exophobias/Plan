package com.djrapitops.plan.community;

import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.transactions.commands.RemoveEverythingTransaction;
import com.djrapitops.plan.storage.database.transactions.commands.RemovePlayerTransaction;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Runs through the existing SQLite and isolated native MySQL database aggregates. */
public interface CommunityDatabaseTest extends DatabaseTestPreparer {
    private long communityCount(String table) { return db().queryOptional("SELECT COUNT(*) FROM " + table, r -> r.getLong(1)).orElseThrow(); }
    private void communitySession(long start, long end) {
        CommunityTables.commit(db(), new CommunitySessionTransaction(serverUUID().asUUID(), playerUUID, start, end,
                new CommunityActivity.Snapshot(start, true, List.of(new CommunityActivity.Interval(start, end))))).join();
    }

    @Test default void communityHistoryPersistsBeyondThirtyFiveDaysAndUsesFirstObservedDate() {
        communitySession(1000, 2000);
        long later = 1000 + 90L * 86400000;
        communitySession(later, later + 1000);
        forcePersistenceCheck();
        assertEquals(2, communityCount(CommunityTables.ACTIVITY));
        assertEquals(1000L, db().queryOptional("SELECT first_seen FROM " + CommunityTables.MEMBERS, r -> r.getLong(1)).orElseThrow());
        assertEquals(0, communityCount(com.djrapitops.plan.referrals.ReferralTables.MEMBERS));
    }

    @Test default void communitySessionUpdatesAreIdempotentAndOlderSnapshotsCannotRegress() {
        communitySession(1000, 2000);
        communitySession(1000, 3000);
        communitySession(1000, 2000);
        assertEquals(1, communityCount(CommunityTables.ACTIVITY));
        assertEquals(3000L, db().queryOptional("SELECT session_end FROM " + CommunityTables.ACTIVITY, r -> r.getLong(1)).orElseThrow());
    }

    @Test default void communityLateLogoutCannotExtendObservedSessionsIntoPauseOrEnrollFixtureSessions() {
        CommunityCapture.pause(true, 500);
        CommunityCapture.pause(false, 1000);
        try {
            var normal = new com.djrapitops.plan.gathering.domain.ActiveSession(playerUUID, serverUUID(), 1100, "world", "SURVIVAL");
            normal.recordReferralActivity(2000, true);
            CommunityTables.commit(db(), new CommunitySessionTransaction(normal.toFinishedSession(2000))).join();
            CommunityCapture.pause(true, 2000);
            normal.recordReferralActivity(3000, true);
            CommunityTables.commit(db(), new CommunitySessionTransaction(normal.toFinishedSession(3000))).join();
            assertEquals(2000L, db().queryOptional("SELECT session_end FROM " + CommunityTables.ACTIVITY, r -> r.getLong(1)).orElseThrow());
            var fixture = new com.djrapitops.plan.gathering.domain.ActiveSession(player2UUID, serverUUID(), 2100, "world", "SURVIVAL");
            fixture.recordReferralActivity(3000, true);
            CommunityTables.commit(db(), new CommunitySessionTransaction(fixture.toFinishedSession(3000))).join();
            assertEquals(1, communityCount(CommunityTables.ACTIVITY));
            assertEquals(1, communityCount(CommunityTables.MEMBERS));
        } finally { CommunityCapture.pause(true, System.currentTimeMillis()); }
    }

    @Test default void communityRoutineCleanupPreservesHistoryButExplicitErasureSuppressesPendingWrites() {
        communitySession(1000, 2000);
        executeTransactions(new RemovePlayerTransaction(playerUUID, true));
        assertEquals(1, communityCount(CommunityTables.ACTIVITY));
        executeTransactions(new RemovePlayerTransaction(playerUUID));
        communitySession(1000, 3000);
        assertEquals(0, communityCount(CommunityTables.ACTIVITY));
        assertEquals(0, communityCount(CommunityTables.MEMBERS));
        assertEquals(1, communityCount(CommunityTables.DELETED));
    }

    @Test default void communityBackupPreservesHistoryCoverageAndErasureWithoutGuessingMergedHistory() {
        communitySession(1000, 2000);
        db().executeInTransaction("INSERT INTO " + CommunityTables.COVERAGE + " (run_id,server_uuid,start_ms,end_ms,flushed_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), serverUUID().toString(), 1000, 2000, 2200).join();
        executeTransactions(new CommunityEraseTransaction(player2UUID));
        db().executeInTransaction("INSERT INTO " + CommunityTables.STOPS + " (server_uuid,process_token,stopped_at) VALUES (?,?,?)",
                serverUUID().toString(), "old-process", 2000).join();
        CommunityTransfer.Snapshot snapshot = CommunityTransfer.capture(db());
        assertThrows(IllegalStateException.class, () -> CommunityTransfer.preflight(db(), snapshot, false, false));
        assertThrows(IllegalStateException.class, () -> CommunityTransfer.preflight(db(), snapshot, false, true));
        executeTransactions(new RemoveEverythingTransaction());
        assertEquals(0, communityCount(CommunityTables.DELETED));
        CommunityTransfer.preflight(db(), snapshot, false, false);
        CommunityTransfer.restore(db(), snapshot);
        assertEquals(snapshot, CommunityTransfer.capture(db()));
        assertEquals(1, communityCount(CommunityTables.COVERAGE));
        assertEquals(0, communityCount(CommunityTables.STOPS), "A restored backup cannot prove downtime after its snapshot");
    }

    @Test default void communityCleanStopPersistsProofButHotReloadNeverBecomesKnownDowntime() {
        var service = new CommunityAnalyticsSvc(dbSystem(), serverInfo(), config(), org.mockito.Mockito.mock(com.djrapitops.plan.delivery.web.ResolverSvc.class));
        service.enable();
        try {
            service.collect(System.currentTimeMillis());
            service.prepareServerShutdown().toCompletableFuture().join();
            assertEquals(1, communityCount(CommunityTables.STOPS));
            assertTrue(CommunityCapture.state().paused());
            long before = communityCount(CommunityTables.COVERAGE);
            service.setCollectionPaused(false);
            service.collect(System.currentTimeMillis());
            assertEquals(0, communityCount(CommunityTables.STOPS));
            assertEquals(before + 1, communityCount(CommunityTables.COVERAGE));
        } finally { service.disable(); }
    }

    @Test default void communityFixturePausePreventsBothCollectionAndCleanStopProof() {
        var service = new CommunityAnalyticsSvc(dbSystem(), serverInfo(), config(), org.mockito.Mockito.mock(com.djrapitops.plan.delivery.web.ResolverSvc.class));
        service.enable();
        try {
            service.setCollectionPaused(true);
            service.collect(System.currentTimeMillis());
            assertEquals(0, communityCount(CommunityTables.COVERAGE));
            assertThrows(java.util.concurrent.CompletionException.class, () -> service.prepareServerShutdown().toCompletableFuture().join());
            assertEquals(0, communityCount(CommunityTables.STOPS));
        } finally { service.disable(); }
    }

    @Test default void communityUncheckedFailureRollsBackBeforeTheSharedConnectionIsReused() {
        utilities.TestErrorLogger.throwErrors(false);
        try {
            assertThrows(RuntimeException.class, () -> CommunityTables.commit(db(), new CommunityTables.Tx() {
                @Override protected void performCommunityOperations() {
                    sql("INSERT INTO " + CommunityTables.MEMBERS + " (server_uuid,uuid,first_seen) VALUES (?,?,?)", serverUUID().toString(), playerUUID.toString(), 1000);
                    throw new IllegalStateException("synthetic failure");
                }
            }).join());
        } finally { utilities.TestErrorLogger.throwErrors(true); }
        communitySession(3000, 4000);
        assertEquals(3000L, db().queryOptional("SELECT first_seen FROM " + CommunityTables.MEMBERS, r -> r.getLong(1)).orElseThrow());
    }
    @Test default void communityReportReadsPublicAggregatesFromOneStoredSnapshot() {
        var date = java.time.LocalDate.of(2026, 10, 1);
        long from = CommunityReport.epoch(date), until = from + CommunityReport.DAY;
        for (int i = 0; i < 10; i++)
            CommunityTables.commit(db(), new CommunitySessionTransaction(serverUUID().asUUID(), UUID.randomUUID(), from, from + 600000,
                    new CommunityActivity.Snapshot(from, true, List.of(new CommunityActivity.Interval(from, from + 600000))))).join();
        db().executeInTransaction("INSERT INTO " + CommunityTables.COVERAGE + " (run_id,server_uuid,start_ms,end_ms,flushed_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), serverUUID().toString(), from, until, until).join();
        var report = CommunityReport.load(db(), serverUUID().asUUID(), date, date.plusDays(1), until);
        assertEquals("ready", report.get("status"));
        assertEquals(10, ((java.util.Map<?, ?>) report.get("summary")).get("participants"));
        assertFalse(new com.google.gson.Gson().toJson(report).contains("uuid"));
    }
}
