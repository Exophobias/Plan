package com.djrapitops.plan.delivery.web.resolver.request;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Keeps browser credentials and short-lived authorization grants out of diagnostics. */
public final class RequestLogSanitizer {
    private RequestLogSanitizer() {}

    public static String uri(String uri) {
        if (uri == null) return "non-HTTP request, missing URI";
        int query = uri.indexOf('?');
        if (query >= 0 && uri.substring(0, query).contains("/auth/")) return uri.substring(0, query);
        return uri;
    }

    public static Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            safe.put(key, lower.equals("cookie") || lower.equals("authorization") || lower.equals("proxy-authorization")
                    || lower.equals("set-cookie") ? "[redacted]" : value);
        });
        return safe;
    }
}
