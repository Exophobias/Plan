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
package com.djrapitops.plan.extension.implementation.storage.transactions.results;

import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionProviderTable;
import com.djrapitops.plan.storage.database.transactions.ExecStatement;
import com.djrapitops.plan.storage.database.transactions.Executable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import static com.djrapitops.plan.storage.database.sql.building.Sql.INSERT_INTO;
import static com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionServerValueHistoryTable.*;

/**
 * Appends a point to a graphed provider's series.
 * <p>
 * Its own class rather than a method on each store transaction, because both transactions need the identical
 * insert and the alternative is the same twenty lines copied into each. It also keeps the change to those
 * transactions down to two lines apiece, which matters on a fork that merges upstream by hand.
 * <p>
 * Two transactions, three annotations: percentage providers route through the double transaction rather than
 * having one of their own.
 *
 * @author AuroraLS3
 */
public final class ExtensionValueHistory {

    private ExtensionValueHistory() {
        /* Static utility class */
    }

    /**
     * An insert of one point.
     * <p>
     * Exactly one of the two values is expected to be set; the other is stored null. Which one it is says how
     * the value was provided, and the reader picks whichever is present rather than being told in advance.
     *
     * @param pluginName   name of the plugin the provider belongs to
     * @param providerName name of the provider
     * @param serverUUID   the server the value was gathered on
     * @param timestamp    epoch milliseconds the value was gathered
     * @param longValue    value of a number provider, or null
     * @param doubleValue  value of a double or percentage provider, or null
     * @return the insert, to be given to {@code execute}
     */
    public static Executable append(String pluginName, String providerName, ServerUUID serverUUID,
                                    long timestamp, Long longValue, Double doubleValue) {
        String sql = INSERT_INTO + TABLE_NAME + "(" +
                LONG_VALUE + "," +
                DOUBLE_VALUE + "," +
                TIMESTAMP + "," +
                PROVIDER_ID +
                ") VALUES (?,?,?," + ExtensionProviderTable.STATEMENT_SELECT_PROVIDER_ID + ")";

        return new ExecStatement(sql) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                if (longValue == null) {
                    statement.setNull(1, Types.BIGINT);
                } else {
                    statement.setLong(1, longValue);
                }
                if (doubleValue == null) {
                    statement.setNull(2, Types.DOUBLE);
                } else {
                    statement.setDouble(2, doubleValue);
                }
                statement.setLong(3, timestamp);
                ExtensionProviderTable.set3PluginValuesToStatement(statement, 4, providerName, pluginName, serverUUID);
            }
        };
    }
}
