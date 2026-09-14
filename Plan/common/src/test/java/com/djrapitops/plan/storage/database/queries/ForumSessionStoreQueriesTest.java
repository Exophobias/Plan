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
package com.djrapitops.plan.storage.database.queries;

import com.djrapitops.plan.delivery.webserver.auth.forum.DatabaseForumSessionStore;
import com.djrapitops.plan.delivery.webserver.auth.forum.ForumIdentity;
import com.djrapitops.plan.delivery.webserver.auth.forum.ForumSessionStore;
import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.queries.objects.WebUserQueries;
import com.djrapitops.plan.storage.database.sql.tables.ForumSessionTable;
import com.djrapitops.plan.storage.database.transactions.commands.RemoveEverythingTransaction;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public interface ForumSessionStoreQueriesTest extends DatabaseTestPreparer {

    private ForumSessionStore.Session forumSession(long expires) {
        ForumIdentity identity = new ForumIdentity("https://forums.example.test", "42", playerUUID,
                "verified-link-revision", System.currentTimeMillis() / 1000, 1209600, 60);
        return new ForumSessionStore.Session(identity, DigestUtils.sha256Hex("client-config"), expires);
    }

    @Test
    default void forumSessionStoresCookieHashWithoutLocalPasswordAccount() throws IOException {
        String browserCookie = "test-browser-cookie-" + UUID.randomUUID();
        String cookieHash = DigestUtils.sha256Hex(browserCookie);
        ForumSessionStore.Session expected = forumSession(System.currentTimeMillis() + 60_000);
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());

        store.save(cookieHash, expected);

        assertEquals(expected, store.find(cookieHash).orElseThrow());
        assertTrue(store.find(browserCookie).isEmpty());
        assertEquals(cookieHash, db().queryOptional("SELECT cookie_hash FROM " + ForumSessionTable.TABLE_NAME
                + " WHERE cookie_hash=?", row -> row.getString("cookie_hash"), cookieHash).orElseThrow());
        assertTrue(db().query(WebUserQueries.fetchAllUsers()).isEmpty(),
                "External sessions must not require or create a local password-bearing web user");
    }

    @Test
    default void forumSessionRestartPreservesIdentityAndOriginalAbsoluteExpiry() throws IOException {
        String cookieHash = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        long originalExpiry = System.currentTimeMillis() + 1209600_000L;
        ForumSessionStore.Session expected = forumSession(originalExpiry);
        new DatabaseForumSessionStore(dbSystem()).save(cookieHash, expected);

        forcePersistenceCheck();
        DatabaseForumSessionStore restarted = new DatabaseForumSessionStore(dbSystem());
        ForumSessionStore.Session actual = restarted.find(cookieHash).orElseThrow();

        assertEquals(expected, actual);
        assertEquals(originalExpiry, actual.expires(), "Restart must not extend the login lifetime");
    }

    @Test
    default void forumSessionRemovalRevokesOnlyTheSelectedCookie() throws IOException {
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());
        String first = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        String second = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        ForumSessionStore.Session session = forumSession(System.currentTimeMillis() + 60_000);
        store.save(first, session);
        store.save(second, session);

        store.remove(first);
        store.remove(first);
        forcePersistenceCheck();
        store = new DatabaseForumSessionStore(dbSystem());

        assertTrue(store.find(first).isEmpty());
        assertEquals(session, store.find(second).orElseThrow());
    }

    @Test
    default void forumSessionSaveRemovesExpiredRowsWithoutRemovingActiveSessions() throws IOException {
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());
        String expired = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        String active = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        store.save(expired, forumSession(System.currentTimeMillis() - 1_000));
        ForumSessionStore.Session session = forumSession(System.currentTimeMillis() + 60_000);

        store.save(active, session);

        assertTrue(store.find(expired).isEmpty());
        assertEquals(session, store.find(active).orElseThrow());
    }

    @Test
    default void forumPauseRemovesAllSavedSessions() throws IOException {
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());
        String first = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        String second = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        ForumSessionStore.Session session = forumSession(System.currentTimeMillis() + 60_000);
        store.save(first, session);
        store.save(second, session);

        store.removeAll();
        forcePersistenceCheck();

        DatabaseForumSessionStore restarted = new DatabaseForumSessionStore(dbSystem());
        assertTrue(restarted.find(first).isEmpty());
        assertTrue(restarted.find(second).isEmpty());
        restarted.removeAll();
    }

    @Test
    default void removeEverythingRevokesForumSessions() throws IOException {
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());
        String cookieHash = DigestUtils.sha256Hex(UUID.randomUUID().toString());
        store.save(cookieHash, forumSession(System.currentTimeMillis() + 60_000));

        executeTransactions(new RemoveEverythingTransaction());

        assertTrue(store.find(cookieHash).isEmpty());
    }
}
