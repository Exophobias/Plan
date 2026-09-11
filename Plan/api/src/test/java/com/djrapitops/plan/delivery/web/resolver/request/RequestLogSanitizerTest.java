package com.djrapitops.plan.delivery.web.resolver.request;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RequestLogSanitizerTest {
    @Test void authenticationUrisOmitQueriesButAnalyticsQueriesRemain() {
        assertEquals("/auth/forum/callback", RequestLogSanitizer.uri("/auth/forum/callback?code=secret&state=private"));
        assertEquals("https://plan.example/auth/login", RequestLogSanitizer.uri("https://plan.example/auth/login?password=secret"));
        assertEquals("/player/example?raw=true", RequestLogSanitizer.uri("/player/example?raw=true"));
    }

    @Test void requestDiagnosticsRedactCredentialsWithoutMutatingRequest() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "auth=COOKIE_SECRET");
        headers.put("AUTHORIZATION", "Bearer BEARER_SECRET");
        headers.put("Accept", "application/json");
        Request request = new Request("GET", "/auth/forum/callback?code=CODE_SECRET&state=STATE_SECRET", null, headers);
        String diagnostic = request.toString();
        assertFalse(diagnostic.contains("COOKIE_SECRET"));
        assertFalse(diagnostic.contains("BEARER_SECRET"));
        assertFalse(diagnostic.contains("CODE_SECRET"));
        assertFalse(diagnostic.contains("STATE_SECRET"));
        assertTrue(diagnostic.contains("application/json"));
        assertEquals("auth=COOKIE_SECRET", request.getHeader("Cookie").orElseThrow(AssertionError::new));
    }
}
