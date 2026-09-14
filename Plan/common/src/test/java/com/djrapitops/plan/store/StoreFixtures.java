package com.djrapitops.plan.store;
import java.util.*;
import static com.djrapitops.plan.store.StoreAnalyticsService.*;
import static com.djrapitops.plan.store.StoreReport.map;

public final class StoreFixtures {
    public static final String ID="a".repeat(64),OTHER="b".repeat(64),STREAM="c".repeat(32);
    public static Map<String,Object> order(long at,String currency,boolean legacy){return map("order_id",ID,"payer_key",ID,"payer_uuid",null,"recipient_uuid",null,"created_at",at,
            "currency",currency,"amount_cents",500L,"legacy",legacy,"lines",List.of(map("product_id",ID,"name","Example","quantity",1L,"unit_cents",500L)));}
    public static Map<String,Object> payment(long at,String status,String previous,boolean legacy){return map("payment_id",ID,"order_id",ID,"status",status,"previous_status",previous,
            "origin",legacy?"unknown":"manual","amount_cents",500L,"currency","USD","created_at",at,"state_recorded_at",legacy?null:at,"legacy",legacy);}
    public static Event event(long sequence,String type,Map<String,Object> record){return new Event(sequence,5000,type,record);}
    public static Batch batch(long from,long high,List<Event> events){return new Batch(STREAM,from,from+events.size(),high,6000,100,0,0,from+events.size()<high,false,events);}
    private StoreFixtures() { }
}
