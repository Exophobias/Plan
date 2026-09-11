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
import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.webserver.auth.ActiveCookieStore;
import com.djrapitops.plan.delivery.webserver.auth.AuthenticationExtractor;
import com.djrapitops.plan.delivery.webserver.auth.Cookie;
import com.djrapitops.plan.delivery.webserver.auth.CookieMetadata;
import com.djrapitops.plan.delivery.webserver.http.InternalRequest;
import com.djrapitops.plan.delivery.webserver.resolver.auth.LogoutResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForumAuthenticationBoundaryTest {
    private static final String COOKIE = ForumAuthService.COOKIE_PREFIX + "c".repeat(43);
    private final ActiveCookieStore localCookies = mock(ActiveCookieStore.class);
    private final ForumAuthService forum = mock(ForumAuthService.class);
    private final AuthenticationExtractor extractor = new AuthenticationExtractor(localCookies, forum);

    private InternalRequest request(Cookie... cookies) {
        InternalRequest request = mock(InternalRequest.class);
        when(request.getCookies()).thenReturn(List.of(cookies));
        return request;
    }

    @Test
    void forumCookiesUseVerifiedForumServiceAndNeverFallBackToLocalAccounts() {
        User user = mock(User.class);
        when(forum.authenticate(COOKIE)).thenReturn(user);
        assertSame(user, extractor.extractAuthentication(request(new Cookie("auth", COOKIE))).orElseThrow().getUser());
        assertNull(extractor.extractAuthentication(request(new Cookie("auth", "forum1_malformed"))).orElseThrow().getUser());
        verifyNoInteractions(localCookies);
    }

    @Test
    void localEmergencyAccountAuthenticationRemainsAvailable() {
        User local = mock(User.class);
        when(localCookies.findCookie("local-cookie")).thenReturn(Optional.of(new CookieMetadata(local, Long.MAX_VALUE, "127.0.0.1")));
        assertSame(local, extractor.extractAuthentication(request(new Cookie("auth", "local-cookie"))).orElseThrow().getUser());
        verifyNoInteractions(forum);
    }

    @Test
    void duplicateAuthenticationCookiesAreRejectedWithoutChoosingAnIdentity() {
        assertTrue(extractor.extractAuthentication(request(new Cookie("auth", COOKIE), new Cookie("auth", "local-cookie"))).isEmpty());
        verifyNoInteractions(localCookies, forum);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cookie", "cookie", "cOoKiE"})
    void logoutRevokesForumSessionAndExpiresTheBrowserCookie(String header) throws IOException {
        LogoutResolver resolver = new LogoutResolver(localCookies, forum);
        Response response = resolver.resolve(new Request("GET", "/auth/logout", null,
                Map.of(header, "other=value; auth=" + COOKIE), "127.0.0.1")).orElseThrow();
        assertEquals(302, response.getCode());
        assertTrue(response.getHeaders().get("Set-Cookie").contains("Path=/; Max-Age=0"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        verify(forum).logout(COOKIE);
        verifyNoInteractions(localCookies);
    }

    @Test
    void logoutStorageFailureIsReportedWithoutFalselyClaimingRevocation() throws IOException {
        doThrow(new IOException("private-storage-detail")).when(forum).logout(COOKIE);
        LogoutResolver resolver = new LogoutResolver(localCookies, forum);
        Response response = resolver.resolve(new Request("GET", "/auth/logout", null,
                Map.of("Cookie", "auth=" + COOKIE), "127.0.0.1")).orElseThrow();
        assertEquals(503, response.getCode());
        assertFalse(response.getHeaders().containsKey("Set-Cookie"));
        assertFalse(response.getAsString().contains("private-storage-detail"));
    }
}
