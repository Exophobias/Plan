package com.djrapitops.plan.referrals;

import com.djrapitops.plan.storage.database.transactions.ExecStatement;
import com.djrapitops.plan.storage.database.transactions.Transaction;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import java.sql.*;
import java.util.*;

/** No foreign keys to expiring raw player/session rows: cohort denominators survive routine upkeep. */
public final class ReferralTables {
    public static final String MEMBERS = "plan_referral_members";
    public static final String ACTIVITY = "plan_referral_activity";
    public static final String COVERAGE = "plan_referral_coverage";
    public static final String FEED = "plan_referral_feed";
    public static final String EVENTS = "plan_referral_events";
    public static final String DELETED = "plan_referral_deleted";
    public static final String LATEST = "plan_referral_latest";
    public static final String STOPS = "plan_referral_stops";
    public static final List<String> NAMES = List.of(MEMBERS, ACTIVITY, COVERAGE, FEED, EVENTS, DELETED, LATEST, STOPS);
    private ReferralTables() { }
    /** Plan logs some transaction failures and completes its Future normally; never treat that as commit. */
    public static java.util.concurrent.CompletableFuture<Void> commit(com.djrapitops.plan.storage.database.Database database, Transaction transaction) {
        return database.executeTransaction(transaction).thenRun(() -> {
            if (!transaction.wasSuccessful()) throw new IllegalStateException("Referral transaction did not commit");
        });
    }
    public static List<String> createStatements() {
        return List.of(
            "CREATE TABLE IF NOT EXISTS " + MEMBERS + " (server_uuid VARCHAR(36) NOT NULL, uuid VARCHAR(36) NOT NULL, first_join BIGINT NOT NULL, PRIMARY KEY(server_uuid,uuid))",
            "CREATE TABLE IF NOT EXISTS " + ACTIVITY + " (server_uuid VARCHAR(36) NOT NULL, uuid VARCHAR(36) NOT NULL, session_start BIGINT NOT NULL, session_end BIGINT NOT NULL, valid INTEGER NOT NULL, intervals MEDIUMTEXT NOT NULL, PRIMARY KEY(server_uuid,uuid,session_start))",
            "CREATE TABLE IF NOT EXISTS " + COVERAGE + " (run_id VARCHAR(36) PRIMARY KEY, server_uuid VARCHAR(36) NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, flushed_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS " + FEED + " (server_uuid VARCHAR(36) PRIMARY KEY, stream_id VARCHAR(32) NOT NULL, cursor_value BIGINT NOT NULL, source_as_of BIGINT NOT NULL, capture_started BIGINT NOT NULL, received_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS " + EVENTS + " (server_uuid VARCHAR(36) NOT NULL, sequence_value BIGINT NOT NULL, record_json MEDIUMTEXT NOT NULL, PRIMARY KEY(server_uuid,sequence_value))",
            "CREATE TABLE IF NOT EXISTS " + DELETED + " (uuid VARCHAR(96) PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS " + LATEST + " (server_uuid VARCHAR(36) NOT NULL, entity_key VARCHAR(100) NOT NULL, newcomer_uuid VARCHAR(36), record_json MEDIUMTEXT NOT NULL, PRIMARY KEY(server_uuid,entity_key), UNIQUE(server_uuid,newcomer_uuid))",
            "CREATE TABLE IF NOT EXISTS " + STOPS + " (server_uuid VARCHAR(36) PRIMARY KEY, process_token VARCHAR(100) NOT NULL, stopped_at BIGINT NOT NULL)"
        );
    }

    public abstract static class Tx extends Transaction {
        @Override protected final void performOperations() {
            try {
                performReferralOperations();
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
        protected abstract void performReferralOperations();
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
        protected boolean deleted(String uuid) { return one("SELECT uuid FROM " + DELETED + " WHERE uuid=?", r -> true, false, uuid); }
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
