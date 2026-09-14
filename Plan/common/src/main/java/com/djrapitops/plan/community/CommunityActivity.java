package com.djrapitops.plan.community;

import java.util.ArrayList;
import java.util.List;

/** Time-resolved Plan AFK decisions, independent of referral eligibility and player registration. */
public final class CommunityActivity {
    private static final int MAX_INTERVALS = 16384;
    private final long sessionStart;
    private final List<Interval> intervals = new ArrayList<>();
    private long classifiedThrough;
    private long firstObserved;
    private boolean valid = true;

    public CommunityActivity(long sessionStart) {
        this.sessionStart = sessionStart;
        classifiedThrough = sessionStart;
        observe(sessionStart, CommunityCapture.state());
    }

    private void observe(long now, CommunityCapture.State gate) {
        if (firstObserved == 0 && !gate.paused() && now >= gate.changedAt())
            firstObserved = Math.max(sessionStart, gate.changedAt());
    }

    public synchronized void classified(long end, boolean active) {
        if (end < classifiedThrough) { valid = false; return; }
        CommunityCapture.State gate = CommunityCapture.state();
        observe(end, gate);
        long start = Math.max(classifiedThrough, gate.changedAt());
        classifiedThrough = end;
        if (gate.paused() || !valid || !active || end <= start) return;
        if (!intervals.isEmpty() && intervals.get(intervals.size() - 1).end() == start) {
            Interval previous = intervals.remove(intervals.size() - 1);
            intervals.add(new Interval(previous.start(), end));
        } else if (intervals.size() < MAX_INTERVALS) intervals.add(new Interval(start, end));
        else valid = false;
    }

    public synchronized Snapshot snapshot(long observedAt) {
        return snapshot(observedAt, CommunityCapture.state());
    }

    synchronized Snapshot snapshot(long observedAt, CommunityCapture.State gate) {
        observe(observedAt, gate);
        return new Snapshot(firstObserved, valid, List.copyOf(intervals));
    }

    public record Interval(long start, long end) { }
    public record Snapshot(long firstObserved, boolean valid, List<Interval> intervals) { }
}
