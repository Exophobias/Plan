package com.djrapitops.plan.referrals;

import com.djrapitops.plan.gathering.domain.FinishedSession;
import com.google.gson.Gson;
import java.util.UUID;

public final class ReferralSessionTransaction extends ReferralTables.Tx {
    private static final Gson JSON = new Gson();
    private final String server, player;
    private final long start, end;
    private final ReferralActivity.Snapshot snapshot;
    public ReferralSessionTransaction(FinishedSession session) {
        this(session.getServerUUID().asUUID(), session.getPlayerUUID(), session.getStart(), session.getEnd(),
                session.getExtraData(ReferralActivity.class).map(ReferralActivity::snapshot).orElse(null));
    }
    public ReferralSessionTransaction(UUID server, UUID player, long start, long end, ReferralActivity.Snapshot snapshot) {
        this.server = server.toString(); this.player = player.toString(); this.start = start; this.end = end; this.snapshot = snapshot;
    }
    @Override protected void performReferralOperations() {
        if (snapshot == null || deleted(player)) return;
        Long joined = one("SELECT first_join FROM " + ReferralTables.MEMBERS + " WHERE server_uuid=? AND uuid=?", r -> r.getLong(1), null, server, player);
        if (joined == null || start >= joined + 35 * ReferralReport.DAY) return;
        long horizon = joined + 35 * ReferralReport.DAY;
        long boundedEnd = Math.min(end,horizon);
        java.util.List<ReferralActivity.Interval> bounded = snapshot.intervals().stream()
                .filter(i -> i.start() < boundedEnd && i.end() > joined)
                .map(i -> new ReferralActivity.Interval(Math.max(joined, i.start()), Math.min(boundedEnd, i.end())))
                .collect(java.util.stream.Collectors.toList());
        Long previous = one("SELECT session_end FROM " + ReferralTables.ACTIVITY + " WHERE server_uuid=? AND uuid=? AND session_start=?", r -> r.getLong(1), null, server, player, start);
        if (previous != null && (previous > end || previous >= horizon)) return;
        if (previous == null) sql("INSERT INTO " + ReferralTables.ACTIVITY + " (server_uuid,uuid,session_start,session_end,valid,intervals) VALUES (?,?,?,?,?,?)", server, player, start, Math.min(end,horizon), snapshot.valid() ? 1 : 0, JSON.toJson(bounded));
        else sql("UPDATE " + ReferralTables.ACTIVITY + " SET session_end=?,valid=?,intervals=? WHERE server_uuid=? AND uuid=? AND session_start=?", Math.min(end,horizon), snapshot.valid() ? 1 : 0, JSON.toJson(bounded), server, player, start);
    }
}
