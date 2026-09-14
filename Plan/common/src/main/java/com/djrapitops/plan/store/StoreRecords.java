package com.djrapitops.plan.store;

import com.google.gson.*;
import java.util.*;
import static com.djrapitops.plan.store.StoreAnalyticsService.*;

/** Strict version-one record boundary, shared by import and focused contract tests. */
public final class StoreRecords {
    static final Gson JSON=new GsonBuilder().serializeNulls().create();
    static final long MAX=1_000_000_000_000L, MAX_SEQUENCE=9_007_199_254_740_991L;
    static final Set<String> STATUSES=Set.of("pending","completed","refunded","reversed","denied");
    static final Set<String> ORIGINS=Set.of("paypal","stripe","manual","api","free","credit_only","credits_legacy","unknown");
    private StoreRecords() { }
    public static void validate(Batch b,long now) {
        require(b!=null && b.streamId.matches("[a-f0-9]{32}"),"Invalid Store stream");
        require(b.fromExclusive>=0 && b.throughInclusive>=b.fromExclusive && b.highWatermark>=b.throughInclusive
                && b.highWatermark<=MAX_SEQUENCE && b.events.size()<=100 && b.throughInclusive-b.fromExclusive==b.events.size()
                && b.hasMore==(b.throughInclusive<b.highWatermark) && (!b.hasMore || !b.events.isEmpty()),"Invalid Store page");
        require(b.sourceAsOf>0 && b.sourceAsOf<=now+120 && b.captureStartedAt>0 && b.captureStartedAt<=b.sourceAsOf
                && b.legacySnapshotAt>=0 && b.legacySnapshotAt<=b.sourceAsOf && b.legacyRecords>=0
                && b.legacyRecords<=MAX_SEQUENCE && (b.legacyRecords==0 || b.legacySnapshotAt>0)
                && !b.adjustmentsComplete,"Invalid Store coverage");
        long sequence=b.fromExclusive; long bytes=0;
        for(Event e:b.events) {
            require(e!=null && e.sequence==++sequence && e.recordedAt>0 && e.recordedAt<=b.sourceAsOf,"Invalid Store event");
            validate(e.type,e.record,b.sourceAsOf);
            bytes+=JSON.toJson(e).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            require(bytes<=1_048_576,"Store batch too large");
        }
    }
    public static void validate(String type,Map<String,Object> r,long asOf) {
        boolean legacy=!type.equals("deletion") && bool(r,"legacy");
        switch(type) {
            case "order" -> {
                keys(r,"order_id","payer_key","payer_uuid","recipient_uuid","created_at","currency","amount_cents","lines","legacy");
                id(r,"order_id"); id(r,"payer_key"); uuid(r,"payer_uuid"); uuid(r,"recipient_uuid"); currency(r,true);
                time(r,"created_at",asOf,legacy); long amount=number(r,"amount_cents",0,MAX);
                require(r.get("lines") instanceof List<?>,"Invalid Store lines"); List<?> lines=(List<?>)r.get("lines");
                require(!lines.isEmpty() && lines.size()<=100,"Invalid Store line count"); long total=0;
                for(Object raw:lines) {
                    require(raw instanceof Map<?,?>,"Invalid Store line"); @SuppressWarnings("unchecked") Map<String,Object> line=(Map<String,Object>)raw;
                    keys(line,"product_id","name","quantity","unit_cents"); id(line,"product_id");
                    String name=text(line,"name"); require(name.codePointCount(0,name.length())<=160 && name.codePoints().noneMatch(Character::isISOControl),"Invalid product label");
                    long quantity=number(line,"quantity",1,10000), unit=number(line,"unit_cents",0,MAX);
                    total=Math.addExact(total,Math.multiplyExact(quantity,unit)); require(total<=MAX,"Invalid Store order amount");
                }
                require(total==amount,"Store order lines do not balance");
                require(!legacy || r.get("payer_uuid")==null,"Legacy identity cannot be inferred");
            }
            case "payment" -> {
                keys(r,"payment_id","order_id","status","previous_status","origin","amount_cents","currency","created_at","state_recorded_at","legacy");
                id(r,"payment_id"); id(r,"order_id"); member(r,"status",STATUSES); member(r,"origin",ORIGINS);
                if(r.get("previous_status")!=null) member(r,"previous_status",STATUSES);
                nullableNumber(r,"amount_cents"); currency(r,true); time(r,"created_at",asOf,true); time(r,"state_recorded_at",asOf,legacy);
                require(!legacy || r.get("state_recorded_at")==null && r.get("previous_status")==null && "unknown".equals(r.get("origin")),"Legacy payment is a baseline");
            }
            case "adjustment" -> {
                keys(r,"adjustment_id","payment_id","kind","amount_cents","currency","created_at","legacy");
                id(r,"adjustment_id"); id(r,"payment_id"); member(r,"kind",Set.of("refund","reversal"));
                number(r,"amount_cents",0,MAX); currency(r,false); time(r,"created_at",asOf,legacy);
            }
            case "credit_award" -> {
                keys(r,"award_id","payer_key","amount_cents","currency","created_at","legacy");
                id(r,"award_id"); id(r,"payer_key"); number(r,"amount_cents",0,MAX); currency(r,false); time(r,"created_at",asOf,legacy);
            }
            case "credit_funding" -> {
                keys(r,"order_id","payer_key","gross_cents","credit_cents","cash_cents","currency","state","created_at","state_recorded_at","legacy");
                id(r,"order_id"); id(r,"payer_key"); currency(r,false);
                long gross=number(r,"gross_cents",0,MAX),credit=number(r,"credit_cents",0,MAX),cash=number(r,"cash_cents",0,MAX);
                require(gross==Math.addExact(credit,cash),"Store funding does not balance");
                member(r,"state",Set.of("reserved","captured","released","refunded")); time(r,"created_at",asOf,true); time(r,"state_recorded_at",asOf,legacy);
                require(!legacy || r.get("state_recorded_at")==null,"Legacy funding is a baseline");
            }
            case "deletion" -> {
                keys(r,"order_id","payment_id","recorded_at"); id(r,"order_id"); id(r,"payment_id"); time(r,"recorded_at",asOf,false);
            }
            default -> throw new IllegalArgumentException("Unsupported Store record");
        }
    }
    static Event privateEvent(Event e) {
        Map<String,Object> r=new LinkedHashMap<>(e.record); r.remove("payer_uuid"); r.remove("recipient_uuid");
        return new Event(e.sequence,e.recordedAt,e.type,r);
    }
    static String key(Event e) {
        String field=switch(e.type) {case "order","credit_funding","deletion" -> "order_id"; case "payment" -> "payment_id";
            case "adjustment" -> "adjustment_id"; case "credit_award" -> "award_id"; default -> throw new IllegalArgumentException("Unknown record");};
        return e.type+":"+e.record.get(field);
    }
    static Event decode(String json) {
        JsonObject root=JsonParser.parseString(json).getAsJsonObject();
        @SuppressWarnings("unchecked") Map<String,Object> record=(Map<String,Object>)primitive(root.get("record"));
        return new Event(root.get("sequence").getAsLong(),root.get("recordedAt").getAsLong(),root.get("type").getAsString(),record);
    }
    private static Object primitive(JsonElement e) {
        if(e.isJsonNull())return null;
        if(e.isJsonArray()){List<Object> out=new ArrayList<>();e.getAsJsonArray().forEach(x->out.add(primitive(x)));return out;}
        if(e.isJsonObject()){Map<String,Object> out=new LinkedHashMap<>();e.getAsJsonObject().entrySet().forEach(x->out.put(x.getKey(),primitive(x.getValue())));return out;}
        JsonPrimitive p=e.getAsJsonPrimitive();return p.isBoolean()?p.getAsBoolean():p.isNumber()?p.getAsBigDecimal().longValueExact():p.getAsString();
    }
    static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException(reason);}
    static void keys(Map<String,Object> r,String...keys){require(r.keySet().equals(Set.of(keys)),"Unexpected Store fields");}
    static boolean bool(Map<String,Object> r,String k){require(r.get(k) instanceof Boolean,"Invalid Store boolean");return (Boolean)r.get(k);}
    static String text(Map<String,Object> r,String k){require(r.get(k) instanceof String && !((String)r.get(k)).isEmpty(),"Invalid Store text");return (String)r.get(k);}
    static String id(Map<String,Object> r,String k){String s=text(r,k);require(s.matches("[a-f0-9]{64}"),"Invalid Store identifier");return s;}
    static long number(Map<String,Object> r,String k,long min,long max){Object n=r.get(k);require(n instanceof Long || n instanceof Integer,"Invalid Store integer");long v=((Number)n).longValue();require(v>=min&&v<=max,"Invalid Store integer range");return v;}
    private static void nullableNumber(Map<String,Object> r,String k){if(r.get(k)!=null)number(r,k,0,MAX);}
    private static void time(Map<String,Object> r,String k,long asOf,boolean unknown){if(r.get(k)==null){require(unknown,"Missing Store timestamp");return;}number(r,k,1,asOf);}
    private static void currency(Map<String,Object> r,boolean unknown){if(r.get("currency")==null){require(unknown,"Missing currency");return;}require(text(r,"currency").matches("[A-Z]{3}"),"Invalid currency");}
    private static void uuid(Map<String,Object> r,String k){if(r.get(k)==null)return;String s=text(r,k);require(s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")&&!s.equals("00000000-0000-0000-0000-000000000000"),"Invalid UUID");}
    private static void member(Map<String,Object> r,String k,Set<String> values){require(values.contains(text(r,k)),"Unknown Store value");}
}
