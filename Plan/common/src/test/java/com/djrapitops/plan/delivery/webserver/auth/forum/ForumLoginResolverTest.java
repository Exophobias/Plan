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

import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.webserver.resolver.auth.ForumLoginResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ForumLoginResolverTest {
    private final ForumAuthService service = mock(ForumAuthService.class);
    private final ForumLoginResolver resolver = new ForumLoginResolver(service);

    private Response resolve(String method, String target, Map<String, String> headers) {
        return resolver.resolve(new Request(method, target, null, headers, "127.0.0.1")).orElseThrow();
    }

    @Test
    void startIssuesHostBoundHttpOnlyTransactionCookieWithSafeCacheHeaders() throws IOException {
        when(service.isEnabled()).thenReturn(true);
        when(service.begin()).thenReturn(new ForumAuthService.LoginStart("https://forums.example.test/plan-auth/authorize?state=state", "browser"));
        Response response = resolve("GET", "/auth/forum/start", Map.of());
        assertEquals(302, response.getCode());
        assertEquals("__Host-plan-login=browser; Path=/; Max-Age=300; SameSite=Lax; Secure; HttpOnly",
                response.getHeaders().get("Set-Cookie"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        assertEquals("no-referrer", response.getHeaders().get("Referrer-Policy"));
    }

    @Test
    void callbackIssuesSecureSessionOnlyFromValidatedResult() throws IOException {
        when(service.isEnabled()).thenReturn(true);
        when(service.complete("code", "state", "browser")).thenReturn(new ForumAuthService.LoginResult("forum1_cookie", "/player/uuid", 123));
        Response response = resolve("GET", "/auth/forum/callback?code=code&state=state", Map.of("Cookie", "other=value; __Host-plan-login=browser"));
        assertEquals(302, response.getCode());
        assertEquals("/player/uuid", response.getHeaders().get("Location"));
        assertEquals("auth=forum1_cookie; Path=/; Max-Age=123; SameSite=Lax; Secure; HttpOnly", response.getHeaders().get("Set-Cookie"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        assertEquals("no-referrer", response.getHeaders().get("Referrer-Policy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "other=value", "__Host-plan-login=one; __Host-plan-login=two"})
    void absentOrDuplicatedTransactionCookieCannotAuthenticate(String cookies) throws IOException {
        when(service.isEnabled()).thenReturn(true);
        when(service.complete(any(), any(), isNull())).thenThrow(new IOException("invalid transaction"));
        Response response = resolve("GET", "/auth/forum/callback?code=code&state=state", Map.of("cookie", cookies));
        assertEquals("/login?forumError=1", response.getHeaders().get("Location"));
        assertFalse(response.getHeaders().containsKey("Set-Cookie"));
        verify(service).complete(eq("code"), eq("state"), isNull());
    }

    @Test
    void callbackErrorsNeverReflectBackchannelDetailsOrCodes() throws IOException {
        when(service.isEnabled()).thenReturn(true);
        when(service.complete(any(), any(), any())).thenThrow(new IOException("secret-backchannel-detail"));
        Response response = resolve("GET", "/auth/forum/callback?code=sensitive-code&state=state", Map.of("Cookie", "__Host-plan-login=browser"));
        assertEquals("/login?forumError=1", response.getHeaders().get("Location"));
        assertEquals("", response.getAsString());
        assertFalse(response.getHeaders().toString().contains("sensitive-code"));
        assertFalse(response.getHeaders().toString().contains("secret-backchannel-detail"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE", "PUT", "HEAD"})
    void loginRoutesRejectUnexpectedMethods(String method) {
        Response response = resolve(method, "/auth/forum/start", Map.of());
        assertEquals(405, response.getCode());
        assertEquals("GET", response.getHeaders().get("Allow"));
        verifyNoInteractions(service);
    }

    @Test
    void disabledServiceReportsStatusAndRejectsStartingLogin() {
        assertEquals("{\"enabled\":false}", resolve("GET", "/auth/forum/status", Map.of()).getAsString());
        assertEquals(503, resolve("GET", "/auth/forum/start", Map.of()).getCode());
    }
}
