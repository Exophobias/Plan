/*
 *  This file is part of Player Analytics (Plan).
 */
package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.delivery.web.resolver.request.WebUser;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.webserver.ResponseFactory;
import com.djrapitops.plan.delivery.webserver.http.WebServer;
import com.djrapitops.plan.delivery.webserver.resolver.RootPageResolver;
import com.djrapitops.plan.identification.Server;
import com.djrapitops.plan.identification.ServerInfo;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ForumUserTest {

    private final ForumIdentity identity = new ForumIdentity("https://forums.example.test", "42", UUID.randomUUID(), "r1", 100, 900, 60);

    @Test
    void selfOnlyForumUserRetainsExternalProvenanceWithoutPassword() {
        ForumUser forum = new ForumUser("gerber11", "RenamableName", identity);
        WebUser user = forum.toWebUser();
        assertEquals("forum", user.getAuthenticationProvider().orElseThrow());
        assertEquals(ForumAuthService.hash(identity.issuer()) + ":42", user.getAuthenticationSubject().orElseThrow());
        assertTrue(user.hasPermission("access.player.self"));
        assertFalse(user.hasPermission("access.player"));
        assertFalse(user.hasPermission("manage.users"));
        assertFalse(forum.doesPasswordMatch(""));
    }

    @Test
    void linkedPermissionsAreImmutableAndPreserveExternalPreferenceIdentity() {
        List<String> grants = new ArrayList<>(List.of("access", "manage.users"));
        ForumPermissions permissions = new ForumPermissions("admin", grants);
        ForumUser forum = new ForumUser("forum:issuer:42", "RenamedPlayer", identity, permissions);
        grants.clear();

        assertEquals(List.of("access", "manage.users"), forum.getPermissions());
        assertThrows(UnsupportedOperationException.class, () -> permissions.permissions().clear());
        assertEquals("admin", forum.getPermissionGroup());
        assertTrue(forum.toWebUser().hasPermission("manage.users"));
        assertEquals("forum", forum.toWebUser().getAuthenticationProvider().orElseThrow());
        assertEquals(ForumAuthService.hash(identity.issuer()) + ":42", forum.toWebUser().getAuthenticationSubject().orElseThrow());
        assertEquals("", forum.getPasswordHash());
        assertFalse(forum.doesPasswordMatch("anything"));
    }

    @Test
    void emptyMappedPermissionsDoNotGainDefaultSelfAccess() {
        ForumUser forum = new ForumUser("forum:issuer:42", "Player", identity, new ForumPermissions("no_access", List.of()));
        assertTrue(forum.getPermissions().isEmpty());
        assertFalse(forum.toWebUser().hasPermission("access.player.self"));
    }

    @Test
    void normalRootLandingKeepsUnmappedForumUsersOnTheirOwnUuid() {
        ForumUser forum = new ForumUser("forum:issuer:42", "RenamedPlayer", identity);
        assertEquals("player/" + identity.minecraftUUID(), landing(forum, false).getHeaders().get("Location"));
    }

    @Test
    void normalRootLandingUsesLocalAdminServerOrNetworkPermissions() {
        ForumUser forum = new ForumUser("forum:issuer:42", "RenamedPlayer", identity,
                new ForumPermissions("admin", List.of("access", "page")));
        assertEquals("server/Patriam", landing(forum, false).getHeaders().get("Location"));
        assertEquals("network", landing(forum, true).getHeaders().get("Location"));
    }

    @Test
    void normalRootLandingDeniesMappedAccountWithoutAnyPermissions() {
        ForumUser forum = new ForumUser("forum:issuer:42", "Player", identity, new ForumPermissions("no_access", List.of()));
        assertEquals(403, landing(forum, false).getCode());
    }

    private static Response landing(ForumUser forum, boolean proxy) {
        WebServer web = mock(WebServer.class);
        when(web.isAuthRequired()).thenReturn(true);
        Server server = mock(Server.class);
        when(server.isProxy()).thenReturn(proxy);
        when(server.getIdentifiableName()).thenReturn("Patriam");
        ServerInfo info = mock(ServerInfo.class);
        when(info.getServer()).thenReturn(server);
        ResponseFactory responses = mock(ResponseFactory.class);
        when(responses.redirectResponse(anyString())).thenAnswer(invocation -> Response.builder().setStatus(302)
                .setHeader("Location", invocation.getArgument(0)).setContent(new byte[0]).build());
        when(responses.forbidden403(anyString())).thenReturn(Response.builder().setStatus(403).setContent(new byte[0]).build());
        return new RootPageResolver(responses, () -> web, info)
                .resolve(new Request("GET", "/", forum.toWebUser(), Map.of(), "127.0.0.1")).orElseThrow();
    }

}
