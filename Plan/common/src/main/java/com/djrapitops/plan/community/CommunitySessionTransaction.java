package com.djrapitops.plan.community;

import com.djrapitops.plan.gathering.domain.FinishedSession;
import com.google.gson.Gson;
import java.util.List;
import java.util.UUID;

/** Upserts one coherent observed session without borrowing old Bukkit registration dates. */
public final class CommunitySessionTransaction extends CommunityTables.Tx {
    private static final Gson JSON = new Gson();
    private final String server, player;
    private final long start, end;
    private final CommunityActivity.Snapshot snapshot;

    public CommunitySessionTransaction(FinishedSession session) {
        this(session, capture(session));
    }

    private CommunitySessionTransaction(FinishedSession session, Captured captured) {
        this(session.getServerUUID().asUUID(), session.getPlayerUUID(), session.getStart(), captured.end(), captured.activity());
    }

    private static Captured capture(FinishedSession session) {
        CommunityCapture.State gate = CommunityCapture.state();
        // A later logout must not extend a session into a fixture pause or certified server downtime.
        long end = gate.paused() ? Math.min(session.getEnd(), gate.changedAt()) : session.getEnd();
        return new Captured(end, session.getExtraData(CommunityActivity.class).map(a -> a.snapshot(end, gate)).orElse(null));
    }

    private record Captured(long end, CommunityActivity.Snapshot activity) { }

    public CommunitySessionTransaction(UUID server, UUID player, long start, long end, CommunityActivity.Snapshot snapshot) {
        this.server = server.toString(); this.player = player.toString();
        this.start = start; this.end = end; this.snapshot = snapshot;
    }

    @Override protected void performCommunityOperations() {
        if (snapshot == null || snapshot.firstObserved() <= 0 || deleted(player)) return;
        if (start <= 0 || end < start || snapshot.firstObserved() < start || snapshot.firstObserved() > end)
            throw new IllegalArgumentException("Invalid community session boundary");
        if (snapshot.intervals().size() > 16384) throw new IllegalArgumentException("Community interval capacity exceeded");
        List<CommunityActivity.Interval> bounded = snapshot.intervals().stream()
                .filter(i -> i.end() > snapshot.firstObserved() && i.start() < end)
                .map(i -> new CommunityActivity.Interval(Math.max(snapshot.firstObserved(), i.start()), Math.min(end, i.end())))
                .toList();
        long previousEnd = snapshot.firstObserved();
        for (CommunityActivity.Interval interval : bounded) {
            if (interval.start() < previousEnd || interval.end() <= interval.start())
                throw new IllegalArgumentException("Invalid community activity interval");
            previousEnd = interval.end();
        }
        Long firstSeen = one("SELECT first_seen FROM " + CommunityTables.MEMBERS + " WHERE server_uuid=? AND uuid=?", r -> r.getLong(1), null, server, player);
        if (firstSeen == null) {
            sql("INSERT INTO " + CommunityTables.MEMBERS + " (server_uuid,uuid,first_seen) VALUES (?,?,?)", server, player, snapshot.firstObserved());
        } else if (snapshot.firstObserved() < firstSeen)
            sql("UPDATE " + CommunityTables.MEMBERS + " SET first_seen=? WHERE server_uuid=? AND uuid=?", snapshot.firstObserved(), server, player);
        Long oldEnd = one("SELECT session_end FROM " + CommunityTables.ACTIVITY + " WHERE server_uuid=? AND uuid=? AND session_start=?", r -> r.getLong(1), null, server, player, start);
        if (oldEnd != null && oldEnd > end) return;
        String encoded = JSON.toJson(bounded);
        if (oldEnd == null) {
            sql("INSERT INTO " + CommunityTables.ACTIVITY + " (server_uuid,uuid,session_start,session_end,valid,intervals) VALUES (?,?,?,?,?,?)", server, player, start, end, snapshot.valid() ? 1 : 0, encoded);
        } else sql("UPDATE " + CommunityTables.ACTIVITY + " SET session_end=?,valid=?,intervals=? WHERE server_uuid=? AND uuid=? AND session_start=?", end, snapshot.valid() ? 1 : 0, encoded, server, player, start);
    }
}
