package com.djrapitops.plan.store;

import java.util.*;
import static com.djrapitops.plan.store.StoreAnalyticsService.*;
import static com.djrapitops.plan.store.StoreRecords.*;

/** One cursor, all projections and erasures commit together; no history merging or identity rebasing. */
public final class StoreJournal {
    private StoreJournal() { }
    public static final class Apply extends StoreTables.Tx {
        private final String server;
        private final Batch batch;
        private final long received;
        private final Runnable admission;
        public Apply(UUID server,Batch batch,long received) { this(server,batch,received,()->{}); }
        public Apply(UUID server,Batch batch,long received,Runnable admission) {
            validate(batch,received); this.server=server.toString();this.batch=batch;this.received=received;this.admission=admission;
        }
        @Override protected void performStoreOperations() {
            admission.run();
            Checkpoint prior=one("SELECT stream_id,cursor_value FROM "+StoreTables.FEED+" WHERE server_uuid=?"+lockForUpdate(),
                    r->new Checkpoint(r.getString(1),r.getLong(2)),new Checkpoint(null,0),server);
            require(prior.streamId==null || prior.streamId.equals(batch.streamId),"Store stream changed; continuity review required");
            require(prior.sequence==batch.fromExclusive,"Store cursor changed; reread checkpoint");
            long[] previous=one("SELECT capture_started,legacy_at,legacy_records,source_as_of FROM "+StoreTables.FEED+" WHERE server_uuid=?",
                    r->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)},
                    new long[]{batch.captureStartedAt,batch.legacySnapshotAt,batch.legacyRecords,0},server);
            require(previous[0]==batch.captureStartedAt && previous[1]==batch.legacySnapshotAt && previous[2]==batch.legacyRecords
                    && batch.sourceAsOf>=previous[3],"Store capture continuity changed");
            for(Event raw:batch.events) {
                Event e=privateEvent(raw); String key=key(e); Map<String,Object> r=e.record;
                String order=(String)r.get("order_id");
                if(e.type.equals("adjustment")) {
                    if(deleted("payment:"+r.get("payment_id")))continue;
                    Event payment=latest("payment:"+r.get("payment_id"));
                    require(payment!=null,"Store adjustment has no preceding payment"); order=(String)payment.record.get("order_id");
                }
                if(e.type.equals("deletion")) {
                    tombstone("order:"+order); tombstone("payment:"+r.get("payment_id"));
                    for(String payment:rows("SELECT entity_key FROM "+StoreTables.LATEST+" WHERE server_uuid=? AND order_id=?",x->x.getString(1),server,order))
                        if(payment.startsWith("payment:"))tombstone(payment);
                    sql("DELETE FROM "+StoreTables.EVENTS+" WHERE server_uuid=? AND order_id=?",server,order);
                    sql("DELETE FROM "+StoreTables.LATEST+" WHERE server_uuid=? AND order_id=?",server,order);
                    continue;
                }
                if(order!=null && deleted("order:"+order)) {if(e.type.equals("payment"))tombstone(key);continue;}
                if(e.type.equals("payment") || e.type.equals("credit_funding"))
                    require(latest("order:"+order)!=null,"Store financial record has no preceding order");
                Event old=latest(key);
                boolean effect=old==null;
                if(old!=null) {
                    Map<String,Object> before=old.record;
                    if(Set.of("order","adjustment","credit_award").contains(e.type))
                        require(before.equals(r),"Immutable Store fact changed");
                    else {
                        List<String> frozen=e.type.equals("payment")?List.of("order_id","created_at"):List.of("order_id","payer_key","gross_cents","credit_cents","cash_cents","currency","created_at");
                        for(String field:frozen)require(Objects.equals(before.get(field),r.get(field)),"Immutable Store association changed");
                        String state=e.type.equals("payment")?"status":"state";
                        effect=!Objects.equals(before.get(state),r.get(state));
                        if(e.type.equals("payment") && effect)
                            require(Objects.equals(before.get("status"),r.get("previous_status")),"Store payment transition is discontinuous");
                        if(before.get("state_recorded_at")!=null && r.get("state_recorded_at")!=null)
                            require(((Number)r.get("state_recorded_at")).longValue()>=((Number)before.get("state_recorded_at")).longValue(),"Store state time moved backwards");
                    }
                }
                String json=JSON.toJson(e);
                Object time=r.get(e.type.equals("payment")||e.type.equals("credit_funding")?"state_recorded_at":"created_at");
                long eventAt=effect&&!Boolean.TRUE.equals(r.get("legacy"))&&time!=null?((Number)time).longValue():0;
                sql("INSERT INTO "+StoreTables.EVENTS+" (server_uuid,sequence_value,order_id,effect,event_at,record_json) VALUES (?,?,?,?,?,?)",server,e.sequence,order,effect?1:0,eventAt,json);
                if(old==null)sql("INSERT INTO "+StoreTables.LATEST+" (server_uuid,entity_key,order_id,record_json) VALUES (?,?,?,?)",server,key,order,json);
                else sql("UPDATE "+StoreTables.LATEST+" SET record_json=? WHERE server_uuid=? AND entity_key=?",json,server,key);
            }
            admission.run();
            long watermark=batch.hasMore?previous[3]:batch.sourceAsOf;
            if(prior.streamId==null)sql("INSERT INTO "+StoreTables.FEED+" (server_uuid,stream_id,cursor_value,source_as_of,capture_started,legacy_at,legacy_records,received_at,pending) VALUES (?,?,?,?,?,?,?,?,?)",
                    server,batch.streamId,batch.throughInclusive,watermark,batch.captureStartedAt,batch.legacySnapshotAt,batch.legacyRecords,received,batch.hasMore?1:0);
            else sql("UPDATE "+StoreTables.FEED+" SET cursor_value=?,source_as_of=?,received_at=?,pending=? WHERE server_uuid=?",batch.throughInclusive,watermark,received,batch.hasMore?1:0,server);
        }
        private boolean deleted(String key){return one("SELECT entity_key FROM "+StoreTables.DELETED+" WHERE server_uuid=? AND entity_key=?",r->true,false,server,key);}
        private void tombstone(String key){if(!deleted(key))sql("INSERT INTO "+StoreTables.DELETED+" (server_uuid,entity_key) VALUES (?,?)",server,key);}
        private Event latest(String key){Event e=one("SELECT record_json FROM "+StoreTables.LATEST+" WHERE server_uuid=? AND entity_key=?",r->decode(r.getString(1)),null,server,key);
            require(e==null||key.equals(key(e)),"Store entity changed under database collation");return e;}
    }
}
