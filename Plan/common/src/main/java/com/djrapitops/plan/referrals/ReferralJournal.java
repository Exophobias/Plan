package com.djrapitops.plan.referrals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;
import static com.djrapitops.plan.referrals.ReferralAnalyticsService.*;

/** Strict trusted-feed boundary. Validate the entire page before any durable mutation. */
public final class ReferralJournal {
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private ReferralJournal() { }
    public static void validate(Batch b, long now) {
        require(b != null && b.streamId.matches("[a-f0-9]{32}"), "Invalid stream");
        require(b.fromExclusive >= 0 && b.throughInclusive >= b.fromExclusive && b.highWatermark >= b.throughInclusive,
                "Invalid cursor");
        require(b.hasMore == (b.throughInclusive < b.highWatermark), "Invalid page boundary");
        require(!b.hasMore || !b.events.isEmpty(), "Empty incomplete page");
        require(b.sourceAsOf > 0 && b.sourceAsOf <= now + 120000 && b.captureStartedAt > 0
                && b.captureStartedAt <= b.sourceAsOf && b.events.size() <= 100, "Invalid coverage");
        long last = b.fromExclusive;
        for (Event event : b.events) {
            require(event != null && event.sequence == last + 1 && event.sequence <= b.throughInclusive
                    && event.recordedAt > 0 && event.recordedAt <= b.sourceAsOf
                    && (event.claim == null) != (event.award == null), "Invalid event");
            last = event.sequence;
            if (event.claim != null) validate(event.claim, b.sourceAsOf);
            else validate(event.award, b.sourceAsOf);
        }
        require(last == b.throughInclusive, "Incomplete page");
    }
    private static void validate(Claim c, long asOf) {
        require(c.claimId != null && c.claimId.matches("[a-f0-9]{32}") && c.newcomerUUID != null
                && !c.newcomerUUID.equals(new UUID(0, 0)), "Invalid claim identity");
        require(Set.of("verifying", "pending", "successful", "rejected", "expired").contains(c.status), "Invalid claim status");
        require(c.requestedAt > 0 && c.requestedAt <= asOf && c.acceptedAt >= 0 && c.acceptedAt <= asOf
                && c.qualifiedAt >= 0 && c.qualifiedAt <= asOf && (c.expiresAt == 0 || c.expiresAt >= c.requestedAt),
                "Invalid claim times");
        require((c.acceptedAt == 0 || c.acceptedAt >= c.requestedAt)
                && (c.qualifiedAt == 0 || c.qualifiedAt >= c.requestedAt), "Invalid event order");
    }
    private static void validate(Award a, long asOf) {
        require(a.awardId != null && a.awardId.matches("[A-Za-z0-9_-]{1,96}") && a.claimId != null
                && a.claimId.matches("[a-f0-9]{32}"), "Invalid award identity");
        require(Set.of("credit", "referrer", "newcomer").contains(a.kind)
                && Set.of("pending", "delivered").contains(a.status), "Invalid award status");
        require(a.amountMinor >= 0 && a.amountMinor <= 1000000000000L
                && ("credit".equals(a.kind) ? a.currency != null && a.currency.matches("[A-Z]{3}") : a.currency == null), "Invalid award amount");
        require(a.createdAt > 0 && a.createdAt <= asOf && a.deliveredAt >= 0 && a.deliveredAt <= asOf
                && a.issuedAt >= 0 && a.issuedAt <= asOf && (a.issuedAt == 0 || "credit".equals(a.kind)
                && a.deliveredAt > 0 && a.issuedAt >= a.createdAt && a.issuedAt <= a.deliveredAt)
                && (a.deliveredAt == 0 || a.deliveredAt >= a.createdAt)
                && ("delivered".equals(a.status) == (a.deliveredAt > 0)), "Invalid delivery");
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    public static final class Apply extends ReferralTables.Tx {
        private final String server;
        private final Batch batch;
        private final long received;
        public Apply(UUID server, Batch batch, long received) {
            validate(batch, received);
            this.server = server.toString(); this.batch = batch; this.received = received;
        }
        @Override protected void performReferralOperations() {
            Checkpoint previous = one("SELECT stream_id,cursor_value FROM " + ReferralTables.FEED + " WHERE server_uuid=?" + lockForUpdate(),
                    r -> new Checkpoint(r.getString(1), r.getLong(2)), new Checkpoint(null, 0), server);
            require(previous.streamId == null || previous.streamId.equals(batch.streamId), "Referral stream changed; continuity review required");
            require(previous.sequence == batch.fromExclusive, "Referral cursor changed; reread checkpoint");
            long capture = one("SELECT capture_started FROM " + ReferralTables.FEED + " WHERE server_uuid=?", r -> r.getLong(1), batch.captureStartedAt, server);
            require(capture == batch.captureStartedAt, "Referral capture epoch changed");
            Map<String, Claim> claims = new HashMap<>(); Map<String, Award> awards = new HashMap<>();
            Set<String> touched = new HashSet<>(), erasedClaims = new HashSet<>();
            for (Event event : batch.events) {
                String claimId = event.claim != null ? event.claim.claimId : event.award.claimId;
                touched.add("c:" + claimId);
                if (event.award != null) touched.add("a:" + event.award.awardId);
                if (deleted("claim:" + claimId) || event.claim != null && deleted(event.claim.newcomerUUID.toString())) erasedClaims.add(claimId);
            }
            for (String key : touched) {
                Event existing = latest(key);
                if (existing != null) {
                    if (existing.claim != null) claims.put(existing.claim.claimId,existing.claim);
                    else awards.put(existing.award.awardId,existing.award);
                }
            }
            Map<UUID,String> newcomerClaims = new HashMap<>();
            for (Event event : batch.events) {
                if (event.claim != null) {
                    Claim c = event.claim, old = claims.get(c.claimId);
                    if (erasedClaims.contains(c.claimId)) continue;
                    String used = newcomerClaims.computeIfAbsent(c.newcomerUUID, player -> one("SELECT entity_key FROM " + ReferralTables.LATEST
                            + " WHERE server_uuid=? AND newcomer_uuid=?", r -> r.getString(1), "c:" + c.claimId, server, player.toString()));
                    require(used.equals("c:" + c.claimId), "Newcomer already belongs to a different claim");
                    require(old == null || old.newcomerUUID.equals(c.newcomerUUID) && old.requestedAt == c.requestedAt
                            && old.initialAttributionKnown == c.initialAttributionKnown && old.legacy == c.legacy
                            && (old.acceptedAt == 0 || old.acceptedAt == c.acceptedAt)
                            && (old.qualifiedAt == 0 || old.qualifiedAt == c.qualifiedAt)
                            && (!Set.of("successful","rejected","expired").contains(old.status) || old.status.equals(c.status))
                            && (!old.attributionCorrected || c.attributionCorrected),
                            "Immutable claim identity changed");
                    claims.put(c.claimId, c);
                } else {
                    Award a = event.award, old = awards.get(a.awardId);
                    if (erasedClaims.contains(a.claimId)) continue;
                    require(claims.containsKey(a.claimId), "Award has no preceding claim");
                    require(old == null || old.claimId.equals(a.claimId) && old.kind.equals(a.kind)
                            && Objects.equals(old.currency, a.currency) && old.amountMinor == a.amountMinor
                            && old.createdAt == a.createdAt && (old.deliveredAt == 0 || old.deliveredAt == a.deliveredAt)
                            && (old.issuedAt == 0 || old.issuedAt == a.issuedAt),
                            "Immutable award changed");
                    awards.put(a.awardId, a);
                }
            }
            for (String erased : erasedClaims) if (!deleted("claim:" + erased)) sql("INSERT INTO " + ReferralTables.DELETED + " (uuid) VALUES (?)", "claim:" + erased);
            for (Event event : batch.events) {
                String claimId = event.claim != null ? event.claim.claimId : event.award.claimId;
                if (erasedClaims.contains(claimId)) continue;
                String key = event.claim != null ? "c:" + claimId : "a:" + event.award.awardId;
                String json = JSON.toJson(event);
                sql("INSERT INTO " + ReferralTables.EVENTS + " (server_uuid,sequence_value,record_json) VALUES (?,?,?)", server, event.sequence, json);
                if (latest(key) == null) sql("INSERT INTO " + ReferralTables.LATEST + " (server_uuid,entity_key,newcomer_uuid,record_json) VALUES (?,?,?,?)",
                        server,key,event.claim != null ? event.claim.newcomerUUID.toString() : null,json);
                else sql("UPDATE " + ReferralTables.LATEST + " SET record_json=? WHERE server_uuid=? AND entity_key=?",json,server,key);
            }
            long oldWatermark = one("SELECT source_as_of FROM " + ReferralTables.FEED + " WHERE server_uuid=?", r -> r.getLong(1), 0L, server);
            long sourceThrough = batch.hasMore ? oldWatermark : Math.max(oldWatermark, batch.sourceAsOf);
            if (previous.streamId == null) sql("INSERT INTO " + ReferralTables.FEED + " (server_uuid,stream_id,cursor_value,source_as_of,capture_started,received_at) VALUES (?,?,?,?,?,?)", server, batch.streamId, batch.throughInclusive, sourceThrough, batch.captureStartedAt, received);
            else sql("UPDATE " + ReferralTables.FEED + " SET cursor_value=?,source_as_of=?,received_at=? WHERE server_uuid=?", batch.throughInclusive, sourceThrough, received, server);
        }
        private Event latest(String key) {
            Event existing = one("SELECT record_json FROM " + ReferralTables.LATEST + " WHERE server_uuid=? AND entity_key=?", r -> JSON.fromJson(r.getString(1),Event.class),null,server,key);
            require(existing == null || key.equals(existing.claim != null ? "c:" + existing.claim.claimId : "a:" + existing.award.awardId),
                    "Entity identifier changed under database collation");
            return existing;
        }
    }
}
