package com.djrapitops.plan.delivery.webserver.resolver.auth;

import com.djrapitops.plan.delivery.web.resolver.NoAuthResolver;
import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.web.resolver.ResponseBuilder;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.webserver.auth.forum.ForumAuthService;
import jakarta.ws.rs.GET;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@Singleton
public final class ForumLoginResolver implements NoAuthResolver {
    private final ForumAuthService service;

    @Inject
    public ForumLoginResolver(ForumAuthService service) {
        this.service = service;
    }

    @GET
    @Override
    public Optional<Response> resolve(Request request) {
        if (!"GET".equals(request.getMethod())) return Optional.of(safe().setStatus(405).setHeader("Allow", "GET").build());
        String path = request.getPath().asString();
        if ("/auth/forum/status".equals(path)) {
            return Optional.of(safe().setJSONContent(Map.of("enabled", service.isEnabled())).build());
        }
        if (!service.isEnabled()) return Optional.of(safe().setStatus(503).setContent("Forum sign-in is unavailable.").build());
        try {
            if ("/auth/forum/start".equals(path)) {
                ForumAuthService.LoginStart login = service.begin();
                return Optional.of(safe().redirectTo(login.redirect()).setHeader("Set-Cookie",
                        ForumAuthService.TRANSACTION_COOKIE + "=" + login.browserCookie()
                                + "; Path=/; Max-Age=300; SameSite=Lax; Secure; HttpOnly").build());
            }
            if ("/auth/forum/callback".equals(path)) {
                ForumAuthService.LoginResult result = service.complete(request.getQuery().get("code").orElse(null),
                        request.getQuery().get("state").orElse(null), cookie(request, ForumAuthService.TRANSACTION_COOKIE));
                return Optional.of(safe().redirectTo(result.playerPath()).setHeader("Set-Cookie", "auth=" + result.cookie()
                        + "; Path=/; Max-Age=" + result.maxAge() + "; SameSite=Lax; Secure; HttpOnly").build());
            }
            return Optional.empty();
        } catch (IOException unavailable) {
            return Optional.of(safe().redirectTo("/login?forumError=1").build());
        }
    }

    private static ResponseBuilder safe() {
        return Response.builder().setMimeType("text/plain; charset=utf-8").setContent("").setHeader("Cache-Control", "no-store").setHeader("Pragma", "no-cache")
                .setHeader("Referrer-Policy", "no-referrer");
    }

    static String cookie(Request request, String name) {
        String result = null;
        String raw = request.getHeader("Cookie").orElseGet(() -> request.getHeader("cookie").orElse(""));
        for (String part : raw.split(";")) {
            String[] entry = part.trim().split("=", 2);
            if (entry.length == 2 && name.equals(entry[0])) {
                if (result != null) return null;
                result = entry[1];
            }
        }
        return result;
    }
}
