package com.djrapitops.plan.storage.database.queries.objects;

import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.queries.Query;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One aggregate excludes the memory-owned sessions even if their storage has already committed. */
public final class ConfirmedActivityQuery {
    public static Query<Long> completed(UUID player, ServerUUID server, long before, Set<Long> excludedStarts) {
        List<Long> excluded = excludedStarts.stream().sorted().toList();
        String sql = "SELECT COALESCE(SUM(CASE WHEN s.afk_time >= 0 AND s.session_end >= s.session_start "
                + "AND s.afk_time <= s.session_end-s.session_start THEN s.session_end-s.session_start-s.afk_time "
                + "ELSE 0 END),0) AS active_time, COALESCE(SUM(CASE WHEN s.afk_time < 0 "
                + "OR s.session_end < s.session_start OR s.afk_time > s.session_end-s.session_start "
                + "THEN 1 ELSE 0 END),0) AS invalid_sessions FROM plan_sessions s "
                + "JOIN plan_users u ON u.id=s.user_id JOIN plan_servers v ON v.id=s.server_id "
                + "WHERE u.uuid=? AND v.uuid=? AND s.session_end<=?"
                + (excluded.isEmpty() ? "" : " AND s.session_start NOT IN ("
                + String.join(",", java.util.Collections.nCopies(excluded.size(), "?")) + ")");
        return db -> db.query(new QueryStatement<Long>(sql) {
            @Override public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, player.toString());
                statement.setString(2, server.toString());
                statement.setLong(3, before);
                for (int i = 0; i < excluded.size(); i++) statement.setLong(i + 4, excluded.get(i));
            }
            @Override public Long processResults(ResultSet result) throws SQLException {
                if (!result.next()) return 0L;
                if (result.getLong("invalid_sessions") != 0) throw new SQLException("Invalid recorded activity");
                return result.getLong("active_time");
            }
        });
    }
    private ConfirmedActivityQuery() { }
}
