package com.djrapitops.plan.store;
import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.djrapitops.plan.store.StoreAnalyticsService.*;
import static com.djrapitops.plan.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

public interface StoreDatabaseTest extends DatabaseTestPreparer {
    private void apply(Batch b){StoreTables.commit(db(),new StoreJournal.Apply(serverUUID().asUUID(),b,6000)).join();}
    private long count(String table){return db().queryOptional("SELECT COUNT(*) FROM "+table,r->r.getLong(1)).orElseThrow();}
    @Test default void storeReplayAndRestartKeepExactDurableCursorAndNeverPersistPlayerUUIDs(){
        Map<String,Object> order=order(1000,"USD",false);order.put("payer_uuid",playerUUID.toString());order.put("recipient_uuid",player2UUID.toString());
        apply(batch(0,1,List.of(event(1,"order",order))));forcePersistenceCheck();
        assertEquals(1L,db().queryOptional("SELECT cursor_value FROM "+StoreTables.FEED,r->r.getLong(1)).orElseThrow());
        assertThrows(RuntimeException.class,()->apply(batch(0,1,List.of(event(1,"order",order)))));
        apply(batch(1,1,List.of()));assertEquals(1,count(StoreTables.EVENTS));
        String json=db().queryOptional("SELECT record_json FROM "+StoreTables.EVENTS,r->r.getString(1)).orElseThrow();
        assertFalse(json.contains(playerUUID.toString()));assertFalse(json.contains("payer_uuid"));assertFalse(json.contains("recipient_uuid"));
    }
    @Test default void storePartialSnapshotCannotPublishUntilCaughtUpEvenAfterRestart(){
        apply(batch(0,2,List.of(event(1,"order",order(1000,"USD",false)))));forcePersistenceCheck();
        assertThrows(RuntimeException.class,()->StoreReport.load(db(),serverUUID().asUUID(),6000));
        apply(batch(1,2,List.of(event(2,"payment",payment(2000,"completed",null,false)))));
        assertEquals(6000,StoreReport.load(db(),serverUUID().asUUID(),6000).feed().asOf());
    }
    @Test default void storeInvalidLateAssociationRollsBackEarlierPageWrites(){
        Map<String,Object> invalid=payment(2000,"completed",null,false);invalid.put("order_id",OTHER);
        assertThrows(RuntimeException.class,()->apply(batch(0,2,List.of(event(1,"order",order(1000,"USD",false)),event(2,"payment",invalid)))));
        assertEquals(0,count(StoreTables.EVENTS));assertEquals(0,count(StoreTables.LATEST));assertEquals(0,count(StoreTables.FEED));
        apply(batch(0,1,List.of(event(1,"order",order(1000,"USD",false)))));assertEquals(1,count(StoreTables.EVENTS));
    }
    @Test default void storeDeletionRetiresWholeOrderAndFutureReplayCannotResurrectItsAdjustments(){
        apply(batch(0,2,List.of(event(1,"order",order(1000,"USD",false)),event(2,"payment",payment(2000,"completed",null,false)))));
        apply(batch(2,3,List.of(event(3,"deletion",StoreReport.map("order_id",ID,"payment_id",ID,"recorded_at",5000L)))));
        assertEquals(0,count(StoreTables.LATEST));assertEquals(0,count(StoreTables.EVENTS));assertEquals(2,count(StoreTables.DELETED));
        var adjustment=StoreReport.map("adjustment_id",OTHER,"payment_id",ID,"kind","refund","amount_cents",100L,"currency","USD","created_at",4000L,"legacy",false);
        apply(batch(3,5,List.of(event(4,"order",order(1000,"USD",false)),event(5,"adjustment",adjustment))));
        assertEquals(0,count(StoreTables.LATEST));assertEquals(5L,db().queryOptional("SELECT cursor_value FROM "+StoreTables.FEED,r->r.getLong(1)).orElseThrow());
    }
    @Test default void storeRepeatedPaymentObservationDoesNotDuplicateTimelineEffectAndRefundKeepsCompletion(){
        Map<String,Object> completed=payment(2000,"completed",null,false);
        apply(batch(0,3,List.of(event(1,"order",order(1000,"USD",false)),event(2,"payment",completed),event(3,"payment",completed))));
        Map<String,Object> refund=payment(2000,"refunded","completed",false);refund.put("state_recorded_at",4000L);
        apply(batch(3,4,List.of(event(4,"payment",refund))));
        assertEquals(List.of(1,1,0,1),db().queryList("SELECT effect FROM "+StoreTables.EVENTS+" ORDER BY sequence_value",r->r.getInt(1)));
        assertEquals(4,count(StoreTables.EVENTS));assertEquals(2,count(StoreTables.LATEST));
    }
    @Test default void storeCopyRetainsExactFactsAndRefusesTwoPopulatedHistories(){
        apply(batch(0,1,List.of(event(1,"order",order(1000,"USD",false)))));
        var snapshot=StoreTransfer.capture(db());assertTrue(snapshot.hasData());
        assertThrows(IllegalStateException.class,()->StoreTransfer.preflight(db(),snapshot,false,false));
        db().executeTransaction(new com.djrapitops.plan.storage.database.transactions.commands.RemoveEverythingTransaction()).join();
        StoreTransfer.restore(db(),snapshot);assertEquals(snapshot,StoreTransfer.capture(db()));
    }
    @Test default void storeStreamAndSequenceGapsNeverAdvanceDurableProgress(){
        assertThrows(IllegalArgumentException.class,()->new StoreJournal.Apply(serverUUID().asUUID(),batch(0,1,List.of(event(2,"order",order(1000,"USD",false)))),6000));
        apply(batch(0,1,List.of(event(1,"order",order(1000,"USD",false)))));
        assertThrows(RuntimeException.class,()->apply(new Batch("d".repeat(32),1,1,1,6000,100,0,0,false,false,List.of())));
        assertEquals(STREAM,db().queryOptional("SELECT stream_id FROM "+StoreTables.FEED,r->r.getString(1)).orElseThrow());
    }
    @Test default void storeNativePhpExportImportsEveryFrozenRecordAndDeletionWithoutTranslation() throws Exception {
        String json;try(var input=getClass().getResourceAsStream("/store/website-export.json")){assertNotNull(input);json=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var root=com.google.gson.JsonParser.parseString(json).getAsJsonObject();var coverage=root.getAsJsonObject("coverage");
        List<Event> events=new ArrayList<>();for(var item:root.getAsJsonArray("events")){
            var e=item.getAsJsonObject();var normalized=new com.google.gson.JsonObject();normalized.add("sequence",e.get("sequence"));normalized.add("recordedAt",e.get("recorded_at"));normalized.add("type",e.get("type"));normalized.add("record",e.get("record"));events.add(StoreRecords.decode(normalized.toString()));
        }
        long asOf=root.get("source_as_of").getAsLong();
        var batch=new Batch(root.get("stream_id").getAsString(),root.get("from_exclusive").getAsLong(),root.get("through_inclusive").getAsLong(),root.get("high_watermark").getAsLong(),asOf,
                coverage.get("capture_started_at").getAsLong(),coverage.get("legacy_snapshot_at").getAsLong(),coverage.get("legacy_records").getAsLong(),root.get("has_more").getAsBoolean(),coverage.get("adjustments_complete").getAsBoolean(),events);
        StoreTables.commit(db(),new StoreJournal.Apply(serverUUID().asUUID(),batch,asOf)).join();
        assertEquals(batch.throughInclusive,db().queryOptional("SELECT cursor_value FROM "+StoreTables.FEED,r->r.getLong(1)).orElseThrow());
        assertTrue(count(StoreTables.DELETED)>0);assertFalse(StoreRecords.JSON.toJson(StoreReport.load(db(),serverUUID().asUUID(),asOf)).contains("payer_uuid"));
    }
}
