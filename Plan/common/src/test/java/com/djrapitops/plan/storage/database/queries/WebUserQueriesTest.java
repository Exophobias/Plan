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
import com.djrapitops.plan.delivery.domain.auth.WebPermission;
import com.djrapitops.plan.delivery.domain.datatransfer.preferences.Preferences;
import com.djrapitops.plan.delivery.web.resolver.request.WebUser;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.web.resolver.request.URIPath;
import com.djrapitops.plan.delivery.web.resolver.request.URIQuery;
import com.djrapitops.plan.delivery.webserver.auth.forum.ForumIdentity;
import com.djrapitops.plan.delivery.webserver.auth.forum.ForumUser;
import com.djrapitops.plan.delivery.webserver.resolver.json.metadata.PreferencesJSONResolver;
import com.djrapitops.plan.delivery.webserver.resolver.json.metadata.StorePreferencesJSONResolver;
import com.djrapitops.plan.delivery.webserver.auth.ActiveCookieExpiryCleanupTask;
import com.djrapitops.plan.delivery.webserver.auth.ActiveCookieStore;
import com.djrapitops.plan.delivery.webserver.auth.CookieMetadata;
import com.djrapitops.plan.processing.Processing;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.queries.objects.WebUserQueries;
import com.djrapitops.plan.storage.database.sql.tables.webuser.ExternalPreferencesTable;
import com.djrapitops.plan.storage.database.transactions.commands.RemoveEverythingTransaction;
import com.djrapitops.plan.storage.database.transactions.commands.RemoveWebUserTransaction;
import com.djrapitops.plan.storage.database.transactions.commands.StoreWebUserTransaction;
import com.djrapitops.plan.storage.database.transactions.patches.WebGroupDefaultGroupsPatch;
import com.djrapitops.plan.storage.database.transactions.webuser.*;
import com.djrapitops.plan.utilities.PassEncryptUtil;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import utilities.TestConstants;
import utilities.TestErrorLogger;
import utilities.TestPluginLogger;

import java.util.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public interface WebUserQueriesTest extends DatabaseTestPreparer {

    String WEB_USERNAME = TestConstants.PLAYER_ONE_NAME;
    String GROUP_NAME = "test_group";

    @Test
    @DisplayName("Web user with group 'admin' is registered")
    default void userIsRegistered() {
        executeTransactions(new WebGroupDefaultGroupsPatch());
        executeTransactions(new StoreWebGroupTransaction("admin", List.of("page", "access", "manage.groups", "manage.users")));
        User expected = new User(WEB_USERNAME, "console", null, PassEncryptUtil.createHash("testPass"), "admin",
                new HashSet<>(Arrays.asList("page", "access", "manage.groups", "manage.users")));
        db().executeTransaction(new StoreWebUserTransaction(expected));
        forcePersistenceCheck();

        Optional<User> found = db().query(WebUserQueries.fetchUser(WEB_USERNAME));
        assertTrue(found.isPresent());
        assertEquals(expected, found.get());
    }

    @Test
    @DisplayName("WebUserQueries fetchAllUsers finds multiple users")
    default void multipleWebUsersAreFetchedAppropriately() {
        userIsRegistered();
        User secondUser = new User("2nd-user", "console", null, PassEncryptUtil.createHash("testPass"), "admin",
                new HashSet<>(Arrays.asList("page", "access", "manage.groups", "manage.users")));
        db().executeTransaction(new StoreWebUserTransaction(secondUser));

        assertEquals(2, db().query(WebUserQueries.fetchAllUsers()).size());
    }

    @Test
    @DisplayName("RemoveWebUserTransaction deletes user from database")
    default void webUserIsRemoved() {
        userIsRegistered();
        db().executeTransaction(new RemoveWebUserTransaction(WEB_USERNAME));
        assertFalse(db().query(WebUserQueries.fetchUser(WEB_USERNAME)).isPresent());
    }

    @Test
    @DisplayName("RemoveEverythingTransaction deletes user from database")
    default void removeEverythingRemovesWebUser() {
        userIsRegistered();
        db().executeTransaction(new RemoveEverythingTransaction());
        assertTrue(db().query(WebUserQueries.fetchAllUsers()).isEmpty());
    }

    @Test
    @DisplayName("ActiveCookieStore stores cookies it generates in database")
    default void activeCookieStoreSavesCookies() {
        userIsRegistered();
        User user = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new);

        ActiveCookieStore cookieStore = createActiveCookieStore();

        String cookie = cookieStore.generateNewCookie(user, TestConstants.IPV4_ADDRESS);

        Map<String, CookieMetadata> result = db().query(WebUserQueries.fetchActiveCookies());
        Map<String, CookieMetadata> expected = Collections.singletonMap(cookie, new CookieMetadata(user, 0, TestConstants.IPV4_ADDRESS));
        result.entrySet().forEach(entry -> expected.get(entry.getKey()).setExpires(entry.getValue().getExpires()));
        assertEquals(expected, result);
    }

    @Test
    @DisplayName("ActiveCookieStore deletes outdated cookies in database")
    default void activeCookieStoreDeletesCookies() {
        userIsRegistered();
        User user = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new);

        ActiveCookieStore cookieStore = createActiveCookieStore();

        String cookie = cookieStore.generateNewCookie(user, TestConstants.IPV4_ADDRESS);

        cookieStore.removeCookie(cookie);

        assertTrue(db().query(WebUserQueries.fetchActiveCookies()).isEmpty());
    }

    @Test
    @DisplayName("RemoveWebUserTransaction deletes cookies in database")
    default void webUserRemovalDeletesCookies() {
        userIsRegistered();
        User user = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new);

        ActiveCookieStore cookieStore = createActiveCookieStore();

        String cookie = cookieStore.generateNewCookie(user, TestConstants.IPV4_ADDRESS);

        db().executeTransaction(new RemoveWebUserTransaction(WEB_USERNAME));

        assertFalse(cookieStore.findCookie(cookie).isPresent());

        assertTrue(db().query(WebUserQueries.fetchActiveCookies()).isEmpty());
    }

    private ActiveCookieStore createActiveCookieStore() {
        return new ActiveCookieStore(
                Mockito.mock(ActiveCookieExpiryCleanupTask.class),
                Mockito.mock(PlanConfig.class),
                dbSystem(),
                Mockito.mock(Processing.class),
                new TestPluginLogger());
    }

    @Test
    @DisplayName("RemoveEverythingTransaction deletes cookies in database")
    default void removeEverythingRemovesCookies() {
        activeCookieStoreSavesCookies();
        db().executeTransaction(new RemoveEverythingTransaction());
        assertTrue(db().query(WebUserQueries.fetchActiveCookies()).isEmpty());
    }

    @Test
    @DisplayName("Web group is stored in the database")
    default void webGroupIsAdded() {
        db().executeTransaction(new StoreWebGroupTransaction(GROUP_NAME, List.of(WebPermission.ACCESS.getPermission())));

        List<String> result = db().query(WebUserQueries.fetchGroupNames());
        assertTrue(result.contains(GROUP_NAME), () -> GROUP_NAME + " not found from db: " + result);

        List<String> permissionExpected = List.of(WebPermission.ACCESS.getPermission());
        List<String> permissionResult = db().query(WebUserQueries.fetchGroupPermissions(GROUP_NAME));
        assertEquals(permissionExpected, permissionResult);
    }

    @Test
    @DisplayName("Web group's permissions is stored in the database")
    default void webGroupPermissionsAreStored() {
        db().executeTransaction(new StoreWebGroupTransaction(GROUP_NAME, List.of(WebPermission.ACCESS.getPermission())));

        assertTrue(db().query(WebUserQueries.fetchGroupId(GROUP_NAME)).isPresent());

        List<String> permissionExpected = List.of(WebPermission.ACCESS.getPermission());
        List<String> permissionResult = db().query(WebUserQueries.fetchGroupPermissions(GROUP_NAME));
        assertEquals(permissionExpected, permissionResult);
    }

    @Test
    @DisplayName("WebUserQueries fetchGroupNamesWithPermission finds group with specific permission")
    default void webGroupIsFoundByPermission() {
        webGroupPermissionsAreStored();

        List<String> groupNames = db().query(WebUserQueries.fetchGroupNamesWithPermission(WebPermission.ACCESS.getPermission()));
        assertTrue(groupNames.contains(GROUP_NAME));
    }

    @Test
    @DisplayName("Web group's never seen permission 'test.permission' is stored in the database")
    default void customWebPermissionsAreStored() throws Exception {
        String customPermission = "test.permission";
        db().executeTransaction(new StoreWebGroupTransaction(GROUP_NAME, List.of(customPermission))).get();

        List<String> permissionExpected = List.of(customPermission);
        List<String> permissionResult = db().query(WebUserQueries.fetchGroupPermissions(GROUP_NAME));
        assertEquals(permissionExpected, permissionResult);

        assertTrue(db().query(WebUserQueries.fetchPermissionId(customPermission)).isPresent());
        assertFalse(db().query(WebUserQueries.fetchPermissionIds(List.of(customPermission))).isEmpty());
    }

    @Test
    @DisplayName("StoreWebUserTransaction updates user's group")
    default void webUserGroupIsChanged() throws Exception {
        userIsRegistered();
        webGroupIsAdded();

        String passHash = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new).getPasswordHash();
        // Changes web group
        User expected = new User(WEB_USERNAME, "console", null, passHash, GROUP_NAME, Set.of("access"));
        db().executeTransaction(new StoreWebUserTransaction(expected)).get();

        User stored = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new);

        assertEquals(expected, stored);
    }

    @Test
    @DisplayName("GrantWebPermissionToGroupsWithPermissionTransaction gives permissions to existing groups")
    default void grantNewWebPermissions() throws Exception {
        db().executeTransaction(new WebGroupDefaultGroupsPatch());
        db().executeTransaction(new StoreMissingWebPermissionsTransaction(List.of("grant.permission"))).get();
        db().executeTransaction(new GrantWebPermissionToGroupsWithPermissionTransaction("grant.permission", WebPermission.MANAGE_GROUPS.getPermission())).get();

        assertTrue(db().query(WebUserQueries.fetchPermissionId("grant.permission")).isPresent());

        List<String> expected = List.of("admin");
        List<String> groups = db().query(WebUserQueries.fetchGroupNamesWithPermission("grant.permission"));
        assertEquals(expected, groups);
    }

    @Test
    @DisplayName("DeleteWebGroupTransaction deletes a group")
    default void webGroupIsDeleted() {
        db().executeTransaction(new WebGroupDefaultGroupsPatch());
        webGroupIsAdded();

        db().executeTransaction(new DeleteWebGroupTransaction(GROUP_NAME, "no_access"));

        assertTrue(db().query(WebUserQueries.fetchGroupId(GROUP_NAME)).isEmpty());
    }

    @Test
    @DisplayName("DeleteWebGroupTransaction does not delete a group if moveTo group doesn't exist")
    default void webGroupIsNotDeletedWhenMoveToGroupDoesNotExist() {
        try {
            TestErrorLogger.throwErrors(false);
            webGroupIsAdded();

            db().executeTransaction(new DeleteWebGroupTransaction(GROUP_NAME, "no_access"));
            Throwable exception = TestErrorLogger.getLatest().orElseThrow(AssertionError::new);
            assertEquals("com.djrapitops.plan.exceptions.database.DBOpException: Group not found for given name", exception.getMessage());

            assertTrue(db().query(WebUserQueries.fetchGroupId(GROUP_NAME)).isPresent());
        } finally {
            TestErrorLogger.throwErrors(true);
        }
    }

    @Test
    @DisplayName("RemoveEverythingTransaction deletes all groups")
    default void removeEverythingRemovesAllGroups() {
        webGroupIsAdded();

        db().executeTransaction(new RemoveEverythingTransaction());

        assertTrue(db().query(WebUserQueries.fetchGroupId(GROUP_NAME)).isEmpty());
        assertTrue(db().query(WebUserQueries.fetchGroupId("admin")).isEmpty());
    }

    @Test
    @DisplayName("RemoveEverythingTransaction deletes all permissions")
    default void removeEverythingRemovesAllPermissions() throws Exception {
        customWebPermissionsAreStored();

        db().executeTransaction(new RemoveEverythingTransaction()).get();

        assertTrue(db().query(WebUserQueries.fetchPermissionId("test.permission")).isEmpty());
        assertTrue(db().query(WebUserQueries.fetchPermissionId(WebPermission.ACCESS.getPermission())).isEmpty());
    }

    @Test
    @DisplayName("User preferences are stored")
    default void userPreferencesAreStored() throws Exception {
        userIsRegistered();

        WebUser user = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new).toWebUser();
        Preferences defaultPreferences = config().getDefaultPreferences();
        String json = new Gson().toJson(defaultPreferences);
        db().executeTransaction(new StoreWebUserPreferencesTransaction(json, user)).get();

        Preferences stored = db().query(WebUserQueries.fetchPreferences(user)).orElseThrow(AssertionError::new);
        assertEquals(defaultPreferences, stored);
    }

    @Test
    @DisplayName("RemoveEverythingTransaction deletes all preferences")
    default void removeEverythingRemovesAllPreferences() throws Exception {
        userPreferencesAreStored();
        WebUser user = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow(AssertionError::new).toWebUser();

        db().executeTransaction(new RemoveEverythingTransaction()).get();

        assertTrue(db().query(WebUserQueries.fetchPreferences(user)).isEmpty());
    }

    private WebUser forumViewer(String issuer, String subject, String username) {
        ForumIdentity identity = new ForumIdentity(issuer, subject, TestConstants.PLAYER_ONE_UUID, "revision", 100, 900, 60);
        return new ForumUser(username, "EditableName", identity).toWebUser();
    }

    @Test
    default void externalPreferencesPersistWithoutCreatingPasswordAccounts() throws Exception {
        WebUser user = forumViewer("https://forums.example.test", "42", "forum:display:42");
        Preferences expected = config().getDefaultPreferences();
        db().executeTransaction(new StoreWebUserPreferencesTransaction(new Gson().toJson(expected), user)).get();
        assertEquals(expected, db().query(WebUserQueries.fetchPreferences(user)).orElseThrow());
        assertTrue(db().query(WebUserQueries.fetchAllUsers()).isEmpty());
        assertTrue(db().query(WebUserQueries.fetchAllPreferences()).isEmpty());
        assertEquals(1, db().query(WebUserQueries.fetchAllExternalPreferences()).size());
    }

    @Test
    default void externalPreferencesUseImmutableIssuerAndSubjectAcrossNames() throws Exception {
        WebUser original = forumViewer("https://forums.example.test", "42", "old-forum-login");
        Preferences expected = config().getDefaultPreferences();
        db().executeTransaction(new StoreWebUserPreferencesTransaction(new Gson().toJson(expected), original)).get();
        WebUser renamed = forumViewer("https://forums.example.test", "42", "new-forum-login");
        assertEquals(expected, db().query(WebUserQueries.fetchPreferences(renamed)).orElseThrow());
        assertTrue(db().query(WebUserQueries.fetchPreferences(forumViewer("https://other.example.test", "42", "old-forum-login"))).isEmpty());
        assertTrue(db().query(WebUserQueries.fetchPreferences(forumViewer("https://forums.example.test", "43", "old-forum-login"))).isEmpty());
        WebUser otherProvider = new WebUser("EditableName", TestConstants.PLAYER_ONE_UUID, "old-forum-login", List.of(),
                "other", original.getAuthenticationSubject().orElseThrow());
        assertTrue(db().query(WebUserQueries.fetchPreferences(otherProvider)).isEmpty());
    }

    @Test
    default void matchingLocalUsernameCannotReadOrChangeExternalPreferences() throws Exception {
        userIsRegistered();
        WebUser local = db().query(WebUserQueries.fetchUser(WEB_USERNAME)).orElseThrow().toWebUser();
        WebUser external = forumViewer("https://forums.example.test", "42", WEB_USERNAME);
        String localJson = "{\"firstDay\":1}";
        String externalJson = "{\"firstDay\":6}";
        db().executeTransaction(new StoreWebUserPreferencesTransaction(localJson, local)).get();
        assertTrue(db().query(WebUserQueries.fetchPreferences(external)).isEmpty());
        db().executeTransaction(new StoreWebUserPreferencesTransaction(externalJson, external)).get();
        assertEquals(1, db().query(WebUserQueries.fetchPreferences(local)).orElseThrow().getFirstDay());
        assertEquals(6, db().query(WebUserQueries.fetchPreferences(external)).orElseThrow().getFirstDay());
        db().executeTransaction(new StoreWebUserPreferencesTransaction("{\"firstDay\":2}", local)).get();
        assertEquals(6, db().query(WebUserQueries.fetchPreferences(external)).orElseThrow().getFirstDay());
    }

    @Test
    default void externalPreferenceUpdatesReplaceOneIdentityAndClearWithDatabase() throws Exception {
        WebUser user = forumViewer("https://forums.example.test", "42", "editable-login");
        db().executeTransaction(new StoreWebUserPreferencesTransaction("{\"firstDay\":1}", user)).get();
        db().executeTransaction(new StoreWebUserPreferencesTransaction("{\"firstDay\":5}", user)).get();
        assertEquals(5, db().query(WebUserQueries.fetchPreferences(user)).orElseThrow().getFirstDay());
        assertEquals(1, db().query(WebUserQueries.fetchAllExternalPreferences()).size());
        db().executeTransaction(new RemoveEverythingTransaction()).get();
        assertTrue(db().query(WebUserQueries.fetchPreferences(user)).isEmpty());
    }

    @Test
    default void externalPreferencesMergePreservesDestinationChoices() throws Exception {
        WebUser user = forumViewer("https://forums.example.test", "42", "editable-login");
        db().executeTransaction(new StoreWebUserPreferencesTransaction("{\"firstDay\":5}", user)).get();
        ExternalPreferencesTable.Row incoming = new ExternalPreferencesTable.Row(1, ExternalPreferencesTable.key(user),
                user.getAuthenticationProvider().orElseThrow(), user.getAuthenticationSubject().orElseThrow(), "{\"firstDay\":2}");
        db().executeInTransaction(LargeStoreQueries.insertExternalPreferences(List.of(incoming), db().getType())).get();
        assertEquals(5, db().query(WebUserQueries.fetchPreferences(user)).orElseThrow().getFirstDay());
    }

    @Test
    default void metadataPreferencesReadAndWriteOnlyTheAuthenticatedForumIdentity() {
        WebUser user = forumViewer("https://forums.example.test", "42", "editable-login");
        WebUser other = forumViewer("https://forums.example.test", "43", "another-login");
        byte[] body = "{\"firstDay\":4,\"username\":\"another-login\",\"authenticationSubject\":\"43\"}".getBytes(StandardCharsets.UTF_8);
        Request request = new Request("POST", new URIPath("/v1/storePreferences"), new URIQuery(""), user, Map.of(), body, "127.0.0.1");
        new StorePreferencesJSONResolver(dbSystem()).resolve(request);
        assertEquals(4, db().query(WebUserQueries.fetchPreferences(user)).orElseThrow().getFirstDay());
        assertTrue(db().query(WebUserQueries.fetchPreferences(other)).isEmpty());
        String json = new PreferencesJSONResolver(config(), dbSystem()).resolve(
                new Request("GET", "/v1/preferences", user, Map.of(), "127.0.0.1")).orElseThrow().getAsString();
        assertEquals(4, JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("preferences").get("firstDay").getAsInt());
    }
}
