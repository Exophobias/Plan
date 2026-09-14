package com.djrapitops.plan.community;

import com.djrapitops.plan.storage.database.Database;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exact bounded backup transfer; independent observation histories are never merged or rebased. */
public final class CommunityTransfer {
    private static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put(CommunityTables.MEMBERS, List.of("server_uuid", "uuid", "first_seen"));
        COLUMNS.put(CommunityTables.ACTIVITY, List.of("server_uuid", "uuid", "session_start", "session_end", "valid", "intervals"));
        COLUMNS.put(CommunityTables.COVERAGE, List.of("run_id", "server_uuid", "start_ms", "end_ms", "flushed_at"));
        COLUMNS.put(CommunityTables.DELETED, List.of("uuid"));
        // Process proof never survives a restore: the intervening time cannot be certified.
    }
    private CommunityTransfer() { }
    public record Snapshot(Map<String, List<List<Object>>> tables) {
        public boolean hasData() { return tables.values().stream().anyMatch(rows -> !rows.isEmpty()); }
    }
    public static Snapshot capture(Database source) {
        AtomicReference<Snapshot> output = new AtomicReference<>();
        CommunityTables.commit(source, new CommunityTables.Tx() {
            @Override protected IsolationLevel getDesiredIsolationLevel() { return IsolationLevel.REPEATABLE_READ; }
            @Override protected void performCommunityOperations() {
                Map<String, List<List<Object>>> tables = new LinkedHashMap<>();
                long[] count = {0}, bytes = {0};
                COLUMNS.forEach((table, columns) -> tables.put(table, rows("SELECT " + String.join(",", columns) + " FROM " + table + " LIMIT 200001", r -> {
                    if (++count[0] > 200000) throw new IllegalStateException("Community backup capacity exceeded; use a native database backup");
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= columns.size(); i++) {
                        Object value = r.getObject(i); row.add(value);
                        bytes[0] += value instanceof String string ? 2L * string.length() : 16;
                        if (bytes[0] > 64L * 1024 * 1024) throw new IllegalStateException("Community backup capacity exceeded; use a native database backup");
                    }
                    return Collections.unmodifiableList(row);
                })));
                output.set(new Snapshot(Collections.unmodifiableMap(tables)));
            }
        }).join();
        return output.get();
    }
    public static void preflight(Database destination, Snapshot source, boolean replacement, boolean changesServerIdentity) {
        java.util.concurrent.atomic.AtomicBoolean populated = new java.util.concurrent.atomic.AtomicBoolean();
        CommunityTables.commit(destination, new CommunityTables.Tx() {
            @Override protected void performCommunityOperations() { populated.set(hasData(this)); }
        }).join();
        if (!replacement && source.hasData() && populated.get())
            throw new IllegalStateException("Two populated community histories cannot be merged");
        if (!replacement && changesServerIdentity && (source.hasData() || populated.get()))
            throw new IllegalStateException("Community history cannot be rebased to another server identity");
    }
    public static void restore(Database destination, Snapshot source) {
        if (!source.hasData()) return;
        CommunityTables.commit(destination, new CommunityTables.Tx() {
            @Override protected void performCommunityOperations() {
                if (hasData(this)) throw new IllegalStateException("Destination community history changed during transfer");
                COLUMNS.forEach((table, columns) -> {
                    String insert = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
                            + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
                    for (List<Object> row : source.tables().get(table)) sql(insert, row.toArray());
                });
                sql("DELETE FROM " + CommunityTables.STOPS);
            }
        }).join();
    }
    private static boolean hasData(CommunityTables.Tx tx) {
        for (String table : CommunityTables.NAMES)
            if (tx.one("SELECT 1 FROM " + table + " LIMIT 1", r -> true, false)) return true;
        return false;
    }
}
