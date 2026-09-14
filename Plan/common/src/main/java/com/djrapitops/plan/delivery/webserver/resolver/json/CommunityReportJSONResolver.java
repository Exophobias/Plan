package com.djrapitops.plan.delivery.webserver.resolver.json;

import com.djrapitops.plan.community.CommunityAnalyticsSvc;
import com.djrapitops.plan.delivery.web.resolver.*;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.webserver.http.WebServer;
import com.djrapitops.plan.identification.Identifiers;
import dagger.Lazy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Private generation endpoint. Exported images are separately reviewed by their operator. */
@Singleton
@Path("/v1/community-report")
public class CommunityReportJSONResolver implements Resolver {
    private final Identifiers identifiers;
    private final CommunityAnalyticsSvc analytics;
    private final Lazy<WebServer> webServer;
    @Inject public CommunityReportJSONResolver(Identifiers identifiers, CommunityAnalyticsSvc analytics, Lazy<WebServer> webServer) {
        this.identifiers = identifiers; this.analytics = analytics; this.webServer = webServer;
    }
    @Override public boolean canAccess(Request request) {
        return webServer.get().isAuthRequired() && request.getUser().filter(user ->
                user.getPermissions().contains("page.server.reports") && user.hasPermission("access.server")).isPresent();
    }
    @Override public Set<String> usedWebPermissions() { return Set.of("page.server.reports"); }
    @GET @Override public Optional<Response> resolve(Request request) {
        if (!canAccess(request)) return Optional.of(response(403, Map.of("error", "Reports require explicit authenticated permission")));
        if (!"GET".equals(request.getMethod())) return Optional.of(response(405, Map.of("error", "Method not allowed")));
        try {
            UUID server = identifiers.getServerUUID(request).asUUID();
            LocalDate start = date(request.getQuery().get("start").orElse(""));
            LocalDate end = date(request.getQuery().get("end").orElse(""));
            return Optional.of(response(200, analytics.report(server, start, end)));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            return Optional.of(response(400, Map.of("error", "Invalid server or Vancouver date range (maximum 366 days)")));
        } catch (IllegalStateException | java.util.concurrent.CompletionException unavailable) {
            return Optional.of(response(503, Map.of("error", "Report unavailable; try again or select a shorter period")));
        }
    }
    private static LocalDate date(String value) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid date");
        return LocalDate.parse(value);
    }
    private static Response response(int status, Map<String, ?> content) {
        return Response.builder().setStatus(status).setMimeType(MimeType.JSON)
                .setJSONContent(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(content))
                .setHeader("Cache-Control", "private, no-store").setHeader("Vary", "Cookie, Authorization").build();
    }
}
