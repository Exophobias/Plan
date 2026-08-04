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
package com.djrapitops.plan.storage.database.transactions.patches;

import com.djrapitops.plan.storage.database.DBType;
import com.djrapitops.plan.storage.database.sql.building.Sql;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionTableProviderTable;

/**
 * Increases the length of extension table format columns so that TIME_MILLISECONDS fits.
 * <p>
 * The columns store TableColumnFormat#name() in 15 characters, which is 2 too few for
 * TIME_MILLISECONDS, so storing a table using that format failed on MySQL.
 */
public class ExtensionTableProviderFormatterLengthPatch extends Patch {

    private final String tableName;

    public ExtensionTableProviderFormatterLengthPatch() {
        tableName = ExtensionTableProviderTable.TABLE_NAME;
    }

    @Override
    public boolean hasBeenApplied() {
        return dbType == DBType.SQLITE || // SQLite does not limit varchar lengths
                columnVarcharLength(tableName, ExtensionTableProviderTable.FORMAT_1) >= ExtensionTableProviderTable.FORMAT_LENGTH;
    }

    @Override
    protected void applyPatch() {
        increaseLength(ExtensionTableProviderTable.FORMAT_1);
        increaseLength(ExtensionTableProviderTable.FORMAT_2);
        increaseLength(ExtensionTableProviderTable.FORMAT_3);
        increaseLength(ExtensionTableProviderTable.FORMAT_4);
        increaseLength(ExtensionTableProviderTable.FORMAT_5);
    }

    private void increaseLength(String column) {
        execute("ALTER TABLE " + tableName + " MODIFY " + column + " " + Sql.varchar(ExtensionTableProviderTable.FORMAT_LENGTH));
    }
}
