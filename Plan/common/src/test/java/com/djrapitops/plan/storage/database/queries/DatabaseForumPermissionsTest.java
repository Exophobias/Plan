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

import com.djrapitops.plan.delivery.domain.auth.User;
import com.djrapitops.plan.delivery.webserver.auth.forum.DatabaseForumSessionStore;
import com.djrapitops.plan.delivery.webserver.auth.forum.ForumPermissions;
import com.djrapitops.plan.storage.database.DBType;
import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.queries.objects.WebUserQueries;
import com.djrapitops.plan.storage.database.sql.tables.UsersTable;
import com.djrapitops.plan.storage.database.sql.tables.webuser.SecurityTable;
import com.djrapitops.plan.storage.database.transactions.Transaction;
import com.djrapitops.plan.storage.database.transactions.commands.RemoveWebUserTransaction;
import com.djrapitops.plan.storage.database.transactions.commands.StoreWebUserTransaction;
import com.djrapitops.plan.storage.database.transactions.webuser.StoreWebGroupTransaction;
import com.djrapitops.plan.utilities.PassEncryptUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the permission association against each database implementation in the aggregate. */
public interface DatabaseForumPermissionsTest extends DatabaseTestPreparer {
    String FORUM_LOCAL_OWNER = "forum-mapped-owner";
    String FORUM_LOCAL_GROUP = "forum-mapped-group";
    List<String> FORUM_LOCAL_GRANTS = List.of("page", "data", "access", "manage.groups", "manage.users", "manage.themes");

    private User storeForumLinkedAccount(String username, UUID uuid, String group, List<String> grants) {
        User user = new User(username, "display-name-is-not-proof", uuid,
                PassEncryptUtil.createHash("synthetic-test-password"), group, grants);
        executeTransactions(new StoreWebUserTransaction(user));
        return user;
    }

    @Test
    default void forumPermissionsUniqueUuidInheritsExactGrantsWithoutPlayerNameRow() throws IOException {
        User local = storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        assertTrue(db().queryOptional("SELECT " + UsersTable.USER_UUID + " FROM " + UsersTable.TABLE_NAME
                + " WHERE " + UsersTable.USER_UUID + "=?", row -> row.getString(1), playerUUID).isEmpty());

        ForumPermissions permissions = new DatabaseForumSessionStore(dbSystem()).linkedPermissions(playerUUID).orElseThrow();

        assertEquals(FORUM_LOCAL_GROUP, permissions.group());
        assertEquals(Set.copyOf(FORUM_LOCAL_GRANTS), Set.copyOf(permissions.permissions()));
        assertThrows(UnsupportedOperationException.class, () -> permissions.permissions().add("extra.grant"));
        User unchanged = db().query(WebUserQueries.fetchUser(FORUM_LOCAL_OWNER)).orElseThrow();
        assertEquals(local.getPasswordHash(), unchanged.getPasswordHash());
        assertTrue(unchanged.doesPasswordMatch("synthetic-test-password"));
    }

    @Test
    default void forumPermissionsNeverMatchUsernameInsteadOfVerifiedUuid() throws IOException {
        storeForumLinkedAccount(player2UUID.toString(), playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());

        assertTrue(store.linkedPermissions(player2UUID).isEmpty(), "A matching textual username is not a UUID association");
        assertTrue(store.linkedPermissions(playerUUID).isPresent());
        assertTrue(store.linkedPermissions(player3UUID).isEmpty());
    }

    @Test
    default void forumPermissionsDuplicateUuidCannotChooseOrCombineAccounts() {
        storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        storeForumLinkedAccount("second-linked-account", playerUUID, "empty-linked-group", List.of());

        assertThrows(IOException.class, () -> new DatabaseForumSessionStore(dbSystem()).linkedPermissions(playerUUID));
    }

    @Test
    default void forumPermissionsEmptyExistingGroupRemainsEmpty() throws IOException {
        storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, List.of());

        ForumPermissions permissions = new DatabaseForumSessionStore(dbSystem()).linkedPermissions(playerUUID).orElseThrow();

        assertEquals(FORUM_LOCAL_GROUP, permissions.group());
        assertTrue(permissions.permissions().isEmpty(), "An empty mapped group must not acquire forum self grants");
    }

    @Test
    default void forumPermissionsDanglingGroupIsDeniedInsteadOfUnmapped() {
        storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        makeForumGroupDangling();

        assertThrows(IOException.class, () -> new DatabaseForumSessionStore(dbSystem()).linkedPermissions(playerUUID));
    }

    @Test
    default void forumPermissionsDuplicateAccountWithDanglingGroupStillDenied() {
        storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        makeForumGroupDangling();
        storeForumLinkedAccount("valid-second-account", playerUUID, "another-group", List.of("access.player.self"));

        assertThrows(IOException.class, () -> new DatabaseForumSessionStore(dbSystem()).linkedPermissions(playerUUID));
    }

    @Test
    default void forumPermissionsObserveGroupEditsAndReassignmentOnNextLookup() throws IOException {
        User local = storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());
        assertEquals(Set.copyOf(FORUM_LOCAL_GRANTS), Set.copyOf(store.linkedPermissions(playerUUID).orElseThrow().permissions()));

        executeTransactions(new StoreWebGroupTransaction(FORUM_LOCAL_GROUP, List.of("page.player.overview")));
        assertEquals(List.of("page.player.overview"), store.linkedPermissions(playerUUID).orElseThrow().permissions());

        executeTransactions(new StoreWebUserTransaction(new User(FORUM_LOCAL_OWNER, "ignored", playerUUID,
                local.getPasswordHash(), "no-grants", List.of())));
        ForumPermissions reassigned = store.linkedPermissions(playerUUID).orElseThrow();
        assertEquals("no-grants", reassigned.group());
        assertTrue(reassigned.permissions().isEmpty());
    }

    @Test
    default void forumPermissionsObserveUuidUnlinkAndAccountRemovalOnNextLookup() throws Exception {
        storeForumLinkedAccount(FORUM_LOCAL_OWNER, playerUUID, FORUM_LOCAL_GROUP, FORUM_LOCAL_GRANTS);
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(dbSystem());
        assertTrue(store.linkedPermissions(playerUUID).isPresent());

        db().executeInTransaction("UPDATE " + SecurityTable.TABLE_NAME + " SET " + SecurityTable.LINKED_TO
                + "=? WHERE " + SecurityTable.USERNAME + "=?", player2UUID.toString(), FORUM_LOCAL_OWNER).get();
        assertTrue(store.linkedPermissions(playerUUID).isEmpty());
        assertTrue(store.linkedPermissions(player2UUID).isPresent());

        executeTransactions(new RemoveWebUserTransaction(FORUM_LOCAL_OWNER));
        assertTrue(store.linkedPermissions(player2UUID).isEmpty());
    }

    /** Model an imported/corrupt association without changing any production validation. */
    private void makeForumGroupDangling() {
        executeTransactions(new Transaction() {
            @Override protected void performOperations() {
                if (dbType == DBType.MYSQL) execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    execute("UPDATE " + SecurityTable.TABLE_NAME + " SET " + SecurityTable.GROUP_ID
                            + "=2147483647 WHERE " + SecurityTable.USERNAME + "='" + FORUM_LOCAL_OWNER + "'");
                } finally {
                    if (dbType == DBType.MYSQL) execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
        });
    }
}
