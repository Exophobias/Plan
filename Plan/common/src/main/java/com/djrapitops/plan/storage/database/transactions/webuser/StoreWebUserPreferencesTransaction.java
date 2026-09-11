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
package com.djrapitops.plan.storage.database.transactions.webuser;

import com.djrapitops.plan.delivery.web.resolver.request.WebUser;
import com.djrapitops.plan.storage.database.sql.tables.webuser.WebUserPreferencesTable;
import com.djrapitops.plan.storage.database.sql.tables.webuser.ExternalPreferencesTable;
import com.djrapitops.plan.storage.database.transactions.ExecStatement;
import com.djrapitops.plan.storage.database.transactions.Transaction;
import com.djrapitops.plan.utilities.dev.Untrusted;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Stores user preferences as text in database.
 *
 * @author AuroraLS3
 */
public class StoreWebUserPreferencesTransaction extends Transaction {

    @Untrusted
    private final String preferences;
    @Untrusted
    private final WebUser user;

    public StoreWebUserPreferencesTransaction(String preferences, WebUser user) {
        this.preferences = preferences;
        this.user = user;
    }

    @Override
    protected void performOperations() {
        if (user.getAuthenticationProvider().isPresent()) {
            storeExternal();
            return;
        }
        execute(new ExecStatement(WebUserPreferencesTable.DELETE_BY_WEB_USERNAME) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, user.getUsername());
            }
        });
        commitMidTransaction();
        execute(new ExecStatement(WebUserPreferencesTable.INSERT_STATEMENT) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, preferences);
                statement.setString(2, user.getUsername());
            }
        });
    }

    private void storeExternal() {
        String key = ExternalPreferencesTable.key(user);
        execute(new ExecStatement(ExternalPreferencesTable.DELETE) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, key);
            }
        });
        // Keep delete and insert in one transaction so a failed update preserves prior preferences.
        execute(new ExecStatement(ExternalPreferencesTable.INSERT) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, key);
                statement.setString(2, user.getAuthenticationProvider().orElseThrow());
                statement.setString(3, user.getAuthenticationSubject().orElseThrow());
                statement.setString(4, preferences);
            }
        });
    }
}
