package com.djrapitops.plan.referrals;

import java.util.ArrayList;
import java.util.List;

/** Prospective exact active intervals. Called under the owning ActiveSession monitor. */
public final class ReferralActivity {
    private static final int MAX_INTERVALS = 16384;
    private final List<Interval> intervals = new ArrayList<>();
    private boolean valid = true;
    private long classifiedThrough;
    public ReferralActivity(long sessionStart) { classifiedThrough = sessionStart; }

    public synchronized void classified(long end, boolean active) {
        if (end < classifiedThrough) { valid = false; return; }
        ReferralCapture.State gate = ReferralCapture.state();
        long start = Math.max(classifiedThrough, gate.changedAt());
        classifiedThrough = end;
        if (gate.paused() || !valid || !active || end <= start) return;
        if (!intervals.isEmpty() && intervals.get(intervals.size() - 1).end == start) {
            Interval previous = intervals.remove(intervals.size() - 1);
            intervals.add(new Interval(previous.start, end));
        } else if (intervals.size() < MAX_INTERVALS) {
            intervals.add(new Interval(start, end));
        } else valid = false;
    }

    public synchronized Snapshot snapshot() { return new Snapshot(valid, List.copyOf(intervals)); }
    public record Interval(long start, long end) { }
    public record Snapshot(boolean valid, List<Interval> intervals) { }
}
