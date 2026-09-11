/*
 *  This file is part of Player Analytics (Plan).
 */
package com.djrapitops.plan.storage.database.sql.tables.webuser;

import com.djrapitops.plan.delivery.web.resolver.request.WebUser;
import com.djrapitops.plan.storage.database.DBType;
import com.djrapitops.plan.storage.database.queries.Query;
import com.djrapitops.plan.storage.database.sql.building.CreateTableBuilder;
import com.djrapitops.plan.storage.database.sql.building.Select;
import com.djrapitops.plan.storage.database.sql.building.Sql;
import org.apache.commons.codec.digest.DigestUtils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** External preferences have an immutable authentication namespace, independent of plan_security. */
public final class ExternalPreferencesTable {

    public static final String TABLE_NAME = "plan_external_preferences";
    public static final String AUTH_KEY = "auth_key";
    public static final String PROVIDER = "auth_provider";
    public static final String SUBJECT = "auth_subject";
    public static final String PREFERENCES = "preferences";
    public static final String INSERT_COLUMNS = TABLE_NAME + " (" + AUTH_KEY + ',' + PROVIDER + ',' + SUBJECT + ',' + PREFERENCES + ") VALUES (?,?,?,?)";
    public static final String INSERT = "INSERT INTO " + INSERT_COLUMNS;
    public static final String SELECT = "SELECT " + PREFERENCES + " FROM " + TABLE_NAME + " WHERE " + AUTH_KEY + "=?";
    public static final String DELETE = "DELETE FROM " + TABLE_NAME + " WHERE " + AUTH_KEY + "=?";

    private ExternalPreferencesTable() { }

    public static String key(WebUser user) {
        return DigestUtils.sha256Hex(user.getAuthenticationProvider().orElseThrow(IllegalArgumentException::new)
                + '\n' + user.getAuthenticationSubject().orElseThrow(IllegalArgumentException::new));
    }

    public static String createTableSQL(DBType type) {
        return CreateTableBuilder.create(TABLE_NAME, type)
                .column("id", Sql.INT).primaryKey()
                .column(AUTH_KEY, "varchar(64)").notNull().unique()
                .column(PROVIDER, "varchar(32)").notNull()
                .column(SUBJECT, "varchar(256)").notNull()
                .column(PREFERENCES, "TEXT").notNull().toString();
    }

    public static Query<List<Row>> fetchRows(int afterId, int limit) {
        return db -> db.queryList(Select.all(TABLE_NAME).where("id>" + afterId).orderBy("id").limit(limit).toString(), Row::extract);
    }

    public record Row(int id, String key, String provider, String subject, String preferences) {
        public static Row extract(ResultSet result) throws SQLException {
            return new Row(result.getInt("id"), result.getString(AUTH_KEY), result.getString(PROVIDER),
                    result.getString(SUBJECT), result.getString(PREFERENCES));
        }

        public void insert(PreparedStatement statement) throws SQLException {
            statement.setString(1, key);
            statement.setString(2, provider);
            statement.setString(3, subject);
            statement.setString(4, preferences);
        }
    }
}
