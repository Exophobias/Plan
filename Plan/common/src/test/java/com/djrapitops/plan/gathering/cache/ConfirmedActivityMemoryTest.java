package com.djrapitops.plan.gathering.cache;

import com.djrapitops.plan.gathering.domain.ActiveSession;
import com.djrapitops.plan.identification.ServerUUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedActivityMemoryTest {
    @AfterEach void reset() { SessionCache.clear(); }
    @Test void logoutKeepsTheExactSessionUntilSuccessfulPersistenceAndInvalidatesOldObservations() {
        UUID player = UUID.randomUUID();
        ServerUUID server = ServerUUID.from(UUID.randomUUID());
        SessionCache cache = new SessionCache();
        ActiveSession session = new ActiveSession(player, server, 1000, "world", "SURVIVAL");
        session.setLastMovementForAfkCalculation(9000);
        session.addAfkTime(2000);
        cache.cacheSession(player, session);
        var online = SessionCache.activityMemory(player, server, 10000);
        assertEquals(6000L, online.sessions().get(1000L));
        assertTrue(online.online());
        var ended = cache.endSession(player, 10000).orElseThrow();
        assertFalse(SessionCache.activityUnchanged(player, online));
        var pending = SessionCache.activityMemory(player, server, 11000);
        assertFalse(pending.online());
        assertEquals(7000L, pending.sessions().get(1000L));
        SessionCache.activityStored(ended);
        assertFalse(SessionCache.activityUnchanged(player, pending));
        assertTrue(SessionCache.activityMemory(player, server, 12000).sessions().isEmpty());
    }
    @Test void confirmedActivityWithholdsIdleTimeAndCannotBecomeNegative() {
        ActiveSession session = new ActiveSession(UUID.randomUUID(), ServerUUID.from(UUID.randomUUID()), 1000, "world", "SURVIVAL");
        assertEquals(0, session.confirmedActiveTime(10000));
        session.setLastMovementForAfkCalculation(4000);
        assertEquals(3000, session.confirmedActiveTime(10000));
        session.addAfkTime(5000);
        assertEquals(0, session.confirmedActiveTime(10000));
    }
    @Test void aNewSessionCannotHideAnUnstoredPreviousSession() {
        UUID player = UUID.randomUUID();
        ServerUUID server = ServerUUID.from(UUID.randomUUID());
        SessionCache cache = new SessionCache();
        cache.cacheSession(player, new ActiveSession(player, server, 1000, "world", "SURVIVAL"));
        cache.cacheSession(player, new ActiveSession(player, server, 10000, "world", "SURVIVAL"));
        var snapshot = SessionCache.activityMemory(player, server, 11000);
        assertEquals(2, snapshot.sessions().size());
        assertEquals(9000L, snapshot.sessions().get(1000L));
    }
}
