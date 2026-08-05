/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.storage.database.transactions.init;

import com.djrapitops.plan.delivery.domain.DateObj;
import com.djrapitops.plan.exceptions.database.DBOpException;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.queries.objects.TPSQueries;
import com.djrapitops.plan.storage.database.sql.tables.PingTable;
import com.djrapitops.plan.storage.database.sql.tables.ServerTable;
import com.djrapitops.plan.storage.database.sql.tables.TPSTable;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionPluginTable;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionProviderTable;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionServerValueHistoryTable;
import com.djrapitops.plan.storage.database.transactions.ExecStatement;
import com.djrapitops.plan.storage.database.transactions.Executable;
import com.djrapitops.plan.storage.database.transactions.ThrowawayTransaction;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

import static com.djrapitops.plan.storage.database.sql.building.Sql.*;

/**
 * Transaction for cleaning up old data from the database.
 *
 * @author AuroraLS3
 */
public class RemoveOldSampledDataTransaction extends ThrowawayTransaction {

    private final ServerUUID serverUUID;
    private final long deleteTPSOlderThanMs;
    private final long deletePingOlderThanMs;

    public RemoveOldSampledDataTransaction(
            ServerUUID serverUUID,
            long deleteTPSOlderThanMs,
            long deletePingOlderThanMs
    ) {
        this.serverUUID = serverUUID;
        this.deleteTPSOlderThanMs = deleteTPSOlderThanMs;
        this.deletePingOlderThanMs = deletePingOlderThanMs;
    }

    @Override
    protected void performOperations() {
        Optional<Integer> allTimePeak = query(TPSQueries.fetchAllTimePeakPlayerCount(serverUUID)).map(DateObj::getValue);

        try {
            execute(cleanTPSTable(allTimePeak.orElse(-1)));
            execute(cleanPingTable());
            execute(cleanExtensionValueHistoryTable());
        } catch (DBOpException e) {
            if (e.isModifiedSinceLastReadViolation()) {
                return;
            }
            throw e;
        }
    }

    /**
     * Drops graph points older than the TPS retention window.
     *
     * <p>Reuses that setting rather than adding one of its own: both are sampled series kept only so a page can
     * draw them, they are gathered on comparable schedules, and an operator who has decided how far back the
     * server's own graphs should reach has already answered this question. A second setting would let the two
     * disagree, and nothing good comes of a plugin graph that outlives the TPS graph beside it.
     *
     * <p>Scoped to this server, like the TPS and ping cleans above it. Several servers can share one database,
     * each running this transaction on its own schedule with its own retention setting, so an unscoped delete
     * would let whichever node cleaned up most aggressively destroy every other node's history. A point has no
     * server column of its own, so the scope comes through its provider's plugin.
     */
    private Executable cleanExtensionValueHistoryTable() {
        String selectProviderIdsOfServer = SELECT + "p." + ExtensionProviderTable.ID +
                FROM + ExtensionProviderTable.TABLE_NAME + " p" +
                INNER_JOIN + ExtensionPluginTable.TABLE_NAME + " pl on pl." + ExtensionPluginTable.ID + "=p." + ExtensionProviderTable.PLUGIN_ID +
                WHERE + ExtensionPluginTable.SERVER_UUID + "=?";

        String sql = DELETE_FROM + ExtensionServerValueHistoryTable.TABLE_NAME +
                WHERE + ExtensionServerValueHistoryTable.TIMESTAMP + "<?" +
                AND + ExtensionServerValueHistoryTable.PROVIDER_ID + " IN (" + selectProviderIdsOfServer + ')';

        return new ExecStatement(sql) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setLong(1, System.currentTimeMillis() - deleteTPSOlderThanMs);
                statement.setString(2, serverUUID.toString());
            }
        };
    }

    private Executable cleanTPSTable(int allTimePlayerPeak) {
        String sql = DELETE_FROM + TPSTable.TABLE_NAME +
                WHERE + TPSTable.DATE + "<?" +
                AND + TPSTable.PLAYERS_ONLINE + "!=?" +
                AND + TPSTable.SERVER_ID + '=' + ServerTable.SELECT_SERVER_ID;

        return new ExecStatement(sql) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setLong(1, System.currentTimeMillis() - deleteTPSOlderThanMs);
                statement.setInt(2, allTimePlayerPeak);
                statement.setString(3, serverUUID.toString());
            }
        };
    }

    private Executable cleanPingTable() {
        String sql = DELETE_FROM + PingTable.TABLE_NAME +
                WHERE + '(' + PingTable.DATE + "<?" +
                AND + PingTable.SERVER_ID + "=" + ServerTable.SELECT_SERVER_ID + ")" +
                OR + PingTable.MIN_PING + "<0";

        return new ExecStatement(sql) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setLong(1, System.currentTimeMillis() - deletePingOlderThanMs);
                statement.setString(2, serverUUID.toString());
            }
        };
    }
}