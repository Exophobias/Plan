package com.djrapitops.plan.store;

import com.djrapitops.plan.storage.database.transactions.ExecStatement;
import com.djrapitops.plan.storage.database.transactions.Transaction;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import java.sql.*;
import java.util.*;

/** Independent financial record tables; no identifiers reference Plan player rows. */
public final class StoreTables {
    public static final String FEED="plan_store_feed", EVENTS="plan_store_events", LATEST="plan_store_latest", DELETED="plan_store_deleted";
    public static final List<String> NAMES=List.of(FEED,EVENTS,LATEST,DELETED);
    private StoreTables() { }
    /** Plan logs some transaction failures and completes its Future normally; never treat that as commit. */
    public static java.util.concurrent.CompletableFuture<Void> commit(com.djrapitops.plan.storage.database.Database database, Transaction transaction) {
        return database.executeTransaction(transaction).thenRun(() -> {
            if (!transaction.wasSuccessful()) throw new IllegalStateException("Store transaction did not commit");
        });
    }
    public static List<String> createStatements() {
        return List.of(
            "CREATE TABLE IF NOT EXISTS " + FEED + " (server_uuid VARCHAR(36) PRIMARY KEY, stream_id VARCHAR(32) NOT NULL, cursor_value BIGINT NOT NULL, source_as_of BIGINT NOT NULL, capture_started BIGINT NOT NULL, legacy_at BIGINT NOT NULL, legacy_records BIGINT NOT NULL, received_at BIGINT NOT NULL, pending INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS " + EVENTS + " (server_uuid VARCHAR(36) NOT NULL, sequence_value BIGINT NOT NULL, order_id VARCHAR(64), effect INTEGER NOT NULL, event_at BIGINT NOT NULL, record_json MEDIUMTEXT NOT NULL, PRIMARY KEY(server_uuid,sequence_value))",
            "CREATE TABLE IF NOT EXISTS " + LATEST + " (server_uuid VARCHAR(36) NOT NULL, entity_key VARCHAR(90) NOT NULL, order_id VARCHAR(64), record_json MEDIUMTEXT NOT NULL, PRIMARY KEY(server_uuid,entity_key))",
            "CREATE TABLE IF NOT EXISTS " + DELETED + " (server_uuid VARCHAR(36) NOT NULL, entity_key VARCHAR(90) NOT NULL, PRIMARY KEY(server_uuid,entity_key))"
        );
    }

    public abstract static class Tx extends Transaction {
        @Override protected final void performOperations() {
            try {
                performStoreOperations();
            } catch (RuntimeException failure) {
                // The shared SQLite connection is reused; unchecked validation/cancellation failures
                // must not leave writes for the next transaction to accidentally commit.
                execute(connection -> {
                    try { connection.rollback(); }
                    catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                    return true;
                });
                throw failure;
            }
        }
        protected abstract void performStoreOperations();
        public <T> List<T> rows(String sql, Extractor<T> extractor, Object... args) {
            return query(new QueryStatement<List<T>>(sql) {
                @Override public void prepare(PreparedStatement statement) throws SQLException { bind(statement, args); }
                @Override public List<T> processResults(ResultSet results) throws SQLException {
                    List<T> rows = new ArrayList<>(); while (results.next()) rows.add(extractor.get(results)); return rows;
                }
            });
        }
        public <T> Optional<T> optional(String sql, Extractor<T> extractor, Object... args) {
            return Optional.ofNullable(one(sql, extractor, null, args));
        }
        protected void sql(String sql, Object... args) { execute(statement(sql, args)); }
        protected <T> T one(String sql, Extractor<T> extractor, T absent, Object... args) {
            return query(new QueryStatement<T>(sql) {
                @Override public void prepare(PreparedStatement statement) throws SQLException { bind(statement, args); }
                @Override public T processResults(ResultSet results) throws SQLException {
                    return results.next() ? extractor.get(results) : absent;
                }
            });
        }

    }
    @FunctionalInterface public interface Extractor<T> { T get(ResultSet results) throws SQLException; }
    public static ExecStatement statement(String sql, Object... args) {
        return new ExecStatement(sql) {
            @Override public void prepare(PreparedStatement statement) throws SQLException { bind(statement, args); }
        };
    }
    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }
}
