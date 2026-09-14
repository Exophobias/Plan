package com.djrapitops.plan.community;

import com.djrapitops.plan.storage.database.Database;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import com.djrapitops.plan.storage.database.transactions.ExecStatement;
import com.djrapitops.plan.storage.database.transactions.Transaction;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Durable first-observed identity and activity survive routine inactive-player cleanup. */
public final class CommunityTables {
    public static final String MEMBERS = "plan_community_members";
    public static final String ACTIVITY = "plan_community_activity";
    public static final String COVERAGE = "plan_community_coverage";
    public static final String DELETED = "plan_community_deleted";
    public static final String STOPS = "plan_community_stops";
    public static final List<String> NAMES = List.of(MEMBERS, ACTIVITY, COVERAGE, DELETED, STOPS);
    private CommunityTables() { }

    public static List<String> createStatements() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + MEMBERS + " (server_uuid VARCHAR(36) NOT NULL, uuid VARCHAR(36) NOT NULL, first_seen BIGINT NOT NULL, PRIMARY KEY(server_uuid,uuid))",
                "CREATE TABLE IF NOT EXISTS " + ACTIVITY + " (server_uuid VARCHAR(36) NOT NULL, uuid VARCHAR(36) NOT NULL, session_start BIGINT NOT NULL, session_end BIGINT NOT NULL, valid INTEGER NOT NULL, intervals MEDIUMTEXT NOT NULL, PRIMARY KEY(server_uuid,uuid,session_start))",
                "CREATE TABLE IF NOT EXISTS " + COVERAGE + " (run_id VARCHAR(36) PRIMARY KEY, server_uuid VARCHAR(36) NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, flushed_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + DELETED + " (uuid VARCHAR(36) PRIMARY KEY)",
                "CREATE TABLE IF NOT EXISTS " + STOPS + " (server_uuid VARCHAR(36) PRIMARY KEY, process_token VARCHAR(100) NOT NULL, stopped_at BIGINT NOT NULL)"
        );
    }

    public static CompletableFuture<Void> commit(Database database, Transaction transaction) {
        return database.executeTransaction(transaction).thenRun(() -> {
            if (!transaction.wasSuccessful()) throw new IllegalStateException("Community activity transaction did not commit");
        });
    }

    public abstract static class Tx extends Transaction {
        @Override protected final void performOperations() {
            try { performCommunityOperations(); }
            catch (RuntimeException failure) {
                execute(connection -> {
                    try { connection.rollback(); }
                    catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                    return true;
                });
                throw failure;
            }
        }
        protected abstract void performCommunityOperations();
        public <T> List<T> rows(String sql, Extractor<T> extractor, Object... args) {
            return query(new QueryStatement<List<T>>(sql) {
                @Override public void prepare(PreparedStatement statement) throws SQLException { bind(statement, args); }
                @Override public List<T> processResults(ResultSet results) throws SQLException {
                    List<T> found = new ArrayList<>();
                    while (results.next()) found.add(extractor.get(results));
                    return found;
                }
            });
        }
        public <T> Optional<T> optional(String sql, Extractor<T> extractor, Object... args) {
            return Optional.ofNullable(one(sql, extractor, null, args));
        }
        public <T> T one(String sql, Extractor<T> extractor, T absent, Object... args) {
            return query(new QueryStatement<T>(sql) {
                @Override public void prepare(PreparedStatement statement) throws SQLException { bind(statement, args); }
                @Override public T processResults(ResultSet results) throws SQLException {
                    return results.next() ? extractor.get(results) : absent;
                }
            });
        }
        public void sql(String sql, Object... args) { execute(statement(sql, args)); }
        protected boolean deleted(String uuid) { return one("SELECT 1 FROM " + DELETED + " WHERE uuid=?", r -> true, false, uuid); }
    }

    @FunctionalInterface public interface Extractor<T> { T get(ResultSet result) throws SQLException; }
    public static ExecStatement statement(String sql, Object... args) {
        return new ExecStatement(sql) {
            @Override public void prepare(PreparedStatement statement) throws SQLException { bind(statement, args); }
        };
    }
    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }
}
