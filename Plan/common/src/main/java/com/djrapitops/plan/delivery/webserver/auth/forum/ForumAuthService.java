package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.delivery.domain.auth.User;
import com.djrapitops.plan.settings.forumauth.ForumAuthConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.playeranalytics.plugin.server.PluginLogger;
import org.apache.commons.codec.digest.DigestUtils;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;

/** Forum identity and sessions never enter Plan's local password or group tables. */
@Singleton
public final class ForumAuthService {
    public static final String COOKIE_PREFIX = "forum1_";
    public static final String TRANSACTION_COOKIE = "__Host-plan-login";
    public static final List<String> SELF_PERMISSIONS = List.of("access.player.self", "page.player.overview",
            "page.player.sessions", "page.player.versus", "page.player.servers", "page.player.statistics", "page.player.plugins");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LOGIN_MILLIS = 300_000;
    private final Cache<String, Pending> pending = Caffeine.newBuilder().maximumSize(1024)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, Checked> checked = Caffeine.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(15)).build();
    // Guarded by this. Never evict a live revocation: cache pressure cannot revive a saved cookie.
    // Only existing sessions can add entries; exhausting the bound pauses all forum authentication.
    static final int MAX_REVOCATIONS = 10000;
    private final Map<String, Long> revoked = new HashMap<>();
    private final File folder;
    private final ForumSessionStore sessions;
    private final PluginLogger logger;
    private final Clock clock;
    private final int revocationLimit;
    private volatile Generation generation;
    // Guarded by this: a failed pause must finish revoking saved sessions before re-enabling.
    private boolean invalidationRequired;

    private record Generation(ForumAuthConfig config, ForumBroker broker, String configHash) {}
    private record Pending(String browserHash, String verifier, long started, Generation generation) {}
    private record Checked(ForumIdentity identity, long until, boolean accepted, boolean revoked, Generation generation) {}
    public record LoginStart(String redirect, String browserCookie) {}
    public record LoginResult(String cookie, String playerPath, int maxAge) {}

    @Inject
    public ForumAuthService(@Named("dataFolder") File folder, DatabaseForumSessionStore sessions, PluginLogger logger) {
        this(folder, sessions, logger, Clock.systemUTC());
    }

    ForumAuthService(File folder, ForumSessionStore sessions, PluginLogger logger, Clock clock) {
        this(folder, sessions, logger, clock, MAX_REVOCATIONS);
    }

    ForumAuthService(File folder, ForumSessionStore sessions, PluginLogger logger, Clock clock, int revocationLimit) {
        if (revocationLimit < 1 || revocationLimit > MAX_REVOCATIONS) throw new IllegalArgumentException("Invalid revocation bound");
        this.folder = folder;
        this.sessions = sessions;
        this.logger = logger;
        this.clock = clock;
        this.revocationLimit = revocationLimit;
    }

    ForumAuthService(ForumAuthConfig config, ForumBroker broker, ForumSessionStore sessions, Clock clock) {
        this.folder = null;
        this.logger = null;
        this.sessions = sessions;
        this.clock = clock;
        this.revocationLimit = MAX_REVOCATIONS;
        configure(config, broker);
    }

    public synchronized void initialize(boolean authenticationRequired) {
        try {
            // Pause and revoke before parsing: an invalid file cannot bypass Plan's auth boundary.
            if (!authenticationRequired) pauseAndInvalidate();
            ForumAuthConfig candidate = ForumAuthConfig.load(folder.toPath().resolve("forum-auth.yml"));
            if (!authenticationRequired) {
                logger.info("Forum sign-in config: supported=" + ForumAuthConfig.CURRENT_VERSION
                        + ", installed=" + candidate.getInstalledVersion() + ", state=paused (Plan web authentication disabled)");
                return;
            }
            if (candidate.isEnabled()) {
                finishInvalidation();
                configure(candidate, new HttpForumBroker(candidate));
            } else {
                pauseAndInvalidate();
            }
            logger.info("Forum sign-in config: supported=" + ForumAuthConfig.CURRENT_VERSION
                    + ", installed=" + candidate.getInstalledVersion() + ", state=" + candidate.getState()
                    + ", enabled=" + candidate.isEnabled());
        } catch (IOException invalid) {
            // Loader diagnostics are sanitized. Storage exception text may contain private details.
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("forum-auth.yml blocked")) {
                logger.warn(invalid.getMessage() + "; previous settings retained when Plan authentication permits.");
            } else {
                logger.warn("Forum sign-in is paused: saved sessions could not be invalidated. Check session storage before enabling.");
            }
        } catch (RuntimeException invalid) {
            // Never log arbitrary client or parser exception values.
            logger.warn("Forum sign-in configuration could not be loaded; check forum-auth.yml. Previous settings retained if available.");
        }
    }

    synchronized void configure(ForumAuthConfig candidate, ForumBroker candidateBroker) {
        if (invalidationRequired) throw new IllegalStateException("Forum session invalidation must complete before enabling");
        String configHash = hash(candidate.getForumUrl() + "\n" + candidate.getClientId() + "\n" + candidate.getClientSecret()
                + "\n" + candidate.getCallbackUrl() + "\n" + candidate.getSessionSeconds() + "\n" + candidate.getRecheckSeconds());
        Generation next = new Generation(candidate, candidateBroker, configHash);
        generation = next;
        pending.invalidateAll();
        checked.invalidateAll();
    }

    private void pauseAndInvalidate() throws IOException {
        generation = null;
        pending.invalidateAll();
        checked.invalidateAll();
        invalidationRequired = true;
        finishInvalidation();
    }

    private void finishInvalidation() throws IOException {
        if (!invalidationRequired) return;
        sessions.removeAll();
        revoked.clear();
        invalidationRequired = false;
    }

    public boolean isEnabled() {
        Generation active = generation;
        return active != null && active.config().isEnabled();
    }

    public LoginStart begin() throws IOException {
        Generation active = requireEnabled();
        ForumAuthConfig config = active.config();
        String state = randomToken();
        String browser = randomToken();
        String verifier = randomToken();
        String redirect = config.getAuthorizeUrl() + "?client_id=" + encode(config.getClientId())
                + "&redirect_uri=" + encode(config.getCallbackUrl()) + "&state=" + state
                + "&code_challenge=" + challenge(verifier) + "&code_challenge_method=S256";
        synchronized (this) {
            requireCurrent(active);
            pending.put(hash(state), new Pending(hash(browser), verifier, clock.millis(), active));
            return new LoginStart(redirect, browser);
        }
    }

    public LoginResult complete(String code, String state, String browser) throws IOException {
        Generation active = requireEnabled();
        ForumAuthConfig config = active.config();
        if (!token(state) || !token(browser) || code == null || !code.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IOException("Invalid or expired forum login");
        }
        String key = hash(state);
        Pending login = pending.getIfPresent(key);
        long now = clock.millis();
        if (login == null || login.generation() != active || now - login.started() >= LOGIN_MILLIS || now < login.started()
                || !MessageDigest.isEqual(login.browserHash().getBytes(StandardCharsets.US_ASCII), hash(browser).getBytes(StandardCharsets.US_ASCII))
                || !pending.asMap().remove(key, login)) {
            throw new IOException("Invalid or expired forum login");
        }
        requireCurrent(active);
        ForumIdentity identity = active.broker().redeem(code, login.verifier(), state);
        requireCurrent(active);
        now = clock.millis();
        if (!config.getForumUrl().equals(identity.issuer()) || identity.authTime() > now / 1000 + 30
                || identity.authTime() < login.started() / 1000 - 30) {
            throw new IOException("Invalid forum authentication time or issuer");
        }
        int lifetime = Math.min(config.getSessionSeconds(), identity.expiresIn());
        long expires = Math.min(now + lifetime * 1000L, (identity.authTime() + lifetime) * 1000L);
        if (expires <= now) throw new IOException("Expired forum authentication");
        String cookie = COOKIE_PREFIX + randomToken();
        String cookieHash = hash(cookie);
        requireCurrent(active);
        sessions.save(cookieHash, new ForumSessionStore.Session(identity, active.configHash(), expires));
        synchronized (this) {
            if (generation != active) {
                sessions.remove(cookieHash);
                throw new IOException("Forum sign-in configuration changed; begin a new login");
            }
            long issuedAt = clock.millis();
            if (issuedAt >= expires || issuedAt < now) {
                markRevoked(cookieHash);
                sessions.remove(cookieHash);
                throw new IOException("Forum authentication expired before the session could be issued");
            }
            // A slow store must not reset the age of the broker's eligibility observation.
            long checkedUntil = Math.min(expires, now + recheckMillis(active, identity));
            checked.put(cookieHash, new Checked(identity, checkedUntil, true, false, active));
            return new LoginResult(cookie, "/", (int) Math.max(1, (expires - issuedAt) / 1000));
        }
    }

    public User authenticate(String cookie) {
        Generation active = generation;
        if (active == null || !active.config().isEnabled() || !isForumCookie(cookie)) return null;
        String key = hash(cookie);
        if (isRevoked(key)) return null;
        try {
            Optional<ForumSessionStore.Session> found = sessions.find(key);
            if (found.isEmpty() || generation != active) return null;
            ForumSessionStore.Session session = found.get();
            if (!active.configHash().equals(session.configHash()) || clock.millis() >= session.expires()) {
                checked.invalidate(key);
                sessions.remove(key);
                return null;
            }
            Checked current = checked.asMap().compute(key, (ignored, previous) -> {
                long now = clock.millis();
                if (previous != null && previous.generation() == active && previous.identity().sameAccount(session.identity())
                        && (previous.revoked() || now < previous.until())) return previous;
                try {
                    requireCurrent(active);
                    ForumIdentity fresh = active.broker().check(session.identity());
                    boolean valid = session.identity().sameAccount(fresh);
                    return new Checked(session.identity(), now + recheckMillis(active, fresh), valid, !valid, active);
                } catch (ForumVerificationRefusedException refused) {
                    return new Checked(session.identity(), session.expires(), false, true, active);
                } catch (IOException | RuntimeException unavailable) {
                    // Unknown eligibility cannot authorize a request; allow a bounded retry.
                    return new Checked(session.identity(), now + 5000, false, false, active);
                }
            });
            if (current.revoked()) {
                markRevoked(key);
                sessions.remove(key);
                return null;
            }
            if (!current.accepted()) return null;
            ForumIdentity identity = session.identity();
            String username = "forum:" + hash(identity.issuer()).substring(0, 12) + ":" + identity.subject();
            String playerName = sessions.playerName(identity.minecraftUUID());
            // Read current local authority on every request. Broker eligibility caches never cache
            // Plan permissions; exact UUID ownership is independent of mutable account/player names.
            ForumPermissions permissions = sessions.linkedPermissions(identity.minecraftUUID())
                    .orElseGet(() -> new ForumPermissions("forum-self", SELF_PERMISSIONS));
            synchronized (this) {
                if (generation != active || isRevoked(key) || !current.accepted()
                        || clock.millis() >= session.expires()) return null;
                if (!sessions.find(key).filter(session::equals).isPresent() || clock.millis() >= session.expires()) return null;
                return new ForumUser(username, playerName, identity, permissions);
            }
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    public void logout(String cookie) throws IOException {
        if (!isForumCookie(cookie)) return;
        String key = hash(cookie);
        // Do not let arbitrary nonexistent cookies fill the revocation cache.
        if (sessions.find(key).isEmpty()) return;
        markRevoked(key);
        sessions.remove(key);
    }

    private synchronized void markRevoked(String key) throws IOException {
        long now = clock.millis();
        revoked.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (!revoked.containsKey(key) && revoked.size() >= revocationLimit) {
            if (logger != null && generation != null)
                logger.warn("Forum sign-in paused: revocation capacity reached. Reload after session storage is healthy to invalidate saved sessions.");
            generation = null;
            pending.invalidateAll();
            checked.invalidateAll();
            invalidationRequired = true;
            throw new IOException("Forum sign-in paused: revocation capacity requires saved-session invalidation");
        }
        revoked.put(key, now + ForumAuthConfig.MAX_SESSION_SECONDS * 1000L);
        checked.invalidate(key);
    }

    private synchronized boolean isRevoked(String key) {
        Long until = revoked.get(key);
        if (until == null) return false;
        if (clock.millis() < until) return true;
        revoked.remove(key);
        return false;
    }

    private long recheckMillis(Generation active, ForumIdentity identity) {
        return Math.min(active.config().getRecheckSeconds(), identity.checkAfter()) * 1000L;
    }

    private Generation requireEnabled() throws IOException {
        Generation active = generation;
        if (active == null || !active.config().isEnabled()) throw new IOException("Forum sign-in is unavailable");
        return active;
    }

    private void requireCurrent(Generation active) throws IOException {
        if (generation != active) throw new IOException("Forum sign-in configuration changed; begin a new login");
    }

    public static boolean isForumCookie(String cookie) {
        return cookie != null && cookie.startsWith(COOKIE_PREFIX) && token(cookie.substring(COOKIE_PREFIX.length()));
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(DigestUtils.sha256(verifier));
    }

    static String hash(String value) {
        return DigestUtils.sha256Hex(value);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
