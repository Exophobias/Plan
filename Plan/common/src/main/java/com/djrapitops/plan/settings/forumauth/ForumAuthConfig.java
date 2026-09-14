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
package com.djrapitops.plan.settings.forumauth;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

/** Immutable settings owned exclusively by the independent forum-auth.yml schema. */
public final class ForumAuthConfig {

    public static final int CURRENT_VERSION = 2;
    public static final int MAX_SESSION_SECONDS = 14 * 24 * 60 * 60;
    private final boolean enabled;
    private final String forumUrl;
    private final String clientId;
    private final String clientSecret;
    private final String callbackUrl;
    private final int sessionSeconds;
    private final int recheckSeconds;
    private final int timeoutSeconds;
    private final String state;

    ForumAuthConfig(Map<String, String> values, String state) throws IOException {
        enabled = switch (values.get("enabled")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("enabled must be a boolean");
        };
        URI forum = httpsUrl(values.get("forum-url"), "forum-url");
        if (!forum.getRawPath().isEmpty()) throw invalid("forum-url must be an HTTPS origin without a trailing slash");
        forumUrl = forum.toASCIIString();
        URI callback = httpsUrl(values.get("callback-url"), "callback-url");
        if (!"/auth/forum/callback".equals(callback.getRawPath())) {
            throw invalid("callback-url must use the /auth/forum/callback path");
        }
        callbackUrl = callback.toASCIIString();
        clientId = values.get("client-id");
        if (!clientId.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("client-id must be 1-64 letters, digits, underscores or hyphens");
        clientSecret = values.get("client-secret");
        if (!clientSecret.isEmpty() && !clientSecret.matches("[A-Za-z0-9_-]{43,128}")
                || enabled && clientSecret.isEmpty()) {
            throw invalid("client-secret must be 43-128 base64url characters, or blank when disabled");
        }
        sessionSeconds = bounded(values, "session-seconds", 1, MAX_SESSION_SECONDS);
        recheckSeconds = bounded(values, "recheck-seconds", 1, 60);
        timeoutSeconds = bounded(values, "timeout-seconds", 1, 10);
        this.state = state;
    }

    /**
     * Prepares and rechecks a complete snapshot before returning it. On failure callers must retain
     * their prior settings and clients; no mutable singleton is changed by this loader.
     */
    public static ForumAuthConfig load(Path configFile) throws IOException {
        return ForumAuthConfigLoader.load(configFile);
    }

    private static URI httpsUrl(String value, String key) throws IOException {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0
                    || uri.getPort() > 65535 || !uri.normalize().equals(uri)) {
                throw invalid(key + " must be a canonical HTTPS URL without credentials, query or fragment");
            }
            return uri;
        } catch (URISyntaxException ignored) {
            throw invalid(key + " must be a valid HTTPS URL");
        }
    }

    private static int bounded(Map<String, String> values, String key, int minimum, int maximum) throws IOException {
        try {
            int value = Integer.parseInt(values.get(key));
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // Sanitized below: parser exceptions can contain credentials from a malformed file.
        }
        throw invalid(key + " must be an integer from " + minimum + " to " + maximum);
    }

    static IOException invalid(String detail) {
        return new IOException("forum-auth.yml blocked (supported config-version " + CURRENT_VERSION + "): " + detail);
    }

    public boolean isEnabled() { return enabled; }
    public String getForumUrl() { return forumUrl; }
    public URI getAuthorizeUrl() { return URI.create(forumUrl + "/plan-auth/authorize"); }
    public URI getTokenUrl() { return URI.create(forumUrl + "/plan-auth/token"); }
    public URI getCheckUrl() { return URI.create(forumUrl + "/plan-auth/check"); }
    public String getClientId() { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public String getCallbackUrl() { return callbackUrl; }
    public int getSessionSeconds() { return sessionSeconds; }
    public int getRecheckSeconds() { return recheckSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getInstalledVersion() { return CURRENT_VERSION; }
    public String getState() { return state; }
}
