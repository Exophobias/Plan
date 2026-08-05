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
package com.djrapitops.plan.storage.database.sql.tables.extension;

import com.djrapitops.plan.storage.database.DBType;
import com.djrapitops.plan.storage.database.sql.building.CreateTableBuilder;
import com.djrapitops.plan.storage.database.sql.building.Sql;

/**
 * Table information about 'plan_extension_server_value_history'.
 * <p>
 * Kept values for server-level providers that opted in with {@code graphed = true}, so that a tab can draw a
 * series rather than a single figure.
 * <p>
 * Separate from {@link ExtensionServerValueTable} rather than a column on it, because the two have opposite
 * lifetimes. That table holds exactly one row per provider and is overwritten on every gather; this one is
 * append-only and is read as an ordered series. Putting a timestamp on the existing table would have meant
 * either losing the history on the next overwrite or changing what a row there means for every extension,
 * including the ones that never asked for a graph.
 * <p>
 * Both value columns are nullable and exactly one is set per row: numbers land in {@code long_value}, doubles
 * and percentages in {@code double_value}. This mirrors how {@link ExtensionServerValueTable} already keeps
 * differently-typed values side by side, so the reading code stays the same shape.
 *
 * @author AuroraLS3
 */
public class ExtensionServerValueHistoryTable {

    public static final String TABLE_NAME = "plan_extension_server_value_history";

    public static final String ID = "id";
    public static final String PROVIDER_ID = "provider_id";
    public static final String DOUBLE_VALUE = "double_value";
    public static final String LONG_VALUE = "long_value";
    public static final String TIMESTAMP = "timestamp";

    private ExtensionServerValueHistoryTable() {
        /* Static information class */
    }

    public static String createTableSQL(DBType dbType) {
        return CreateTableBuilder.create(TABLE_NAME, dbType)
                .column(ID, Sql.INT).primaryKey()
                .column(DOUBLE_VALUE, Sql.DOUBLE)
                .column(LONG_VALUE, Sql.LONG)
                .column(TIMESTAMP, Sql.LONG).notNull()
                .column(PROVIDER_ID, Sql.INT).notNull()
                .foreignKey(PROVIDER_ID, ExtensionProviderTable.TABLE_NAME, ExtensionProviderTable.ID)
                .build();
    }
}
