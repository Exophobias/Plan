package com.djrapitops.plan.store;

import com.djrapitops.plan.storage.database.Database;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exact backup transfer. Independent feed histories are deliberately never merged or rebased. */
public final class StoreTransfer {
    private static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put(StoreTables.FEED,List.of("server_uuid","stream_id","cursor_value","source_as_of","capture_started","legacy_at","legacy_records","received_at","pending"));
        COLUMNS.put(StoreTables.EVENTS,List.of("server_uuid","sequence_value","order_id","effect","event_at","record_json"));
        COLUMNS.put(StoreTables.LATEST,List.of("server_uuid","entity_key","order_id","record_json"));
        COLUMNS.put(StoreTables.DELETED,List.of("server_uuid","entity_key"));
    }
    private StoreTransfer() { }
    public record Snapshot(Map<String,List<List<Object>>> tables) {
        public boolean hasData() { return tables.values().stream().anyMatch(rows -> !rows.isEmpty()); }
    }
    public static Snapshot capture(Database source) {
        AtomicReference<Snapshot> result = new AtomicReference<>();
        StoreTables.commit(source, new StoreTables.Tx() {
            @Override protected IsolationLevel getDesiredIsolationLevel() { return IsolationLevel.REPEATABLE_READ; }
            @Override protected void performStoreOperations() {
                Map<String,List<List<Object>>> tables = new LinkedHashMap<>();
                long[] count = {0}, bytes = {0};
                COLUMNS.forEach((table, columns) -> tables.put(table, rows("SELECT " + String.join(",",columns) + " FROM " + table + " LIMIT 200001", r -> {
                    if (++count[0] > 200000) throw new IllegalStateException("Store backup exceeds bounded transfer capacity; use a native database backup");
                    List<Object> row = new ArrayList<>();
                    for (int i=1;i<=columns.size();i++) {
                        Object value = r.getObject(i); row.add(value);
                        bytes[0] += value instanceof String ? 2L*((String)value).length() : 16;
                        if (bytes[0] > 64L*1024*1024) throw new IllegalStateException("Store backup exceeds bounded transfer capacity; use a native database backup");
                    }
                    return Collections.unmodifiableList(row);
                })));
                result.set(new Snapshot(Collections.unmodifiableMap(tables)));
            }
        }).join();
        return result.get();
    }
    /** Called before the ordinary copy processor clears or writes any destination table. */
    public static void preflight(Database destination, Snapshot source, boolean replacement, boolean changesServerIdentity) {
        java.util.concurrent.atomic.AtomicBoolean populated = new java.util.concurrent.atomic.AtomicBoolean();
        StoreTables.commit(destination, new StoreTables.Tx() {
            @Override protected void performStoreOperations() {
                populated.set(hasData(this));
            }
        }).join();
        if (!replacement && source.hasData() && populated.get())
            throw new IllegalStateException("Two populated Store histories cannot be merged; use a full replacement backup or native database transfer");
        if (!replacement && changesServerIdentity && (source.hasData() || populated.get()))
            throw new IllegalStateException("Store history cannot be transferred with server replacement or UUID rebasing");
    }
    public static void restore(Database destination, Snapshot source) {
        if (!source.hasData()) return;
        StoreTables.commit(destination, new StoreTables.Tx() {
            @Override protected void performStoreOperations() {
                // Recheck after the ordinary copy work: concurrent collection must not be overwritten.
                if (hasData(this)) throw new IllegalStateException("Destination Store history changed during database transfer");
                COLUMNS.forEach((table, columns) -> {
                    String insert = "INSERT INTO " + table + " (" + String.join(",",columns) + ") VALUES ("
                            + String.join(",",Collections.nCopies(columns.size(),"?")) + ")";
                    for (List<Object> row : source.tables.get(table)) sql(insert,row.toArray());
                });
            }
        }).join();
    }
    private static boolean hasData(StoreTables.Tx tx) {
        for (String table : StoreTables.NAMES)
            if (tx.one("SELECT 1 FROM " + table + " LIMIT 1", r -> true,false)) return true;
        return false;
    }
}
