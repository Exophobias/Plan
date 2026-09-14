package com.djrapitops.plan.community;

import com.djrapitops.plan.delivery.web.ResolverSvc;
import com.djrapitops.plan.gathering.cache.SessionCache;
import com.djrapitops.plan.identification.ServerInfo;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.settings.config.paths.TimeSettings;
import com.djrapitops.plan.storage.database.DBSystem;
import com.djrapitops.plan.storage.database.Database;
import com.djrapitops.plan.storage.database.transactions.Transaction;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunityAnalyticsConcurrencyTest {
    @Test void cancelledQueuedShutdownReturnsImmediatelyAndCannotPauseOrPersistLater() throws Exception {
        DBSystem databases = mock(DBSystem.class);
        Database database = mock(Database.class);
        ServerInfo server = mock(ServerInfo.class);
        PlanConfig config = mock(PlanConfig.class);
        when(databases.getDatabase()).thenReturn(database);
        when(database.getState()).thenReturn(Database.State.OPEN);
        when(server.getServerUUID()).thenReturn(ServerUUID.from(UUID.randomUUID()));
        doReturn(180000L).when(config).get(TimeSettings.AFK_THRESHOLD);
        CommunityAnalyticsSvc service = new CommunityAnalyticsSvc(databases, server, config, mock(ResolverSvc.class));
        CountDownLatch queued = new CountDownLatch(1), release = new CountDownLatch(1);
        SessionCache.clear();
        service.enable();
        try {
            // Hold the actual collector queue, then prove that cancellation fences a delayed stop request.
            var field = CommunityAnalyticsSvc.class.getDeclaredField("executor");
            field.setAccessible(true);
            ScheduledExecutorService executor = (ScheduledExecutorService) field.get(service);
            executor.submit(() -> {
                queued.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(queued.await(5, TimeUnit.SECONDS));
            CompletionStage<Void> stop = assertTimeoutPreemptively(Duration.ofSeconds(1), service::prepareServerShutdown);
            assertTrue(stop.toCompletableFuture().cancel(false));
            release.countDown();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertTrue(stop.toCompletableFuture().isCancelled());
            assertFalse(CommunityCapture.state().paused());
            verify(database, never()).executeTransaction(any(Transaction.class));
        } finally {
            release.countDown(); service.disable(); SessionCache.clear();
        }
    }
}
