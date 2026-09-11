package com.djrapitops.plan.storage.database.sql.tables;

/** Independent external sessions: no password or reusable browser cookie is stored. */
public final class ForumSessionTable {
    public static final String TABLE_NAME = "plan_forum_sessions";

    private ForumSessionTable() {}

    public static String createTableSQL() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + "cookie_hash VARCHAR(64) PRIMARY KEY,config_hash VARCHAR(64) NOT NULL,"
                + "issuer VARCHAR(512) NOT NULL,forum_subject VARCHAR(20) NOT NULL,mc_uuid VARCHAR(36) NOT NULL,"
                + "link_revision VARCHAR(128) NOT NULL,auth_time BIGINT NOT NULL,expires BIGINT NOT NULL,"
                + "session_seconds INTEGER NOT NULL,check_seconds INTEGER NOT NULL)";
    }
}
