package com.djrapitops.plan.delivery.webserver.auth.forum;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public interface ForumSessionStore {
    record Session(ForumIdentity identity, String configHash, long expires) {}
    Optional<Session> find(String cookieHash) throws IOException;
    void save(String cookieHash, Session session) throws IOException;
    void remove(String cookieHash) throws IOException;
    void removeAll() throws IOException;
    default String playerName(UUID uuid) throws IOException { return uuid.toString(); }
    /** Empty means no linked local account; ambiguous or invalid mappings must throw. */
    default Optional<ForumPermissions> linkedPermissions(UUID uuid) throws IOException { return Optional.empty(); }
}
