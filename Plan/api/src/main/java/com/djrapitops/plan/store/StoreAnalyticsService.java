package com.djrapitops.plan.store;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted in-process, read-only Store journal import, schema 1. Times in this API are epoch SECONDS.
 * Money is integer currency cents as supplied by Store, never a converted or processor-settled amount.
 * Record maps use the documented Store export schema and JSON primitives (integer numbers only).
 * Plan validates them and discards payer/recipient UUIDs before persistence. No credentials belong here.
 * Call off the game thread. Successful completion proves the complete batch and cursor committed.
 */
public interface StoreAnalyticsService {
    static StoreAnalyticsService getInstance() {
        return Optional.ofNullable(Holder.service.get()).orElseThrow(() -> new IllegalStateException("Store analytics unavailable"));
    }
    Checkpoint getCheckpoint(UUID serverUUID);
    CompletionStage<Void> applyBatch(UUID serverUUID, Batch batch);
    /** Runtime status only: pauses never manufacture financial coverage or modify imported facts. */
    void setPaused(boolean paused);

    final class Checkpoint {
        public final String streamId;
        public final long sequence;
        public Checkpoint(String streamId, long sequence) { this.streamId = streamId; this.sequence = sequence; }
    }
    final class Batch {
        public final String streamId;
        public final long fromExclusive, throughInclusive, highWatermark, sourceAsOf, captureStartedAt, legacySnapshotAt, legacyRecords;
        public final boolean hasMore, adjustmentsComplete;
        public final List<Event> events;
        public Batch(String streamId, long fromExclusive, long throughInclusive, long highWatermark, long sourceAsOf,
                     long captureStartedAt, long legacySnapshotAt, long legacyRecords, boolean hasMore,
                     boolean adjustmentsComplete, List<Event> events) {
            this.streamId=Objects.requireNonNull(streamId); this.fromExclusive=fromExclusive; this.throughInclusive=throughInclusive;
            this.highWatermark=highWatermark; this.sourceAsOf=sourceAsOf; this.captureStartedAt=captureStartedAt;
            this.legacySnapshotAt=legacySnapshotAt; this.legacyRecords=legacyRecords; this.hasMore=hasMore;
            this.adjustmentsComplete=adjustmentsComplete; this.events=Collections.unmodifiableList(new ArrayList<>(events));
        }
    }
    final class Event {
        public final long sequence, recordedAt;
        public final String type;
        public final Map<String,Object> record;
        public Event(long sequence, long recordedAt, String type, Map<String,?> record) {
            this.sequence=sequence; this.recordedAt=recordedAt; this.type=Objects.requireNonNull(type);
            this.record=copy(record,0);
        }
        private static Map<String,Object> copy(Map<?,?> input,int depth) {
            if (depth>4 || input.size()>32) throw new IllegalArgumentException("Store record exceeds structural bounds");
            Map<String,Object> out=new LinkedHashMap<>();
            input.forEach((key,value) -> {
                if (!(key instanceof String)) throw new IllegalArgumentException("Invalid Store record key");
                out.put((String)key, freeze(value,depth+1));
            });
            return Collections.unmodifiableMap(out);
        }
        private static Object freeze(Object value,int depth) {
            if (depth>4) throw new IllegalArgumentException("Store record exceeds structural depth");
            if (value==null || value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Integer) return value;
            if (value instanceof Map) return copy((Map<?,?>)value,depth);
            if (value instanceof List && ((List<?>)value).size()<=100) {
                List<Object> out=new ArrayList<>(); for(Object item:(List<?>)value) out.add(freeze(item,depth+1));
                return Collections.unmodifiableList(out);
            }
            throw new IllegalArgumentException("Invalid Store record value");
        }
    }
    final class Holder {
        static final AtomicReference<StoreAnalyticsService> service=new AtomicReference<>();
        private Holder() { }
        static void set(StoreAnalyticsService value) { service.set(value); }
    }
}
