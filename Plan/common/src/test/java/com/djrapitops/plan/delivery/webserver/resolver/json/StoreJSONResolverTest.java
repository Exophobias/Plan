package com.djrapitops.plan.delivery.webserver.resolver.json;

import com.djrapitops.plan.delivery.web.resolver.request.*;
import com.djrapitops.plan.delivery.webserver.http.WebServer;
import com.djrapitops.plan.identification.*;
import com.djrapitops.plan.store.StoreAnalyticsSvc;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreJSONResolverTest {
    private final WebServer web = mock(WebServer.class);
    private final Identifiers identifiers = mock(Identifiers.class);
    private final StoreAnalyticsSvc service = mock(StoreAnalyticsSvc.class);
    private final StoreJSONResolver resolver = new StoreJSONResolver(identifiers,service,() -> web);
    private Request request(String... grants) {
        return new Request("GET","/v1/store?server=1",new WebUser("staff",UUID.randomUUID(),"staff",List.of(grants)),Map.of());
    }
    @Test void publicModeRejectsEvenExplicitPermissionsWhenResolveCalledDirectly() {
        when(web.isAuthRequired()).thenReturn(false);
        assertEquals(403,resolver.resolve(request("page.server.store","access.server")).orElseThrow().getCode());
        verifyNoInteractions(service);
    }
    @Test void anonymousSelfAndParentPagePermissionsDoNotGrantReferralAccess() {
        when(web.isAuthRequired()).thenReturn(true);
        for (Request req : List.of(request("page","access.server"),request("page.server","access.server"),request("page.player","access.player.self"),
                new Request("GET","/v1/store?server=1",null,Map.of()))) {
            assertEquals(403,resolver.resolve(req).orElseThrow().getCode());
        }
        verifyNoInteractions(service);
    }
    @Test void explicitReferralPermissionStillRequiresServerAccess() {
        when(web.isAuthRequired()).thenReturn(true);
        assertEquals(403,resolver.resolve(request("page.server.store")).orElseThrow().getCode());
    }
    @Test void authenticatedResponseKeepsNullsPrivateAndCorrectServerScope() {
        when(web.isAuthRequired()).thenReturn(true); UUID server = UUID.randomUUID();
        when(identifiers.getServerUUID(any(Request.class))).thenReturn(ServerUUID.from(server));
        Map<String,Object> output = new HashMap<>(); output.put("rate",null);
        when(service.report(server,30,null)).thenReturn(output);
        var response = resolver.resolve(request("page.server.store","access.server")).orElseThrow();
        assertEquals(200,response.getCode()); assertEquals("{\"rate\":null}",response.getAsString());
        assertEquals("private, no-store",response.getHeaders().get("Cache-Control"));
        verify(service).report(server,30,null);
    }
    @Test void unknownServerCannotFallBackToAnotherServersCachedData() {
        when(web.isAuthRequired()).thenReturn(true); UUID server = UUID.randomUUID();
        when(identifiers.getServerUUID(any(Request.class))).thenReturn(ServerUUID.from(server));
        when(service.report(server,30,null)).thenThrow(new IllegalArgumentException());
        assertEquals(400,resolver.resolve(request("page.server.store","access.server")).orElseThrow().getCode());
    }
    @Test void collectionFailureDoesNotReturnConvincingZeroMeasurements() {
        when(web.isAuthRequired()).thenReturn(true); UUID server = UUID.randomUUID();
        when(identifiers.getServerUUID(any(Request.class))).thenReturn(ServerUUID.from(server));
        when(service.report(server,30,null)).thenThrow(new IllegalStateException());
        assertEquals(503,resolver.resolve(request("page.server.store","access.server")).orElseThrow().getCode());
    }
}
