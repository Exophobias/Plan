package com.djrapitops.plan.referrals;

import com.djrapitops.plan.storage.database.queries.QueryStatement;
import java.sql.*;
import java.util.*;
import static com.djrapitops.plan.referrals.ReferralAnalyticsService.Event;

/** Explicit player removal, separate from inactive raw-data upkeep. Tombstones prevent feed resurrection. */
public final class ReferralEraseTransaction extends ReferralTables.Tx {
    private final String player;
    public ReferralEraseTransaction(UUID player) { this.player = player.toString(); }
    @Override protected void performOperations() {
        List<Row> rows = query(new QueryStatement<List<Row>>("SELECT server_uuid,sequence_value,record_json FROM " + ReferralTables.EVENTS) {
            @Override public void prepare(PreparedStatement statement) { }
            @Override public List<Row> processResults(ResultSet results) throws SQLException {
                List<Row> rows = new ArrayList<>();
                while (results.next()) rows.add(new Row(results.getString(1), results.getLong(2), ReferralJournal.JSON.fromJson(results.getString(3), Event.class)));
                return rows;
            }
        });
        Set<String> claims = new HashSet<>();
        for (Row row : rows) if (row.event.claim != null && row.event.claim.newcomerUUID.toString().equals(player)) claims.add(row.event.claim.claimId);
        for (Row row : rows) {
            String claim = row.event.claim != null ? row.event.claim.claimId : row.event.award.claimId;
            if (claims.contains(claim)) sql("DELETE FROM " + ReferralTables.EVENTS + " WHERE server_uuid=? AND sequence_value=?", row.server, row.sequence);
            if (claims.contains(claim)) sql("DELETE FROM " + ReferralTables.LATEST + " WHERE server_uuid=? AND entity_key=?", row.server,
                    row.event.claim != null ? "c:" + claim : "a:" + row.event.award.awardId);
        }
        tombstone(player); claims.forEach(claim -> tombstone("claim:" + claim));
        sql("DELETE FROM " + ReferralTables.MEMBERS + " WHERE uuid=?", player);
        sql("DELETE FROM " + ReferralTables.ACTIVITY + " WHERE uuid=?", player);
    }
    private void tombstone(String value) {
        if (!deleted(value)) sql("INSERT INTO " + ReferralTables.DELETED + " (uuid) VALUES (?)", value);
    }
    private record Row(String server, long sequence, Event event) { }
}
