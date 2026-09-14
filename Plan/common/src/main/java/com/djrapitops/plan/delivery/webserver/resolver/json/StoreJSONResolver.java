package com.djrapitops.plan.delivery.webserver.resolver.json;

import com.djrapitops.plan.delivery.web.resolver.*;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.webserver.http.WebServer;
import com.djrapitops.plan.identification.Identifiers;
import com.djrapitops.plan.store.StoreAnalyticsSvc;
import dagger.Lazy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/** Authenticated aggregate endpoint. Authorization is repeated in resolve because public mode skips canAccess. */
@Singleton
@Path("/v1/store")
public class StoreJSONResolver implements Resolver {
    private final Identifiers identifiers;
    private final StoreAnalyticsSvc analytics;
    private final Lazy<WebServer> webServer;
    @Inject public StoreJSONResolver(Identifiers identifiers, StoreAnalyticsSvc analytics, Lazy<WebServer> webServer) {
        this.identifiers = identifiers; this.analytics = analytics; this.webServer = webServer;
    }
    @Override public boolean canAccess(Request request) {
        return webServer.get().isAuthRequired() && request.getUser().filter(user ->
                user.getPermissions().contains("page.server.store") && user.hasPermission("access.server")).isPresent();
    }
    @Override public Set<String> usedWebPermissions() { return Set.of("page.server.store"); }
    @GET @Override public Optional<Response> resolve(Request request) {
        if (!canAccess(request)) return Optional.of(response(403, Map.of("error", "Store analytics requires an explicit authenticated permission")));
        if (!"GET".equals(request.getMethod())) return Optional.of(response(405, Map.of("error", "Method not allowed")));
        try {
            UUID server = identifiers.getServerUUID(request).asUUID();
            String daysText=request.getQuery().get("days").orElse("30");
            if (!Set.of("30","90").contains(daysText)) throw new IllegalArgumentException("Invalid period");
            String currency=request.getQuery().get("currency").orElse(null);
            return Optional.of(response(200, analytics.report(server,Integer.parseInt(daysText),currency)));
        }
        catch (IllegalArgumentException invalid) { return Optional.of(response(400, Map.of("error", "Invalid Store analytics filters or server"))); }
        catch (IllegalStateException unavailable) { return Optional.of(response(503, Map.of("error", "Store analytics is unavailable; no measurements were inferred"))); }
    }
    private static Response response(int status, Map<String,?> content) {
        return Response.builder().setStatus(status).setMimeType(MimeType.JSON)
                .setJSONContent(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(content))
                .setHeader("Cache-Control", "private, no-store").setHeader("Vary", "Cookie, Authorization").build();
    }
}
