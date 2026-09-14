package com.djrapitops.plan.referrals;

/** Synchronous capture admission shared by AFK callbacks and the asynchronous collector. */
public final class ReferralCapture {
    private static volatile State state = new State(true, 0, 0);
    private ReferralCapture() { }
    public static State state() { return state; }
    public static synchronized void pause(boolean paused, long now) {
        if (state.paused != paused) state = new State(paused, now, state.generation + 1);
    }
    public record State(boolean paused, long changedAt, long generation) { }
}
