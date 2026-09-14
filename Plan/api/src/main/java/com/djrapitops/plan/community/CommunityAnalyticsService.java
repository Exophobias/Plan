package com.djrapitops.plan.community;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionStage;

/** Trusted fixture isolation for general community activity, independent of referrals. */
public interface CommunityAnalyticsService {
    static CommunityAnalyticsService getInstance() {
        return Optional.ofNullable(Holder.service.get()).orElseThrow(
                () -> new IllegalStateException("Community analytics is not available"));
    }

    /** Immediate observation pause; fixture and recovery intervals remain unknown in reports. */
    void setCollectionPaused(boolean paused);

    /** Called only after the platform confirms a real server shutdown; await before pausing/closing storage. */
    CompletionStage<Void> prepareServerShutdown();

    final class Holder {
        private static final AtomicReference<CommunityAnalyticsService> service = new AtomicReference<>();
        private Holder() { }
        public static void set(CommunityAnalyticsService value) { service.set(value); }
    }
}
