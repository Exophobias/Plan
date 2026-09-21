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
package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.delivery.domain.auth.User;
import com.djrapitops.plan.settings.forumauth.ForumAuthConfig;
import net.playeranalytics.plugin.server.PluginLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ForumAuthServiceTest {
    private static final String ISSUER = "https://forums.example.test";
    private static final String CODE = "c".repeat(43);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    @TempDir
    Path directory;
    private final MutableClock clock = new MutableClock();
    private final MemorySessions sessions = new MemorySessions();
    private final PluginLogger logger = mock(PluginLogger.class);
    private ForumAuthConfig config;
    private ForumBroker broker;
    private ForumAuthService service;

    @BeforeEach
    void setUp() throws IOException {
        config = config("secret-for-test-client-".repeat(3));
        broker = mock(ForumBroker.class);
        when(broker.redeem(anyString(), anyString(), anyString())).thenAnswer(ignored -> identity());
        when(broker.check(any())).thenAnswer(ignored -> identity());
        service = new ForumAuthService(config, broker, sessions, clock);
    }

    private static ForumAuthConfig config(String secret) {
        return config(secret, Map.of());
    }

    private static ForumAuthConfig config(String secret, Map<String, String> subjectWebGroups) {
        ForumAuthConfig config = mock(ForumAuthConfig.class);
        when(config.isEnabled()).thenReturn(true);
        when(config.getForumUrl()).thenReturn(ISSUER);
        when(config.getClientId()).thenReturn("plan-test");
        when(config.getClientSecret()).thenReturn(secret);
        when(config.getCallbackUrl()).thenReturn("https://plan.example.test/auth/forum/callback");
        when(config.getAuthorizeUrl()).thenReturn(URI.create(ISSUER + "/plan-auth/authorize"));
        when(config.getSessionSeconds()).thenReturn(900);
        when(config.getRecheckSeconds()).thenReturn(60);
        when(config.getSubjectWebGroups()).thenReturn(subjectWebGroups);
        return config;
    }

    private ForumIdentity identity() {
        return new ForumIdentity(ISSUER, "42", PLAYER, "link-revision-1", clock.millis() / 1000, 900, 60);
    }

    private static Map<String, String> query(ForumAuthService.LoginStart start) {
        Map<String, String> values = new HashMap<>();
        for (String part : URI.create(start.redirect()).getRawQuery().split("&")) {
            String[] field = part.split("=", 2);
            values.put(field[0], URLDecoder.decode(field[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private ForumAuthService.LoginResult complete(ForumAuthService.LoginStart start) throws IOException {
        return service.complete(CODE, query(start).get("state"), start.browserCookie());
    }

    @Test
    void loginBindsPkceStateAndBrowserThenIssuesOnlyUuidSelfPermissions() throws IOException {
        ForumAuthService.LoginStart start = service.begin();
        Map<String, String> query = query(start);
        assertEquals("plan-test", query.get("client_id"));
        assertEquals(config.getCallbackUrl(), query.get("redirect_uri"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertFalse(start.redirect().contains(config.getClientSecret()));
        assertFalse(start.redirect().contains(start.browserCookie()));
        doAnswer(invocation -> {
            assertEquals(CODE, invocation.getArgument(0));
            assertEquals(query.get("code_challenge"), ForumAuthService.challenge(invocation.getArgument(1)));
            assertEquals(query.get("state"), invocation.getArgument(2));
            return identity();
        }).when(broker).redeem(anyString(), anyString(), anyString());

        ForumAuthService.LoginResult login = complete(start);
        User user = service.authenticate(login.cookie());

        assertNotNull(user);
        assertEquals(PLAYER, user.getLinkedToUUID());
        assertEquals(ForumAuthService.SELF_PERMISSIONS, user.getPermissions());
        assertTrue(user.getUsername().startsWith("forum:"));
        assertEquals("", user.getPasswordHash());
        assertEquals("/", login.playerPath());
        assertTrue(login.maxAge() > 0 && login.maxAge() <= 900);
        assertTrue(sessions.rows.containsKey(ForumAuthService.hash(login.cookie())));
        assertFalse(sessions.rows.containsKey(login.cookie()));
        for (String forbidden : new String[]{"access.player", "access.raw.player.data", "access.server",
                "access.network", "access.query", "access.players", "page.server.plugins", "manage.users", "data"}) {
            assertFalse(user.toWebUser().hasPermission(forbidden), forbidden);
        }
        verify(broker, never()).check(any());
    }

    @Test
    void verifiedUuidInheritsExactCurrentLocalPermissionsWithoutChangingForumIdentity() throws IOException {
        ForumPermissions admin = new ForumPermissions("admin", List.of("access", "data", "page", "manage.users"));
        sessions.permissions.put(PLAYER, admin);
        ForumAuthService.LoginResult login = complete(service.begin());

        User user = service.authenticate(login.cookie());

        assertNotNull(user);
        assertEquals(admin.permissions(), user.getPermissions());
        assertEquals("admin", user.getPermissionGroup());
        assertEquals(PLAYER, sessions.permissionLookupUuid);
        assertEquals(PLAYER, user.getLinkedToUUID());
        assertTrue(user.toWebUser().hasPermission("access.raw.player.data"));
        assertTrue(user.toWebUser().hasPermission("access.server"));
        assertTrue(user.toWebUser().hasPermission("manage.users"));
        assertEquals("forum", user.toWebUser().getAuthenticationProvider().orElseThrow());
        assertEquals(ForumAuthService.hash(ISSUER) + ":42", user.toWebUser().getAuthenticationSubject().orElseThrow());
        assertTrue(user.getUsername().startsWith("forum:"));
        assertEquals("", user.getPasswordHash());
        assertFalse(user.doesPasswordMatch(""));
        assertEquals("/", login.playerPath());
    }

    @Test
    void immutableForumSubjectUsesExactCurrentConfiguredGroupWithoutLocalAccount() throws IOException {
        ForumPermissions admin = new ForumPermissions("admin", List.of("access", "data", "page", "manage.users"));
        sessions.groups.put("admin", admin);
        service = new ForumAuthService(config("secret-for-test-client-".repeat(3), Map.of("42", "admin")),
                broker, sessions, clock);

        User user = service.authenticate(complete(service.begin()).cookie());

        assertNotNull(user);
        assertEquals(admin.permissions(), user.getPermissions());
        assertEquals(admin.group(), user.getPermissionGroup());
        assertEquals("admin", sessions.permissionLookupGroup);
        assertNull(sessions.permissionLookupUuid);
        assertEquals(PLAYER, user.getLinkedToUUID());
        assertEquals("forum", user.toWebUser().getAuthenticationProvider().orElseThrow());
        assertEquals("", user.getPasswordHash());
        assertFalse(user.doesPasswordMatch(""));
    }

    @Test
    void mappingForAnotherImmutableSubjectDoesNotElevateTheCurrentForumAccount() throws IOException {
        sessions.groups.put("admin", new ForumPermissions("admin", List.of("access", "manage.users")));
        service = new ForumAuthService(config("secret-for-test-client-".repeat(3), Map.of("7", "admin")),
                broker, sessions, clock);

        User user = service.authenticate(complete(service.begin()).cookie());

        assertNotNull(user);
        assertEquals(ForumAuthService.SELF_PERMISSIONS, user.getPermissions());
        assertNull(sessions.permissionLookupGroup);
        assertEquals(PLAYER, sessions.permissionLookupUuid);
    }

    @Test
    void missingConfiguredGroupOrPermissionStorageFailureFailsClosed() throws IOException {
        ForumAuthConfig mapped = config("secret-for-test-client-".repeat(3), Map.of("42", "admin"));
        service = new ForumAuthService(mapped, broker, sessions, clock);
        String missingGroupCookie = complete(service.begin()).cookie();
        assertNull(service.authenticate(missingGroupCookie));
        assertNull(sessions.permissionLookupUuid);

        sessions.groups.put("admin", new ForumPermissions("admin", List.of("access", "manage.users")));
        String unavailableCookie = complete(service.begin()).cookie();
        sessions.permissionsUnavailable = true;
        assertNull(service.authenticate(unavailableCookie));
        assertNull(sessions.permissionLookupUuid);
    }

    @Test
    void configuredGroupPermissionChangesApplyOnTheNextRequest() throws IOException {
        sessions.groups.put("admin", new ForumPermissions("admin", List.of("access", "manage.users")));
        service = new ForumAuthService(config("secret-for-test-client-".repeat(3), Map.of("42", "admin")),
                broker, sessions, clock);
        String cookie = complete(service.begin()).cookie();
        assertTrue(service.authenticate(cookie).toWebUser().hasPermission("manage.users"));

        sessions.groups.put("admin", new ForumPermissions("admin", List.of("access.player.self")));

        User restricted = service.authenticate(cookie);
        assertNotNull(restricted);
        assertEquals(List.of("access.player.self"), restricted.getPermissions());
        assertFalse(restricted.toWebUser().hasPermission("manage.users"));
        assertNull(sessions.permissionLookupUuid);
    }

    @Test
    void matchingMutablePlayerNameCannotBorrowAnotherUuidsPlanPermissions() throws IOException {
        sessions.playerName = "gerber11";
        sessions.permissions.put(UUID.randomUUID(), new ForumPermissions("admin", List.of("access", "manage.users")));

        User user = service.authenticate(complete(service.begin()).cookie());

        assertNotNull(user);
        assertEquals("gerber11", user.getLinkedTo());
        assertEquals(PLAYER, sessions.permissionLookupUuid);
        assertEquals(ForumAuthService.SELF_PERMISSIONS, user.getPermissions());
        assertFalse(user.toWebUser().hasPermission("manage.users"));
    }

    @Test
    void localPermissionDowngradeAndEmptyGroupApplyWithoutWaitingForBrokerRecheck() throws IOException {
        sessions.permissions.put(PLAYER, new ForumPermissions("admin", List.of("access", "manage.users")));
        String cookie = complete(service.begin()).cookie();
        assertTrue(service.authenticate(cookie).toWebUser().hasPermission("manage.users"));

        sessions.permissions.put(PLAYER, new ForumPermissions("restricted", List.of("access.player.self")));
        User restricted = service.authenticate(cookie);
        assertNotNull(restricted);
        assertEquals(List.of("access.player.self"), restricted.getPermissions());
        assertFalse(restricted.toWebUser().hasPermission("page.player.overview"), "self page grants must not be added to a mapped account");
        assertFalse(restricted.toWebUser().hasPermission("manage.users"));

        sessions.permissions.put(PLAYER, new ForumPermissions("no_access", List.of()));
        User denied = service.authenticate(cookie);
        assertNotNull(denied);
        assertTrue(denied.getPermissions().isEmpty());
        assertFalse(denied.toWebUser().hasPermission("access.player.self"));
        verify(broker, never()).check(any());
    }

    @Test
    void removingLocalUuidAssociationDropsInheritedAccessOnNextRequest() throws IOException {
        sessions.permissions.put(PLAYER, new ForumPermissions("admin", List.of("access", "manage.users")));
        String cookie = complete(service.begin()).cookie();
        assertTrue(service.authenticate(cookie).toWebUser().hasPermission("manage.users"));

        sessions.permissions.remove(PLAYER);

        User user = service.authenticate(cookie);
        assertNotNull(user);
        assertEquals(ForumAuthService.SELF_PERMISSIONS, user.getPermissions());
        assertFalse(user.toWebUser().hasPermission("manage.users"));
        verify(broker, never()).check(any());
    }

    @Test
    void permissionStorageFailureCannotReuseEarlierAdminGrantsOrInventSelfGrants() throws IOException {
        sessions.permissions.put(PLAYER, new ForumPermissions("admin", List.of("access", "manage.users")));
        String cookie = complete(service.begin()).cookie();
        assertTrue(service.authenticate(cookie).toWebUser().hasPermission("manage.users"));
        sessions.permissionsUnavailable = true;

        assertNull(service.authenticate(cookie));

        sessions.permissionsUnavailable = false;
        sessions.permissions.put(PLAYER, new ForumPermissions("restricted", List.of("access.player.self")));
        assertEquals(List.of("access.player.self"), service.authenticate(cookie).getPermissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "reload", "expiry"})
    void authorityLookupCannotBypassFinalSessionRevocationFence(String change) throws IOException {
        sessions.permissions.put(PLAYER, new ForumPermissions("admin", List.of("access", "manage.users")));
        String cookie = complete(service.begin()).cookie();
        sessions.beforePermissions = () -> {
            switch (change) {
                case "logout" -> assertDoesNotThrow(() -> service.logout(cookie));
                case "reload" -> service.configure(config, broker);
                case "expiry" -> clock.advance(900_000);
                default -> fail("Unknown change");
            }
        };

        assertNull(service.authenticate(cookie));
    }

    @Test
    void anotherBrowserCannotRedeemOrConsumeTheLegitimateTransaction() throws IOException {
        ForumAuthService.LoginStart start = service.begin();
        assertThrows(IOException.class, () -> service.complete(CODE, query(start).get("state"), "b".repeat(43)));
        assertThrows(IOException.class, () -> service.complete(CODE, query(start).get("state"), null));
        verify(broker, never()).redeem(anyString(), anyString(), anyString());
        assertNotNull(complete(start));
    }

    @Test
    void wrongStateForgedCodeAndReplayCannotIssueSessions() throws IOException {
        ForumAuthService.LoginStart start = service.begin();
        assertThrows(IOException.class, () -> service.complete(CODE, "s".repeat(43), start.browserCookie()));
        assertThrows(IOException.class, () -> service.complete("malformed", query(start).get("state"), start.browserCookie()));
        complete(start);
        assertThrows(IOException.class, () -> complete(start));
        verify(broker, times(1)).redeem(anyString(), anyString(), anyString());
        assertEquals(1, sessions.rows.size());
    }

    @Test
    void concurrentCallbacksCanRedeemTheTransactionOnlyOnce() throws Exception {
        ForumAuthService.LoginStart start = service.begin();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { try { complete(start); return true; } catch (IOException denied) { return false; } });
            var second = executor.submit(() -> { try { complete(start); return true; } catch (IOException denied) { return false; } });
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, sessions.rows.size());
            verify(broker, times(1)).redeem(anyString(), anyString(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {300_000, -1})
    void expiredTransactionOrBackwardClockCannotRedeem(long elapsed) throws IOException {
        ForumAuthService.LoginStart start = service.begin();
        clock.advance(elapsed);
        assertThrows(IOException.class, () -> complete(start));
        verify(broker, never()).redeem(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "future", "old"})
    void wrongIssuerOrInvalidAuthenticationTimeCannotIssueSession(String mutation) throws IOException {
        ForumIdentity valid = identity();
        ForumIdentity wrong = new ForumIdentity(mutation.equals("issuer") ? "https://other.example.test" : ISSUER,
                valid.subject(), PLAYER, valid.revision(), valid.authTime() + (mutation.equals("future") ? 31 : mutation.equals("old") ? -31 : 0),
                900, 60);
        when(broker.redeem(anyString(), anyString(), anyString())).thenReturn(wrong);
        ForumAuthService.LoginStart start = service.begin();
        assertThrows(IOException.class, () -> complete(start));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void redemptionOutageConsumesTransactionWithoutIssuingCookie() throws IOException {
        when(broker.redeem(anyString(), anyString(), anyString())).thenThrow(new IOException("unavailable"));
        ForumAuthService.LoginStart start = service.begin();
        assertThrows(IOException.class, () -> complete(start));
        assertThrows(IOException.class, () -> complete(start));
        assertTrue(sessions.rows.isEmpty());
        verify(broker, times(1)).redeem(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "subject", "uuid", "revision"})
    void accountOrLinkChangeDeniesAccessAfterBoundedRecheck(String mutation) throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        ForumIdentity valid = identity();
        ForumIdentity changed = new ForumIdentity(mutation.equals("issuer") ? "https://other.example.test" : ISSUER,
                mutation.equals("subject") ? "43" : "42", mutation.equals("uuid") ? UUID.randomUUID() : PLAYER,
                mutation.equals("revision") ? "replacement-link" : valid.revision(), valid.authTime(), 900, 60);
        when(broker.check(any())).thenReturn(changed);
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void explicitRefusalPermanentlyRevokesSessionInsteadOfRetryingIt() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        when(broker.check(any())).thenThrow(new ForumVerificationRefusedException());
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
        doReturn(identity()).when(broker).check(any());
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
    }

    @Test
    void configurationChangeDuringRedemptionCannotIssueMixedGenerationSession() throws IOException {
        ForumAuthService.LoginStart start = service.begin();
        when(broker.redeem(anyString(), anyString(), anyString())).thenAnswer(ignored -> {
            service.configure(config("rotated-secret-".repeat(4)), broker);
            return identity();
        });
        assertThrows(IOException.class, () -> complete(start));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void configurationChangeDuringSaveRemovesTheUnissuedCookie() throws IOException {
        ForumAuthService.LoginStart start = service.begin();
        sessions.afterSave = () -> service.configure(config("rotated-secret-".repeat(4)), broker);
        assertThrows(IOException.class, () -> complete(start));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void configurationChangeDuringRecheckCannotAuthorizeTheOldGeneration() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        when(broker.check(any())).thenAnswer(ignored -> {
            service.configure(config("rotated-secret-".repeat(4)), broker);
            return identity();
        });
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
        assertNull(service.authenticate(login.cookie()));
    }

    @Test
    void outageFailsClosedUntilBoundedRetryAndNeverExtendsSession() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        ForumSessionStore.Session original = sessions.rows.get(ForumAuthService.hash(login.cookie()));
        when(broker.check(any())).thenThrow(new IOException("network down"));
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
        assertNull(service.authenticate(login.cookie()));
        verify(broker, times(1)).check(any());
        clock.advance(5_000);
        doReturn(identity()).when(broker).check(any());
        assertNotNull(service.authenticate(login.cookie()));
        assertEquals(original.expires(), sessions.rows.get(ForumAuthService.hash(login.cookie())).expires());
        clock.advance(900_000);
        assertNull(service.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void restartRequiresFreshEligibilityCheckAndPreservesExpiry() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        long expiry = sessions.rows.get(ForumAuthService.hash(login.cookie())).expires();
        ForumAuthService restarted = new ForumAuthService(config, broker, sessions, clock);
        assertNotNull(restarted.authenticate(login.cookie()));
        verify(broker, times(1)).check(any());
        assertEquals(expiry, sessions.rows.get(ForumAuthService.hash(login.cookie())).expires());
        when(broker.check(any())).thenThrow(new IOException("revoked or unavailable"));
        assertNull(new ForumAuthService(config, broker, sessions, clock).authenticate(login.cookie()));
    }

    private void useConfigurationFile() {
        service = new ForumAuthService(directory.toFile(), sessions, logger, clock);
        service.configure(config, broker);
    }

    private void writeConfiguration(boolean enabled) throws IOException {
        Files.writeString(directory.resolve("forum-auth.yml"), "config-version: 3\nenabled: " + enabled
                + "\nforum-url: " + config.getForumUrl() + "\nclient-id: " + config.getClientId()
                + "\nclient-secret: " + config.getClientSecret() + "\ncallback-url: " + config.getCallbackUrl()
                + "\nsession-seconds: " + config.getSessionSeconds() + "\n");
    }

    @Test
    void disablingAndReenablingForumSignInDoesNotResurrectSavedSessions() throws IOException {
        useConfigurationFile();
        ForumAuthService.LoginResult login = complete(service.begin());
        ForumAuthService.LoginStart pending = service.begin();
        writeConfiguration(false);

        service.initialize(true);

        assertFalse(service.isEnabled());
        assertTrue(sessions.rows.isEmpty());
        assertThrows(IOException.class, () -> complete(pending));
        writeConfiguration(true);
        service.initialize(true);
        assertTrue(service.isEnabled());
        assertNull(service.authenticate(login.cookie()));
        assertEquals(1, sessions.removeAllCalls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledPlanAuthenticationRevokesSessionsEvenWhenForumConfigurationIsInvalid(boolean invalidFile) throws IOException {
        useConfigurationFile();
        ForumAuthService.LoginResult login = complete(service.begin());
        writeConfiguration(true);
        if (invalidFile) Files.writeString(directory.resolve("forum-auth.yml"), "config-version: 999\n");
        String original = Files.readString(directory.resolve("forum-auth.yml"));

        service.initialize(false);

        assertFalse(service.isEnabled());
        assertTrue(sessions.rows.isEmpty());
        assertEquals(original, Files.readString(directory.resolve("forum-auth.yml")));
        writeConfiguration(true);
        service.initialize(true);
        assertTrue(service.isEnabled());
        assertNull(service.authenticate(login.cookie()));
        assertEquals(1, sessions.removeAllCalls);
    }

    @Test
    void failedPauseInvalidationMustSucceedBeforeAnyLaterEnable() throws IOException {
        useConfigurationFile();
        ForumAuthService.LoginResult login = complete(service.begin());
        sessions.removeAllUnavailable = true;
        writeConfiguration(false);

        service.initialize(true);

        assertFalse(service.isEnabled());
        assertFalse(sessions.rows.isEmpty());
        assertNull(service.authenticate(login.cookie()));
        writeConfiguration(true);
        service.initialize(true);
        assertFalse(service.isEnabled());
        assertThrows(IOException.class, service::begin);
        assertFalse(sessions.rows.isEmpty());
        assertEquals(2, sessions.removeAllCalls);
        verify(logger, times(2)).warn("Forum sign-in is paused: saved sessions could not be invalidated. Check session storage before enabling.");

        sessions.removeAllUnavailable = false;
        service.initialize(true);
        assertTrue(service.isEnabled());
        assertTrue(sessions.rows.isEmpty());
        assertNull(service.authenticate(login.cookie()));
        assertEquals(3, sessions.removeAllCalls);
    }

    @Test
    void normalEnabledInitializationPreservesSavedSessionsAndOriginalExpiry() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        ForumSessionStore.Session original = sessions.rows.get(ForumAuthService.hash(login.cookie()));
        writeConfiguration(true);
        service = new ForumAuthService(directory.toFile(), sessions, logger, clock);

        service.initialize(true);

        assertTrue(service.isEnabled());
        assertEquals(original, sessions.rows.get(ForumAuthService.hash(login.cookie())));
        assertEquals(0, sessions.removeAllCalls);
        service.configure(config, broker);
        assertNotNull(service.authenticate(login.cookie()));
        assertEquals(original.expires(), sessions.rows.get(ForumAuthService.hash(login.cookie())).expires());
    }

    @Test
    void invalidFileRetainsEnabledSettingsWithoutRevokingSessions() throws IOException {
        useConfigurationFile();
        ForumAuthService.LoginResult login = complete(service.begin());
        Files.writeString(directory.resolve("forum-auth.yml"), "config-version: 999\n");

        service.initialize(true);

        assertTrue(service.isEnabled());
        assertNotNull(service.authenticate(login.cookie()));
        assertEquals(0, sessions.removeAllCalls);
    }

    @Test
    void credentialRotationInvalidatesPersistedSession() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        ForumAuthService rotated = new ForumAuthService(config("rotated-secret-".repeat(4)), broker, sessions, clock);
        assertNull(rotated.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void subjectGroupMappingChangeInvalidatesPersistedSession() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        ForumAuthConfig mapped = config(config.getClientSecret(), Map.of("42", "admin"));

        ForumAuthService changed = new ForumAuthService(mapped, broker, sessions, clock);

        assertNull(changed.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void logoutRevokesCookieAndStorageFailureNeverAuthenticates() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        sessions.unavailable = true;
        assertNull(service.authenticate(login.cookie()));
        assertThrows(IOException.class, () -> service.logout(login.cookie()));
        sessions.unavailable = false;
        service.logout(login.cookie());
        assertNull(service.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void logoutDuringNameLookupCannotAuthorizeTheInflightRequest() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        sessions.beforePlayerName = () -> assertDoesNotThrow(() -> service.logout(login.cookie()));
        assertNull(service.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void failedLogoutDeletionStillRevokesInflightAndReloadedRequests() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        sessions.removeUnavailable = true;
        sessions.beforePlayerName = () -> assertThrows(IOException.class, () -> service.logout(login.cookie()));
        assertNull(service.authenticate(login.cookie()));
        assertFalse(sessions.rows.isEmpty());
        sessions.beforePlayerName = () -> {};
        sessions.removeUnavailable = false;
        service.configure(config, broker);
        assertNull(service.authenticate(login.cookie()));
        service.logout(login.cookie());
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void failedRefusalDeletionDoesNotResurrectAfterSameConfigurationReload() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        sessions.removeUnavailable = true;
        when(broker.check(any())).thenThrow(new ForumVerificationRefusedException());
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
        assertFalse(sessions.rows.isEmpty());
        doReturn(identity()).when(broker).check(any());
        service.configure(config, broker);
        clock.advance(60_000);
        assertNull(service.authenticate(login.cookie()));
    }

    @Test
    void nonexistentLogoutCookieDoesNotAllocateARevocationMarker() throws IOException {
        ForumAuthService.LoginResult login = complete(service.begin());
        String unknown = ForumAuthService.COOKIE_PREFIX + "u".repeat(43);
        service.logout(unknown);
        // A real session appearing later under that synthetic key must not inherit a marker.
        sessions.rows.put(ForumAuthService.hash(unknown), sessions.rows.get(ForumAuthService.hash(login.cookie())));
        assertNotNull(service.authenticate(unknown));
    }

    @Test
    void expiredDuringStorageCannotIssueAnAlreadyExpiredCookie() throws IOException {
        when(config.getSessionSeconds()).thenReturn(1);
        ForumAuthService.LoginStart start = service.begin();
        sessions.afterSave = () -> clock.advance(1500);
        assertThrows(IOException.class, () -> complete(start));
        assertTrue(sessions.rows.isEmpty());
    }

    @Test
    void slowStorageUsesActualCookieLifetimeWithoutExtendingEligibilityCache() throws IOException {
        when(config.getRecheckSeconds()).thenReturn(1);
        sessions.afterSave = () -> clock.advance(3000);
        ForumAuthService.LoginResult login = complete(service.begin());
        assertEquals(897, login.maxAge());
        assertNotNull(service.authenticate(login.cookie()));
        verify(broker, times(1)).check(any());
    }

    @Test
    void disabledAndMalformedCookiesCannotReachAuthentication() throws IOException {
        for (String cookie : new String[]{null, "", "local-cookie", "forum1_short", "forum1_" + "a".repeat(44)}) {
            assertNull(service.authenticate(cookie));
        }
        verify(broker, never()).check(any());
        when(config.isEnabled()).thenReturn(false);
        assertFalse(service.isEnabled());
        assertThrows(IOException.class, service::begin);
    }

    private void longSessions() throws IOException {
        when(config.getSessionSeconds()).thenReturn(ForumAuthConfig.MAX_SESSION_SECONDS);
        when(broker.redeem(anyString(),anyString(),anyString())).thenAnswer(ignored ->
                new ForumIdentity(ISSUER,"42",PLAYER,"link-revision-1",clock.millis()/1000,ForumAuthConfig.MAX_SESSION_SECONDS,60));
        service.configure(config,broker);
    }

    @Test
    void fourteenDaySessionSurvivesFifteenMinutesAndRestartButNeverItsAbsoluteExpiry() throws IOException {
        longSessions();
        ForumAuthService.LoginResult login = complete(service.begin());
        assertEquals(1209600,login.maxAge());
        long expiry = sessions.rows.get(ForumAuthService.hash(login.cookie())).expires();
        clock.advance(900001);
        assertNotNull(service.authenticate(login.cookie()));
        service = new ForumAuthService(config,broker,sessions,clock);
        assertNotNull(service.authenticate(login.cookie()));
        verify(broker,times(2)).check(any());
        sessions.permissions.put(PLAYER,new ForumPermissions("empty",List.of()));
        assertEquals(List.of(),service.authenticate(login.cookie()).getPermissions());
        clock.advance(expiry-clock.millis()-1);
        assertNotNull(service.authenticate(login.cookie()));
        assertEquals(expiry,sessions.rows.get(ForumAuthService.hash(login.cookie())).expires());
        clock.advance(1);
        assertNull(service.authenticate(login.cookie()));
        assertTrue(sessions.rows.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout","refusal"})
    void failedDeletionRemainsRevokedBeyondOldCacheLifetimeAndConfigurationReload(String cause) throws IOException {
        longSessions();
        String cookie = complete(service.begin()).cookie();
        sessions.removeUnavailable = true;
        if (cause.equals("logout")) assertThrows(IOException.class,() -> service.logout(cookie));
        else {
            when(broker.check(any())).thenThrow(new ForumVerificationRefusedException());
            clock.advance(60000); assertNull(service.authenticate(cookie));
        }
        doReturn(identity()).when(broker).check(any());
        clock.advance(3600000);
        service.configure(config,broker);
        assertNull(service.authenticate(cookie));
        assertFalse(sessions.rows.isEmpty());
        // An unacknowledged failed write cannot promise restart durability; fresh verification is mandatory.
        when(broker.check(any())).thenThrow(new ForumVerificationRefusedException());
        assertNull(new ForumAuthService(config,broker,sessions,clock).authenticate(cookie));
        sessions.removeUnavailable = false;
        service.logout(cookie);
        assertNull(new ForumAuthService(config,broker,sessions,clock).authenticate(cookie));
    }

    @Test
    void revocationCapacityNeverEvictsAndRequiresConfirmedGlobalInvalidation() throws IOException {
        service = new ForumAuthService(directory.toFile(),sessions,logger,clock,2);
        service.configure(config,broker);
        String first = complete(service.begin()).cookie(), second = complete(service.begin()).cookie(), third = complete(service.begin()).cookie();
        sessions.removeUnavailable = true;
        assertThrows(IOException.class,() -> service.logout(first));
        assertThrows(IOException.class,() -> service.logout(second));
        assertNull(service.authenticate(first));
        assertThrows(IOException.class,() -> service.logout(third));
        assertFalse(service.isEnabled());
        assertNull(service.authenticate(third));
        verify(logger).warn("Forum sign-in paused: revocation capacity reached. Reload after session storage is healthy to invalidate saved sessions.");
        assertThrows(IllegalStateException.class,() -> service.configure(config,broker));
        writeConfiguration(true);
        sessions.removeAllUnavailable = true;
        service.initialize(true);
        assertFalse(service.isEnabled()); assertEquals(3,sessions.rows.size());
        sessions.removeAllUnavailable = false;
        service.initialize(true);
        assertTrue(service.isEnabled()); assertTrue(sessions.rows.isEmpty());
        assertNull(service.authenticate(first)); assertNull(service.authenticate(third));
    }

    @Test
    void deletionDuringPermissionsLookupIsRecheckedBeforeAuthorization() throws IOException {
        String cookie = complete(service.begin()).cookie();
        sessions.beforePermissions = () -> sessions.rows.remove(ForumAuthService.hash(cookie));
        assertNull(service.authenticate(cookie));
    }

    @Test
    void completedDatabaseFutureWithoutACommittedMutationIsNotAcknowledged() {
        var database = mock(com.djrapitops.plan.storage.database.Database.class);
        var databases = mock(com.djrapitops.plan.storage.database.DBSystem.class);
        when(databases.getDatabase()).thenReturn(database);
        when(database.executeTransaction(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        DatabaseForumSessionStore store = new DatabaseForumSessionStore(databases);
        assertThrows(IOException.class,() -> store.remove("cookie-hash"));
        assertThrows(IOException.class,store::removeAll);
        assertThrows(IOException.class,() -> store.save("cookie-hash",new ForumSessionStore.Session(identity(),"config",clock.millis()+10000)));
    }

    private static final class MemorySessions implements ForumSessionStore {
        final Map<String, Session> rows = new ConcurrentHashMap<>();
        final Map<UUID, ForumPermissions> permissions = new ConcurrentHashMap<>();
        final Map<String, ForumPermissions> groups = new ConcurrentHashMap<>();
        boolean unavailable;
        boolean removeUnavailable;
        boolean removeAllUnavailable;
        boolean permissionsUnavailable;
        UUID permissionLookupUuid;
        String permissionLookupGroup;
        String playerName;
        int removeAllCalls;
        Runnable afterSave = () -> {};
        Runnable beforePlayerName = () -> {};
        Runnable beforePermissions = () -> {};
        private void available() throws IOException { if (unavailable) throw new IOException("storage unavailable"); }
        public Optional<Session> find(String cookieHash) throws IOException { available(); return Optional.ofNullable(rows.get(cookieHash)); }
        public void save(String cookieHash, Session session) throws IOException { available(); rows.put(cookieHash, session); afterSave.run(); }
        public void remove(String cookieHash) throws IOException {
            available();
            if (removeUnavailable) throw new IOException("deletion unavailable");
            rows.remove(cookieHash);
        }
        public void removeAll() throws IOException {
            removeAllCalls++;
            available();
            if (removeAllUnavailable) throw new IOException("private storage diagnostics must not be logged");
            rows.clear();
        }
        public String playerName(UUID uuid) throws IOException { available(); beforePlayerName.run(); return playerName == null ? uuid.toString() : playerName; }
        public Optional<ForumPermissions> linkedPermissions(UUID uuid) throws IOException {
            available();
            if (permissionsUnavailable) throw new IOException("private permission-storage diagnostics");
            permissionLookupUuid = uuid;
            beforePermissions.run();
            return Optional.ofNullable(permissions.get(uuid));
        }
        public Optional<ForumPermissions> groupPermissions(String group) throws IOException {
            available();
            if (permissionsUnavailable) throw new IOException("private permission-storage diagnostics");
            permissionLookupGroup = group;
            beforePermissions.run();
            return Optional.ofNullable(groups.get(group));
        }
    }

    private static final class MutableClock extends Clock {
        private long now = 1_800_000_000_000L;
        void advance(long millis) { now += millis; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
}
