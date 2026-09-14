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

import com.djrapitops.plan.storage.database.DBType;
import com.djrapitops.plan.storage.database.queries.schema.MySQLSchemaQueries;
import com.djrapitops.plan.storage.database.sql.tables.*;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionPlayerTableValueTable;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionServerTableValueTable;
import com.djrapitops.plan.storage.database.sql.tables.extension.ExtensionServerValueHistoryTable;
import com.djrapitops.plan.storage.database.transactions.Transaction;
import org.apache.commons.text.TextStringBuilder;

/**
 * Transaction that creates the database index if it has not yet been created.
 *
 * @author AuroraLS3
 */
public class CreateIndexTransaction extends Transaction {

    @Override
    protected void performOperations() {
        createIndex(com.djrapitops.plan.community.CommunityTables.ACTIVITY,"plan_community_session_time_index","server_uuid","session_start","session_end");
        createIndex(com.djrapitops.plan.community.CommunityTables.MEMBERS,"plan_community_first_seen_index","server_uuid","first_seen");
        createIndex(com.djrapitops.plan.community.CommunityTables.COVERAGE,"plan_community_coverage_time_index","server_uuid","start_ms","end_ms");
        createIndex(com.djrapitops.plan.store.StoreTables.EVENTS,"plan_store_event_time_index","server_uuid","event_at");
        createIndex(com.djrapitops.plan.store.StoreTables.EVENTS,"plan_store_event_order_index","server_uuid","order_id");
        createIndex(com.djrapitops.plan.store.StoreTables.LATEST,"plan_store_latest_order_index","server_uuid","order_id");
        createIndex(UsersTable.TABLE_NAME, "plan_users_uuid_index", UsersTable.USER_UUID);
        createIndex(ServerTable.TABLE_NAME, "plan_servers_uuid_index", ServerTable.SERVER_UUID);

        // replaced by foreign keys
        dropIndex(UserInfoTable.TABLE_NAME, "plan_user_info_uuid_index");
        // replaced by foreign keys
        dropIndex(SessionsTable.TABLE_NAME, "plan_sessions_uuid_index");

        createIndex(SessionsTable.TABLE_NAME, "plan_sessions_date_index", SessionsTable.SESSION_START);
        // Replaced by foreign keys
        dropIndex(WorldTimesTable.TABLE_NAME, "plan_world_times_uuid_index");

        createIndex(KillsTable.TABLE_NAME, "plan_kills_uuid_index",
                KillsTable.KILLER_UUID,
                KillsTable.VICTIM_UUID,
                KillsTable.SERVER_UUID
        );
        createIndex(KillsTable.TABLE_NAME, "plan_kills_date_index", KillsTable.DATE);
        // Replaced with foreign keys.
        dropIndex(PingTable.TABLE_NAME, "plan_ping_uuid_index");

        createIndex(PingTable.TABLE_NAME, "plan_ping_date_index", PingTable.DATE);
        createIndex(TPSTable.TABLE_NAME, "plan_tps_date_index", TPSTable.DATE);
        createIndex(TPSTable.TABLE_NAME, "plan_tps_server_date_index", TPSTable.SERVER_ID, TPSTable.DATE);

        createIndex(SessionsTable.TABLE_NAME, "plan_session_join_address_index", SessionsTable.JOIN_ADDRESS_ID);

        createIndex(ExtensionPlayerTableValueTable.TABLE_NAME, "plan_extension_player_table_value_player_index",
                ExtensionPlayerTableValueTable.TABLE_ID,
                ExtensionPlayerTableValueTable.USER_UUID);
        createIndex(ExtensionServerTableValueTable.TABLE_NAME, "plan_extension_server_table_value_server_index",
                ExtensionServerTableValueTable.TABLE_ID,
                ExtensionServerTableValueTable.SERVER_UUID);
        // Both things done to graph history read it in this order: the query is
        // ORDER BY provider_id, timestamp and the prune is WHERE timestamp < ? AND provider_id IN (...).
        // The foreign key already indexes provider_id on InnoDB but not on SQLite, and neither covers the
        // timestamp, so without this the sort is a filesort over the whole table on every cache refresh.
        createIndex(ExtensionServerValueHistoryTable.TABLE_NAME, "plan_extension_server_value_history_index",
                ExtensionServerValueHistoryTable.PROVIDER_ID,
                ExtensionServerValueHistoryTable.TIMESTAMP);

        createIndex(UserInfoTable.TABLE_NAME, "plan_user_info_server_user",
                UserInfoTable.SERVER_ID,
                UserInfoTable.USER_ID);
        createIndex(SessionsTable.TABLE_NAME, "plan_sessions_server_time_user",
                SessionsTable.SERVER_ID,
                SessionsTable.SESSION_START,
                SessionsTable.USER_ID);
        createIndex(SessionsTable.TABLE_NAME, "plan_sessions_server_end_user",
                SessionsTable.SERVER_ID,
                SessionsTable.SESSION_END,
                SessionsTable.USER_ID);
        createIndex(SessionsTable.TABLE_NAME, "plan_sessions_user_end",
                SessionsTable.USER_ID, SessionsTable.SESSION_END);
        createIndex(SessionsTable.TABLE_NAME, "plan_session_user_start",
                SessionsTable.USER_ID, SessionsTable.SESSION_START);
        createIndex(WorldTimesTable.TABLE_NAME, "plan_world_times_user_server_world",
                WorldTimesTable.USER_ID, WorldTimesTable.SERVER_ID, WorldTimesTable.WORLD_ID);
    }

    private void createIndex(String tableName, String indexName, String... indexedColumns) {
        if (indexedColumns.length == 0) {
            throw new IllegalArgumentException("Can not create index without columns");
        }

        boolean isMySQL = dbType == DBType.MYSQL;
        if (isMySQL) {
            boolean indexExists = query(MySQLSchemaQueries.doesIndexExist(indexName, tableName));
            if (indexExists) return;
        }

        TextStringBuilder sql = new TextStringBuilder("CREATE INDEX ");
        if (!isMySQL) {
            sql.append("IF NOT EXISTS ");
        }
        sql.append(indexName).append(" ON ").append(tableName);

        sql.append(" (");
        sql.appendWithSeparators(indexedColumns, ",");
        sql.append(')');

        execute(sql.toString());
    }

    private void dropIndex(String tableName, String indexName) {
        boolean isMySQL = dbType == DBType.MYSQL;
        if (isMySQL) {
            boolean indexExists = query(MySQLSchemaQueries.doesIndexExist(indexName, tableName));
            if (!indexExists) return;
            execute("DROP INDEX " + indexName + " ON " + tableName);
        } else {
            execute("DROP INDEX IF EXISTS " + indexName);
        }
    }
}
