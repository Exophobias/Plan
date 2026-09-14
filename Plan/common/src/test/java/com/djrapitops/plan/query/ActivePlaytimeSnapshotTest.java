package com.djrapitops.plan.query;

import com.djrapitops.plan.gathering.cache.SessionCache;
import com.djrapitops.plan.gathering.domain.ActiveSession;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.storage.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActivePlaytimeSnapshotTest {
    @AfterEach void reset() { SessionCache.clear(); }
    @Test void logoutAndCommitBetweenReadsRetryWithoutLosingOrDuplicatingTheSession() {
        UUID player = UUID.randomUUID(), serverId = UUID.randomUUID();
        ServerUUID server = ServerUUID.from(serverId);
        long now = System.currentTimeMillis();
        SessionCache cache = new SessionCache();
        ActiveSession active = new ActiveSession(player, server, now - 10000, "world", "SURVIVAL");
        active.setLastMovementForAfkCalculation(now);
        cache.cacheSession(player, active);
        Database database = mock(Database.class);
        AtomicInteger reads = new AtomicInteger();
        when(database.query(any())).thenAnswer(call -> {
            if (reads.getAndIncrement() == 0) {
                var ended = cache.endSession(player, now).orElseThrow();
                SessionCache.activityStored(ended);
                return 0L;
            }
            return 10000L;
        });
        var api = new CommonQueriesImplementation(database, mock(PlanConfig.class));
        var result = api.fetchActivePlaytimeSnapshot(player, serverId);
        assertEquals(10000, result.activePlaytime());
        assertFalse(result.online());
        assertEquals(2, reads.get());
    }
    @Test void repeatedSessionTransitionsAndDatabaseFailureNeverProduceAnAvailableZero() {
        UUID player = UUID.randomUUID(), serverId = UUID.randomUUID();
        Database database = mock(Database.class);
        SessionCache cache = new SessionCache();
        when(database.query(any())).thenAnswer(call -> {
            cache.cacheSession(player, new ActiveSession(player, ServerUUID.from(serverId),
                    System.currentTimeMillis(), "world", "SURVIVAL"));
            return 0L;
        });
        var api = new CommonQueriesImplementation(database, mock(PlanConfig.class));
        assertThrows(IllegalStateException.class, () -> api.fetchActivePlaytimeSnapshot(player, serverId));
        org.mockito.Mockito.reset(database);
        when(database.query(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> api.fetchActivePlaytimeSnapshot(player, serverId));
    }
}
