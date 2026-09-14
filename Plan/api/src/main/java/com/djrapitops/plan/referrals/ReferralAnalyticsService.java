package com.djrapitops.plan.referrals;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted, in-process referral journal ingestion. All times are UTC epoch milliseconds.
 * No web credentials, player addresses, codes or moderation details belong in this API.
 * Calls may block on storage; invoke them off the server thread. Completion means durable commit.
 */
public interface ReferralAnalyticsService {
    static ReferralAnalyticsService getInstance() {
        return Optional.ofNullable(Holder.service.get()).orElseThrow(
                () -> new IllegalStateException("Referral analytics is not available"));
    }

    Checkpoint getCheckpoint(UUID serverUUID);
    CompletionStage<Void> applyBatch(UUID serverUUID, Batch batch);

    /** Pauses prospective collection immediately; paused intervals are unknown, never backfilled. */
    void setCollectionPaused(boolean paused);

    /**
     * Called only by a trusted platform adapter after the server reports it is stopping, before
     * publication pause/adapter shutdown. Completion durably proves a clean stop; callers must await
     * it before allowing Plan storage to close. A plugin reload must never invoke this method.
     */
    CompletionStage<Void> prepareServerShutdown();

    final class Checkpoint {
        public final String streamId;
        public final long sequence;
        public Checkpoint(String streamId, long sequence) { this.streamId = streamId; this.sequence = sequence; }
    }

    final class Batch {
        public final String streamId;
        public final long fromExclusive, throughInclusive, highWatermark, sourceAsOf, captureStartedAt;
        public final boolean hasMore;
        public final List<Event> events;
        public Batch(String streamId, long fromExclusive, long throughInclusive, long highWatermark,
                     long sourceAsOf, long captureStartedAt, boolean hasMore, List<Event> events) {
            this.streamId = Objects.requireNonNull(streamId);
            this.fromExclusive = fromExclusive; this.throughInclusive = throughInclusive;
            this.highWatermark = highWatermark; this.sourceAsOf = sourceAsOf;
            this.captureStartedAt = captureStartedAt; this.hasMore = hasMore;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    final class Event {
        public final long sequence, recordedAt;
        public final Claim claim;
        public final Award award;
        public Event(long sequence, long recordedAt, Claim claim, Award award) {
            this.sequence = sequence; this.recordedAt = recordedAt; this.claim = claim; this.award = award;
        }
    }

    final class Claim {
        public final String claimId, status;
        public final UUID newcomerUUID;
        public final long requestedAt, acceptedAt, qualifiedAt, expiresAt;
        public final boolean initialAttributionKnown, legacy, attributionCorrected;
        public Claim(String claimId, UUID newcomerUUID, String status, long requestedAt, long acceptedAt,
                     long qualifiedAt, long expiresAt, boolean initialAttributionKnown, boolean legacy,
                     boolean attributionCorrected) {
            this.claimId = claimId; this.newcomerUUID = newcomerUUID; this.status = status;
            this.requestedAt = requestedAt; this.acceptedAt = acceptedAt; this.qualifiedAt = qualifiedAt;
            this.expiresAt = expiresAt; this.initialAttributionKnown = initialAttributionKnown;
            this.legacy = legacy; this.attributionCorrected = attributionCorrected;
        }
    }

    final class Award {
        public final String awardId, claimId, kind, currency, status;
        public final long amountMinor, createdAt, deliveredAt, issuedAt;
        /** credit amounts are currency minor units; referrer/newcomer amounts are raffle entries. */
        public Award(String awardId, String claimId, String kind, String currency, long amountMinor,
                     long createdAt, long deliveredAt, long issuedAt, String status) {
            this.awardId = awardId; this.claimId = claimId; this.kind = kind; this.currency = currency;
            this.amountMinor = amountMinor; this.createdAt = createdAt; this.deliveredAt = deliveredAt;
            this.status = status;
            this.issuedAt = issuedAt;
        }
    }

    final class Holder {
        static final AtomicReference<ReferralAnalyticsService> service = new AtomicReference<>();
        private Holder() { }
        static void set(ReferralAnalyticsService instance) { service.set(instance); }
    }
}
