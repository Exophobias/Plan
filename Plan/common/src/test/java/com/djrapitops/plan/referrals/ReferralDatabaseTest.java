package com.djrapitops.plan.referrals;

import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.transactions.commands.RemovePlayerTransaction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.djrapitops.plan.referrals.ReferralAnalyticsService.*;
import static org.junit.jupiter.api.Assertions.*;

public interface ReferralDatabaseTest extends DatabaseTestPreparer {
    String STREAM = "b".repeat(32), CLAIM = "a".repeat(32);
    private Claim referralClaim() { return new Claim(CLAIM,playerUUID,"pending",1000,1100,0,9000,true,false,false); }
    private Batch page(long from,long through,long high,List<Event> events) { return new Batch(STREAM,from,through,high,5000,100,through<high,events); }
    private void apply(Batch b) { ReferralTables.commit(db(),new ReferralJournal.Apply(serverUUID().asUUID(),b,6000)).join(); }
    private long count(String table) { return db().queryOptional("SELECT COUNT(*) FROM " + table,r -> r.getLong(1)).orElseThrow(); }
    @Test default void referralJournalPersistsCheckpointAndProjectionAcrossRestart() {
        apply(page(0,1,1,List.of(new Event(1,2000,referralClaim(),null))));
        forcePersistenceCheck();
        assertEquals(1L,db().queryOptional("SELECT cursor_value FROM " + ReferralTables.FEED,r -> r.getLong(1)).orElseThrow());
        assertEquals(1,count(ReferralTables.LATEST)); assertEquals(1,count(ReferralTables.EVENTS));
    }
    @Test default void referralPartialPageDoesNotAdvanceNoClaimCoverage() {
        apply(page(0,1,2,List.of(new Event(1,2000,referralClaim(),null))));
        assertEquals(0L,db().queryOptional("SELECT source_as_of FROM " + ReferralTables.FEED,r -> r.getLong(1)).orElseThrow());
        Award a = new Award("credit",CLAIM,"credit","CAD",500,3000,0,0,"pending");
        apply(page(1,2,2,List.of(new Event(2,3000,null,a))));
        assertEquals(5000L,db().queryOptional("SELECT source_as_of FROM " + ReferralTables.FEED,r -> r.getLong(1)).orElseThrow());
    }
    @Test default void referralEmptyHeartbeatIsIdempotentAndDoesNotDuplicateRecords() {
        apply(page(0,1,1,List.of(new Event(1,2000,referralClaim(),null))));
        apply(page(1,1,1,List.of())); apply(page(1,1,1,List.of()));
        assertEquals(1,count(ReferralTables.EVENTS)); assertEquals(1,count(ReferralTables.LATEST));
    }
    @Test default void referralRoutineInactiveCleanupKeepsCohortDenominatorButExplicitRemovalErasesIt() {
        db().executeInTransaction("INSERT INTO " + ReferralTables.MEMBERS + " (server_uuid,uuid,first_join) VALUES (?,?,?)",serverUUID().toString(),playerUUID.toString(),1000).join();
        executeTransactions(new RemovePlayerTransaction(playerUUID,true));
        assertEquals(1,count(ReferralTables.MEMBERS));
        executeTransactions(new RemovePlayerTransaction(playerUUID));
        assertEquals(0,count(ReferralTables.MEMBERS)); assertEquals(1,count(ReferralTables.DELETED));
    }
    @Test default void referralDeletedNewcomerClaimAndSamePageAwardAdvanceWithoutResurrection() {
        executeTransactions(new RemovePlayerTransaction(playerUUID));
        Award a = new Award("credit",CLAIM,"credit","CAD",500,3000,0,0,"pending");
        apply(page(0,2,2,List.of(new Event(1,2000,referralClaim(),null),new Event(2,3000,null,a))));
        assertEquals(0,count(ReferralTables.EVENTS)); assertEquals(0,count(ReferralTables.LATEST));
        assertEquals(2L,db().queryOptional("SELECT cursor_value FROM " + ReferralTables.FEED,r -> r.getLong(1)).orElseThrow());
    }
    @Test default void referralExplicitRemovalErasesClaimAwardHistoryAndProjection() {
        Award a = new Award("credit",CLAIM,"credit","CAD",500,3000,0,0,"pending");
        apply(page(0,2,2,List.of(new Event(1,2000,referralClaim(),null),new Event(2,3000,null,a))));
        executeTransactions(new RemovePlayerTransaction(playerUUID));
        assertEquals(0,count(ReferralTables.EVENTS)); assertEquals(0,count(ReferralTables.LATEST));
        assertEquals(2,count(ReferralTables.DELETED));
    }
    @Test default void referralSessionReplayUpdatesOneDurableExactSnapshot() {
        db().executeInTransaction("INSERT INTO " + ReferralTables.MEMBERS + " (server_uuid,uuid,first_join) VALUES (?,?,?)",serverUUID().toString(),playerUUID.toString(),1000).join();
        var snapshot = new ReferralActivity.Snapshot(true,List.of(new ReferralActivity.Interval(1000,3000)));
        executeTransactions(new ReferralSessionTransaction(serverUUID().asUUID(),playerUUID,1000,3000,snapshot),
                new ReferralSessionTransaction(serverUUID().asUUID(),playerUUID,1000,3000,snapshot));
        assertEquals(1,count(ReferralTables.ACTIVITY));
        assertTrue(db().queryOptional("SELECT intervals FROM " + ReferralTables.ACTIVITY,r -> r.getString(1)).orElseThrow().contains("3000"));
    }
    @Test default void referralCollectionPauseBlocksRosterAndResumeDoesNotBackfillFixtureJoin() {
        ReferralCapture.pause(true,1000);
        executeTransactions(new ReferralMemberTransaction(serverUUID().asUUID(),playerUUID,1100));
        ReferralCapture.pause(false,2000);
        try {
            executeTransactions(new ReferralMemberTransaction(serverUUID().asUUID(),playerUUID,1100));
            assertEquals(0,count(ReferralTables.MEMBERS));
            executeTransactions(new ReferralMemberTransaction(serverUUID().asUUID(),player2UUID,2100));
            assertEquals(1,count(ReferralTables.MEMBERS));
        } finally { ReferralCapture.pause(true,System.currentTimeMillis()); }
    }
    @Test default void referralFinishedHistoryStopsAtThirtyFiveDays() {
        db().executeInTransaction("INSERT INTO " + ReferralTables.MEMBERS + " (server_uuid,uuid,first_join) VALUES (?,?,?)",serverUUID().toString(),playerUUID.toString(),1000).join();
        long outside = 1000 + 36*ReferralReport.DAY;
        executeTransactions(new ReferralSessionTransaction(serverUUID().asUUID(),playerUUID,outside,outside+1000,new ReferralActivity.Snapshot(true,List.of(new ReferralActivity.Interval(outside,outside+1000)))));
        assertEquals(0,count(ReferralTables.ACTIVITY));
    }
    private void rejected(Batch batch) {
        utilities.TestErrorLogger.throwErrors(false);
        try { assertThrows(RuntimeException.class,() -> apply(batch)); }
        finally { utilities.TestErrorLogger.throwErrors(true); }
    }
    @Test default void referralCursorCasRejectsReplayAndStreamResetWithoutMutation() {
        apply(page(0,1,1,List.of(new Event(1,2000,referralClaim(),null))));
        rejected(page(0,1,1,List.of(new Event(1,2000,referralClaim(),null))));
        rejected(new Batch("c".repeat(32),1,1,1,5000,100,false,List.of()));
        assertEquals(1,count(ReferralTables.EVENTS)); assertEquals(1,count(ReferralTables.LATEST));
    }
    @Test default void referralImmutableAwardAmountAndDeliveryProofCannotBeRepricedOrReverted() {
        Award first = new Award("credit",CLAIM,"credit","CAD",500,3000,4000,3500,"delivered");
        apply(page(0,2,2,List.of(new Event(1,2000,referralClaim(),null),new Event(2,4000,null,first))));
        Award repriced = new Award("credit",CLAIM,"credit","CAD",999,3000,4000,3500,"delivered");
        rejected(page(2,3,3,List.of(new Event(3,4500,null,repriced))));
        Award reverted = new Award("credit",CLAIM,"credit","CAD",500,3000,0,0,"pending");
        rejected(page(2,3,3,List.of(new Event(3,4500,null,reverted))));
        assertEquals(2,count(ReferralTables.EVENTS));
    }
    @Test default void referralTerminalClaimAndFirstAcceptanceCannotBeRewritten() {
        Claim done = new Claim(CLAIM,playerUUID,"successful",1000,1100,2000,9000,true,false,false);
        apply(page(0,1,1,List.of(new Event(1,2500,done,null))));
        rejected(page(1,2,2,List.of(new Event(2,3000,referralClaim(),null))));
        Claim changed = new Claim(CLAIM,playerUUID,"successful",1000,1200,2000,9000,true,false,false);
        rejected(page(1,2,2,List.of(new Event(2,3000,changed,null))));
        assertEquals(1,count(ReferralTables.EVENTS));
    }
    @Test default void referralDifferentClaimCannotReuseNewcomerIdentity() {
        apply(page(0,1,1,List.of(new Event(1,2000,referralClaim(),null))));
        Claim second = new Claim("c".repeat(32),playerUUID,"pending",1000,1100,0,9000,true,false,false);
        rejected(page(1,2,2,List.of(new Event(2,3000,second,null))));
        assertEquals(1,count(ReferralTables.LATEST));
    }
    @Test default void referralFinalCleanStopPersistsProofButHotReloadDoesNotCountAsDowntime() {
        var service = new ReferralAnalyticsSvc(dbSystem(),serverInfo(),config(),org.mockito.Mockito.mock(com.djrapitops.plan.delivery.web.ResolverSvc.class));
        service.enable();
        try {
            service.setCollectionPaused(false); service.collect(System.currentTimeMillis());
            service.prepareServerShutdown().toCompletableFuture().join();
            assertEquals(1,count(ReferralTables.STOPS));
            assertTrue(ReferralCapture.state().paused());
            long oldCoverageRows = count(ReferralTables.COVERAGE);
            service.setCollectionPaused(false); service.collect(System.currentTimeMillis());
            assertEquals(0,count(ReferralTables.STOPS));
            assertEquals(oldCoverageRows+1,count(ReferralTables.COVERAGE),"Hot reload creates only the new capture run, never a fake downtime span");
        } finally { service.disable(); }
    }
    @Test default void referralPausedCollectionCannotProveCleanServerStop() {
        var service = new ReferralAnalyticsSvc(dbSystem(),serverInfo(),config(),org.mockito.Mockito.mock(com.djrapitops.plan.delivery.web.ResolverSvc.class));
        service.enable();
        try {
            assertThrows(java.util.concurrent.CompletionException.class,() -> service.prepareServerShutdown().toCompletableFuture().join());
            assertEquals(0,count(ReferralTables.STOPS));
        } finally { service.disable(); }
    }
    @Test default void referralMutableSessionSnapshotCannotWriteIntervalsBeyondItsEnd() {
        db().executeInTransaction("INSERT INTO " + ReferralTables.MEMBERS + " (server_uuid,uuid,first_join) VALUES (?,?,?)",serverUUID().toString(),playerUUID.toString(),1000).join();
        executeTransactions(new ReferralSessionTransaction(serverUUID().asUUID(),playerUUID,1000,2000,
                new ReferralActivity.Snapshot(true,List.of(new ReferralActivity.Interval(1000,3000)))));
        String intervals = db().queryOptional("SELECT intervals FROM " + ReferralTables.ACTIVITY,r -> r.getString(1)).orElseThrow();
        assertTrue(intervals.contains("2000")); assertFalse(intervals.contains("3000"));
    }
}
