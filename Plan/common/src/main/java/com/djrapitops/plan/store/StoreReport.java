package com.djrapitops.plan.store;

import com.djrapitops.plan.storage.database.Database;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.djrapitops.plan.store.StoreAnalyticsService.*;

/** Bounded immutable aggregates. Monetary currencies are never combined and state is not cash settlement. */
public final class StoreReport {
    static final long DAY=86400, SAFE=9_007_199_254_740_991L;
    public record Observation(Event event,boolean effect) { }
    public record Feed(long asOf,long received,long capture,long legacyAt,long legacyRecords) { }
    public record Dataset(List<Event> latest,List<Observation> history,Feed feed) { }
    private StoreReport() { }
    static Dataset load(Database db,UUID server,long now) {
        AtomicReference<Dataset> result=new AtomicReference<>();
        StoreTables.commit(db,new StoreTables.Tx() {
            @Override protected IsolationLevel getDesiredIsolationLevel(){return IsolationLevel.REPEATABLE_READ;}
            @Override protected void performStoreOperations(){
                if(one("SELECT pending FROM "+StoreTables.FEED+" WHERE server_uuid=?",r->r.getInt(1),0,server.toString())!=0)
                    throw new IllegalStateException("Store snapshot is still being imported");
                long[] count={0},bytes={0};
                List<Event> latest=rows("SELECT record_json FROM "+StoreTables.LATEST+" WHERE server_uuid=? LIMIT 200001",r->decode(r.getString(1),count,bytes),server.toString());
                List<Observation> events=rows("SELECT record_json,effect FROM "+StoreTables.EVENTS+" WHERE server_uuid=? AND event_at>=? ORDER BY sequence_value LIMIT 200001",r->new Observation(decode(r.getString(1),count,bytes),r.getInt(2)==1),server.toString(),now/DAY*DAY-180*DAY);
                Feed feed=one("SELECT source_as_of,received_at,capture_started,legacy_at,legacy_records FROM "+StoreTables.FEED+" WHERE server_uuid=?",
                        r->new Feed(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5)),new Feed(0,0,0,0,0),server.toString());
                result.set(new Dataset(List.copyOf(latest),List.copyOf(events),feed));
            }
            private Event decode(String json,long[] count,long[] bytes){
                if(++count[0]>200000 || (bytes[0]+=2L*json.length())>64L*1024*1024)throw new IllegalStateException("Store report exceeds bounded capacity");
                return StoreRecords.decode(json);
            }
        }).join();return result.get();
    }
    static List<String> currencies(Dataset data){
        Set<String> all=new TreeSet<>();for(Event e:data.latest){String c=(String)e.record.get("currency");if(c!=null)all.add(c);}
        if(all.size()>200)throw new IllegalStateException("Store currency capacity exceeded");return List.copyOf(all);
    }
    static Map<String,Object> calculate(UUID server,long now,int days,String currency,Dataset data,boolean paused,boolean failed) {
        long until=now/DAY*DAY,from=until-days*DAY,previous=from-days*DAY;
        Feed f=data.feed;boolean usable=!failed && f.asOf>0;
        String status=failed?"unavailable":paused?"paused":f.asOf==0?"collecting":now-f.received>600 || now-f.asOf>600?"stale":"current";
        List<String> available=currencies(data);
        List<Event> latest=failed?List.of():data.latest;
        List<Observation> history=failed?List.of():data.history;
        Map<String,Object> summary=summary(latest,from,until,currency),previousSummary=summary(latest,previous,from,currency);
        Map<String,Map<String,Object>> daily=new LinkedHashMap<>();
        for(long day=from;day<until;day+=DAY){String date=Instant.ofEpochSecond(day).atOffset(ZoneOffset.UTC).toLocalDate().toString();
            Map<String,Object> row=map("date",date,"covered",usable && f.capture<=day && f.asOf>=day+DAY,"saved_orders",0L,"completed_payments",0L);
            for(String field:List.of("order_amount_minor","completed_amount_minor","refund_minor","reversal_minor","credit_awarded_minor","credit_captured_minor"))row.put(field,currency==null?null:0L);
            daily.put(date,row);
        }
        for(Observation observation:history){Event e=observation.event;Map<String,Object> r=e.record;
            if(!observation.effect || Boolean.TRUE.equals(r.get("legacy")) || !matches(r,currency))continue;
            long time=timestamp(r,e.type.equals("payment")||e.type.equals("credit_funding")?"state_recorded_at":"created_at");
            if(time<from || time>=until)continue;
            Map<String,Object> row=daily.get(Instant.ofEpochSecond(time).atOffset(ZoneOffset.UTC).toLocalDate().toString());
            switch(e.type){
                case "order" -> {add(row,"saved_orders",1);money(row,"order_amount_minor",r.get("amount_cents"),currency);}
                case "payment" -> {if("completed".equals(r.get("status")) && !"completed".equals(r.get("previous_status"))){add(row,"completed_payments",1);money(row,"completed_amount_minor",r.get("amount_cents"),currency);}}
                case "adjustment" -> money(row,"refund".equals(r.get("kind"))?"refund_minor":"reversal_minor",r.get("amount_cents"),currency);
                case "credit_award" -> money(row,"credit_awarded_minor",r.get("amount_cents"),currency);
                case "credit_funding" -> {if("captured".equals(r.get("state")))money(row,"credit_captured_minor",r.get("credit_cents"),currency);}
                default -> { }
            }
        }
        return map("schema_version",1,"server_uuid",server.toString(),"generated_at",ms(now),
                "period",map("days",days,"from",ms(from),"until",ms(until),"time_zone","UTC","currency",currency,
                        "complete",usable && f.capture<=from && f.asOf>=until,"previous_complete",usable && f.capture<=previous && f.asOf>=from),
                "coverage",map("status",status,"source_as_of",nullableMs(f.asOf),"received_at",nullableMs(f.received),"capture_started_at",nullableMs(f.capture),
                        "legacy_snapshot_at",nullableMs(f.legacyAt),"legacy_records",f.legacyRecords,"adjustments_complete",false),
                "available_currencies",available,"summary",summary,"previous_summary",previousSummary,
                "currencies",state(latest,currency),"daily",List.copyOf(daily.values()),"products",products(latest,from,until,currency));
    }
    private static Map<String,Object> summary(List<Event> latest,long from,long until,String currency){
        Map<String,Object> out=map("saved_orders",0L,"known_payers",0L,"repeat_payers",0L,"baseline_orders",0L,"unknown_currency_orders",0L,"order_amount_minor",currency==null?null:0L);
        Map<String,Integer> payers=new HashMap<>();
        for(Event e:latest){Map<String,Object> r=e.record;if(!e.type.equals("order") || !matches(r,currency))continue;
            if(Boolean.TRUE.equals(r.get("legacy"))){add(out,"baseline_orders",1);continue;}
            long time=timestamp(r,"created_at");if(time<from||time>=until)continue;
            add(out,"saved_orders",1); if(r.get("currency")==null)add(out,"unknown_currency_orders",1);
            payers.merge((String)r.get("payer_key"),1,Integer::sum);money(out,"order_amount_minor",r.get("amount_cents"),currency);
        }
        out.put("known_payers",(long)payers.size());out.put("repeat_payers",payers.values().stream().filter(n->n>=2).count());return out;
    }
    private static List<Map<String,Object>> state(List<Event> latest,String currency){
        Map<String,Map<String,Object>> rows=new TreeMap<>();Map<String,Map<String,Map<String,Object>>> payments=new HashMap<>();
        for(Event e:latest){Map<String,Object> r=e.record;if(!matches(r,currency))continue;String c=(String)r.get("currency"),key=c==null?"":c;
            Map<String,Object> row=rows.computeIfAbsent(key,k->map("currency",c,"saved_orders",0L,"order_amount_minor",c==null?null:0L,"payments",new ArrayList<>(),
                    "adjustments",map("refund_minor",0L,"reversal_minor",0L,"count",0L),"credits",map("awarded_minor",0L,"reserved_minor",0L,"captured_minor",0L,"released_minor",0L,"refunded_minor",0L)));
            switch(e.type){
                case "order" -> {add(row,"saved_orders",1);money(row,"order_amount_minor",r.get("amount_cents"),c);}
                case "payment" -> {
                    String p=r.get("status")+":"+r.get("origin");Map<String,Object> entry=payments.computeIfAbsent(key,k->new TreeMap<>()).computeIfAbsent(p,k->map("status",r.get("status"),"origin",r.get("origin"),"count",0L,"amount_minor",c==null?null:0L,"unknown_amount_count",0L));
                    add(entry,"count",1);if(r.get("amount_cents")==null)add(entry,"unknown_amount_count",1);money(entry,"amount_minor",r.get("amount_cents"),c);
                }
                case "adjustment" -> {Map<String,Object> a=object(row.get("adjustments"));add(a,"count",1);add(a,"refund".equals(r.get("kind"))?"refund_minor":"reversal_minor",value(r,"amount_cents"));}
                case "credit_award" -> add(object(row.get("credits")),"awarded_minor",value(r,"amount_cents"));
                case "credit_funding" -> add(object(row.get("credits")),r.get("state")+"_minor",value(r,"credit_cents"));
                default -> { }
            }
        }
        rows.forEach((k,row)->row.put("payments",List.copyOf(payments.getOrDefault(k,Map.of()).values())));return List.copyOf(rows.values());
    }
    private static List<Map<String,Object>> products(List<Event> latest,long from,long until,String currency){
        Map<String,Map<String,Object>> products=new HashMap<>();Map<String,Set<String>> orders=new HashMap<>();
        for(Event e:latest){Map<String,Object> r=e.record;long time=timestamp(r,"created_at");
            if(!e.type.equals("order")||Boolean.TRUE.equals(r.get("legacy"))||!matches(r,currency)||time<from||time>=until)continue;
            for(Object item:(List<?>)r.get("lines")){Map<String,Object> line=object(item);String c=(String)r.get("currency"),key=String.valueOf(c)+"\u0000"+line.get("product_id")+"\u0000"+line.get("name");
                Map<String,Object> row=products.computeIfAbsent(key,k->map("name",line.get("name"),"currency",c,"quantity",0L,"order_count",0L,"amount_minor",c==null?null:0L));
                add(row,"quantity",value(line,"quantity"));money(row,"amount_minor",Math.multiplyExact(value(line,"quantity"),value(line,"unit_cents")),c);
                orders.computeIfAbsent(key,k->new HashSet<>()).add((String)r.get("order_id"));
            }
        }
        products.forEach((key,row)->row.put("order_count",(long)orders.get(key).size()));
        return products.values().stream().sorted(Comparator.<Map<String,Object>>comparingLong(r->value(r,"quantity")).reversed().thenComparing(r->String.valueOf(r.get("name")))).limit(50).toList();
    }
    private static boolean matches(Map<String,Object> r,String currency){return currency==null||currency.equals(r.get("currency"));}
    private static long timestamp(Map<String,Object> r,String key){return r.get(key)==null?0:value(r,key);}
    private static long value(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    private static void add(Map<String,Object> r,String key,long value){long sum=Math.addExact(((Number)r.get(key)).longValue(),value);if(sum>SAFE)throw new IllegalStateException("Store aggregate exceeds safe integer range");r.put(key,sum);}
    private static void money(Map<String,Object> r,String key,Object value,String currency){if(currency==null||r.get(key)==null)return;if(value==null){r.put(key,null);return;}add(r,key,((Number)value).longValue());}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    private static long ms(long seconds){return Math.multiplyExact(seconds,1000);}
    private static Long nullableMs(long seconds){return seconds==0?null:ms(seconds);}
    static Map<String,Object> map(Object...pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
}
