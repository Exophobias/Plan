package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.storage.database.DBSystem;
import com.djrapitops.plan.storage.database.sql.tables.ForumSessionTable;
import com.djrapitops.plan.storage.database.queries.objects.UserIdentifierQueries;
import com.djrapitops.plan.storage.database.queries.objects.WebUserQueries;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public final class DatabaseForumSessionStore implements ForumSessionStore {
    private final DBSystem databases;
    private final Cache<UUID, String> playerNames = Caffeine.newBuilder().maximumSize(4096)
            .expireAfterWrite(java.time.Duration.ofMinutes(5)).build();

    @Inject
    public DatabaseForumSessionStore(DBSystem databases) {
        this.databases = databases;
    }

    @Override
    public Optional<Session> find(String cookieHash) throws IOException {
        try {
            return databases.getDatabase().queryOptional("SELECT * FROM " + ForumSessionTable.TABLE_NAME + " WHERE cookie_hash=?",
                    row -> new Session(new ForumIdentity(row.getString("issuer"), row.getString("forum_subject"),
                            UUID.fromString(row.getString("mc_uuid")), row.getString("link_revision"),
                            row.getLong("auth_time"), row.getInt("session_seconds"), row.getInt("check_seconds")),
                            row.getString("config_hash"), row.getLong("expires")), cookieHash);
        } catch (RuntimeException failure) {
            throw new IOException("Forum session storage unavailable");
        }
    }

    @Override
    public void save(String cookieHash, Session session) throws IOException {
        ForumIdentity identity = session.identity();
        try {
            await(databases.getDatabase().executeInTransaction("DELETE FROM " + ForumSessionTable.TABLE_NAME + " WHERE expires<=?", System.currentTimeMillis()));
            await(databases.getDatabase().executeInTransaction("INSERT INTO " + ForumSessionTable.TABLE_NAME
                            + " (cookie_hash,config_hash,issuer,forum_subject,mc_uuid,link_revision,auth_time,expires,session_seconds,check_seconds) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    cookieHash, session.configHash(), identity.issuer(), identity.subject(), identity.minecraftUUID().toString(),
                    identity.revision(), identity.authTime(), session.expires(), identity.expiresIn(), identity.checkAfter()));
        } catch (RuntimeException failure) {
            throw new IOException("Forum session storage unavailable");
        }
    }

    @Override
    public void remove(String cookieHash) throws IOException {
        try {
            await(databases.getDatabase().executeInTransaction("DELETE FROM " + ForumSessionTable.TABLE_NAME + " WHERE cookie_hash=?", cookieHash));
        } catch (RuntimeException failure) {
            throw new IOException("Forum session storage unavailable");
        }
    }

    @Override
    public void removeAll() throws IOException {
        try {
            await(databases.getDatabase().executeInTransaction("DELETE FROM " + ForumSessionTable.TABLE_NAME));
        } catch (RuntimeException failure) {
            throw new IOException("Forum session storage unavailable");
        }
    }

    private static void await(CompletableFuture<?> operation) throws IOException {
        try {
            operation.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Forum session storage interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("Forum session storage unavailable");
        }
    }

    @Override
    public String playerName(UUID uuid) throws IOException {
        try {
            return playerNames.get(uuid, key -> databases.getDatabase().query(UserIdentifierQueries.fetchPlayerNameOf(key))
                    .orElse(key.toString()));
        } catch (RuntimeException failure) {
            throw new IOException("Forum session storage unavailable");
        }
    }

    @Override
    public Optional<ForumPermissions> linkedPermissions(UUID uuid) throws IOException {
        try {
            // Permission changes, account deletion and unlinking take effect on the next request.
            return databases.getDatabase().query(WebUserQueries.fetchLinkedForumPermissions(uuid));
        } catch (RuntimeException failure) {
            throw new IOException("Linked Plan permissions unavailable");
        }
    }
}
