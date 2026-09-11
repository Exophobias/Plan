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
package com.djrapitops.plan.delivery.webserver;

import com.djrapitops.plan.delivery.domain.auth.WebPermission;
import com.djrapitops.plan.delivery.domain.datatransfer.GenericFilter;
import com.djrapitops.plan.delivery.formatting.Formatters;
import com.djrapitops.plan.delivery.rendering.json.PlayerJSONCreator;
import com.djrapitops.plan.delivery.rendering.json.datapoint.DatapointStore;
import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.web.resolver.request.WebUser;
import com.djrapitops.plan.delivery.webserver.auth.PlayerAccess;
import com.djrapitops.plan.delivery.webserver.resolver.PlayerPageResolver;
import com.djrapitops.plan.delivery.webserver.resolver.json.PlayerJSONResolver;
import com.djrapitops.plan.delivery.webserver.resolver.json.SessionsJSONResolver;
import com.djrapitops.plan.delivery.webserver.resolver.json.query.DataPointJSONResolver;
import com.djrapitops.plan.identification.Identifiers;
import com.djrapitops.plan.identification.UUIDUtility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlayerAccessTest {

    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<String> SELF_PERMISSIONS = List.of(
            "access.player.self", "page.player.sessions", "page.player.plugins", "data.player.playtime");

    private static WebUser selfUser(String displayName) {
        return new WebUser(displayName, SELF, "forum:42", SELF_PERMISSIONS);
    }

    private static Request request(String target, WebUser user) {
        return new Request("GET", target, user, Map.of(), "127.0.0.1");
    }

    @Test
    void htmlUsesUuidAfterNameChangeAndRejectsReusedName() {
        UUIDUtility uuids = mock(UUIDUtility.class);
        when(uuids.getUUIDOf(SELF.toString())).thenReturn(SELF);
        when(uuids.getUUIDOf("NewName")).thenReturn(SELF);
        when(uuids.getUUIDOf("OldName")).thenReturn(OTHER);
        PlayerPageResolver resolver = new PlayerPageResolver(null, null, uuids);
        WebUser staleDisplayName = selfUser("OldName");

        assertTrue(resolver.canAccess(request("/player/" + SELF, staleDisplayName)));
        assertTrue(resolver.canAccess(request("/player/NewName", staleDisplayName)));
        assertFalse(resolver.canAccess(request("/player/OldName", staleDisplayName)));
        assertFalse(resolver.canAccess(request("/player/NewName", new WebUser("NewName", null, "forum:42", SELF_PERMISSIONS))));
    }

    @Test
    void jsonUsesUuidEvenWhenForumDisplayNameIsAnotherMinecraftName() {
        Identifiers identifiers = mock(Identifiers.class);
        PlayerJSONResolver resolver = new PlayerJSONResolver(identifiers, null);
        WebUser user = selfUser("OtherPlayer");
        Request self = request("/v1/player?player=" + SELF, user);
        Request other = request("/v1/player?player=OtherPlayer", user);
        when(identifiers.getPlayerUUID(self)).thenReturn(SELF);
        when(identifiers.getPlayerUUID(other)).thenReturn(OTHER);

        assertTrue(resolver.canAccess(self));
        assertFalse(resolver.canAccess(other));
        verify(identifiers, never()).getPlayerUUID(any(String.class));
    }

    @Test
    void nameReassignmentBetweenHtmlCheckAndResolutionCannotExposeOtherPlayer() {
        UUIDUtility uuids = mock(UUIDUtility.class);
        when(uuids.getUUIDOf("ReusedName")).thenReturn(SELF, OTHER);
        ResponseFactory responses = mock(ResponseFactory.class);
        Response forbidden = Response.builder().setStatus(403).setContent(new byte[0]).build();
        when(responses.forbidden403()).thenReturn(forbidden);
        PlayerPageResolver resolver = new PlayerPageResolver(null, responses, uuids);
        Request request = request("/player/ReusedName", selfUser("ReusedName"));

        assertTrue(resolver.canAccess(request));
        assertSame(forbidden, resolver.resolve(request).orElseThrow());
        verify(responses, never()).playerPageResponse(any(), any());
    }

    @Test
    void nameReassignmentBetweenJsonCheckAndResolutionCannotExposeOtherPlayer() {
        Identifiers identifiers = mock(Identifiers.class);
        Request request = request("/v1/player?player=ReusedName", selfUser("ReusedName"));
        when(identifiers.getPlayerUUID(request)).thenReturn(SELF, OTHER);
        PlayerJSONCreator creator = mock(PlayerJSONCreator.class);
        PlayerJSONResolver resolver = new PlayerJSONResolver(identifiers, creator);

        assertTrue(resolver.canAccess(request));
        assertEquals(403, resolver.resolve(request).orElseThrow().getCode());
        verifyNoInteractions(creator);
    }

    @Test
    void rawRequiresBothTargetPlayerAndRawPermission() {
        WebUser rawOnly = new WebUser("Name", SELF, "local", List.of("access.raw.player.data"));
        WebUser rawSelf = new WebUser("Name", SELF, "local", List.of("access.player.self", "access.raw.player.data"));
        WebUser staff = new WebUser("Name", null, "staff", List.of("access.player", "access.raw.player.data"));

        assertFalse(PlayerAccess.canAccessRaw(selfUser("Name"), SELF));
        assertFalse(PlayerAccess.canAccessRaw(rawOnly, SELF));
        assertFalse(PlayerAccess.canAccess(rawOnly, SELF));
        assertTrue(PlayerAccess.canAccessRaw(rawSelf, SELF));
        assertFalse(PlayerAccess.canAccessRaw(rawSelf, OTHER));
        assertTrue(PlayerAccess.canAccessRaw(staff, OTHER));
    }

    @Test
    void sessionsRequireTheRequestedPlayerAndRejectMalformedPlayerFilter() {
        SessionsJSONResolver resolver = new SessionsJSONResolver(new Identifiers(null, null), null, null, mock(Formatters.class));
        WebUser user = selfUser("Unrelated forum name");

        assertTrue(resolver.canAccess(request("/v1/sessions?player=" + SELF, user)));
        assertFalse(resolver.canAccess(request("/v1/sessions?player=" + OTHER, user)));
        assertFalse(resolver.canAccess(request("/v1/sessions?player=not-a-uuid", user)));
        assertFalse(resolver.canAccess(request("/v1/sessions?player=", user)));
        assertFalse(resolver.canAccess(request("/v1/sessions", user)));
        assertFalse(resolver.canAccess(request("/v1/sessions?server=" + OTHER, user)));
        assertFalse(resolver.canAccess(request("/v1/sessions?player=" + SELF,
                new WebUser("Name", SELF, "forum:42", List.of("page.player.sessions")))));
    }

    @Test
    void datapointsRequireTheRequestedPlayerAndExplicitDatapointPermission() {
        Identifiers identifiers = new Identifiers(null, null);
        DatapointStore store = mock(DatapointStore.class);
        when(store.getPermission(any(), any())).thenAnswer(invocation -> {
            GenericFilter filter = invocation.getArgument(1);
            return Optional.of(filter.getPlayerUUID().isPresent()
                    ? WebPermission.DATA_PLAYER_PLAYTIME : WebPermission.DATA_NETWORK_PLAYTIME);
        });
        DataPointJSONResolver resolver = new DataPointJSONResolver(identifiers, store, mock(Formatters.class));
        WebUser user = selfUser("Unrelated forum name");

        assertTrue(resolver.canAccess(request("/v1/datapoint?type=PLAYTIME&player=" + SELF, user)));
        assertFalse(resolver.canAccess(request("/v1/datapoint?type=PLAYTIME&player=" + OTHER, user)));
        assertFalse(resolver.canAccess(request("/v1/datapoint?type=PLAYTIME&player=not-a-uuid", user)));
        assertFalse(resolver.canAccess(request("/v1/datapoint?type=PLAYTIME", user)));
        assertFalse(resolver.canAccess(request("/v1/datapoint?type=PLAYTIME&player=" + SELF,
                new WebUser("Name", SELF, "forum:42", List.of("access.player.self")))));
        assertFalse(resolver.canAccess(request("/v1/datapoint?type=PLAYTIME&player=not-a-uuid",
                new WebUser("Name", null, "staff", List.of("access", "data")))));
    }
}
