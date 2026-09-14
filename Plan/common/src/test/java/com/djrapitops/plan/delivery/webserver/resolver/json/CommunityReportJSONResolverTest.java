package com.djrapitops.plan.delivery.webserver.resolver.json;

import com.djrapitops.plan.community.CommunityAnalyticsSvc;
import com.djrapitops.plan.delivery.web.resolver.request.*;
import com.djrapitops.plan.delivery.webserver.http.WebServer;
import com.djrapitops.plan.identification.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunityReportJSONResolverTest {
    private final WebServer web = mock(WebServer.class);
    private final Identifiers identifiers = mock(Identifiers.class);
    private final CommunityAnalyticsSvc service = mock(CommunityAnalyticsSvc.class);
    private final CommunityReportJSONResolver resolver = new CommunityReportJSONResolver(identifiers, service, () -> web);
    private Request request(String dates, String... grants) {
        return new Request("GET", "/v1/community-report?server=1&" + dates,
                new WebUser("staff", UUID.randomUUID(), "staff", List.of(grants)), Map.of());
    }
    private static final String DATES = "start=2026-10-01&end=2026-11-01";
    @Test void publicModeRejectsDirectResolveEvenWithExplicitPermission() {
        assertEquals(403, resolver.resolve(request(DATES, "page.server.reports", "access.server")).orElseThrow().getCode());
        verifyNoInteractions(service);
    }
    @Test void anonymousSelfAndInheritedParentPermissionsCannotGenerate() {
        when(web.isAuthRequired()).thenReturn(true);
        for (Request request : List.of(request(DATES, "page", "access.server"), request(DATES, "page.server", "access.server"),
                request(DATES, "page.player", "access.player.self"), request(DATES, "page.server.reports"),
                new Request("GET", "/v1/community-report?" + DATES, null, Map.of())))
            assertEquals(403, resolver.resolve(request).orElseThrow().getCode());
        verifyNoInteractions(service);
    }
    @Test void authenticatedGenerationUsesRequestedServerAndDatesAndNoSharedCache() {
        when(web.isAuthRequired()).thenReturn(true); UUID server = UUID.randomUUID();
        when(identifiers.getServerUUID(any(Request.class))).thenReturn(ServerUUID.from(server));
        Map<String, Object> output = new HashMap<>(); output.put("participants", null);
        when(service.report(server, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))).thenReturn(output);
        var response = resolver.resolve(request(DATES, "page.server.reports", "access.server")).orElseThrow();
        assertEquals(200, response.getCode()); assertEquals("{\"participants\":null}", response.getAsString());
        assertEquals("private, no-store", response.getHeaders().get("Cache-Control"));
    }
    @Test void malformedAndMissingDatesFailBeforeStorage() {
        when(web.isAuthRequired()).thenReturn(true);
        when(identifiers.getServerUUID(any(Request.class))).thenReturn(ServerUUID.from(UUID.randomUUID()));
        for (String dates : List.of("", "start=2026-02-30&end=2026-03-01", "start=26-01-01&end=2026-03-01"))
            assertEquals(400, resolver.resolve(request(dates, "page.server.reports", "access.server")).orElseThrow().getCode());
        verifyNoInteractions(service);
    }
    @Test void storageFailureCannotBecomeZeroActivity() {
        when(web.isAuthRequired()).thenReturn(true);
        when(identifiers.getServerUUID(any(Request.class))).thenReturn(ServerUUID.from(UUID.randomUUID()));
        when(service.report(any(), any(), any())).thenThrow(new IllegalStateException("private diagnostic"));
        var response = resolver.resolve(request(DATES, "page.server.reports", "access.server")).orElseThrow();
        assertEquals(503, response.getCode()); assertFalse(response.getAsString().contains("private diagnostic"));
    }
}
