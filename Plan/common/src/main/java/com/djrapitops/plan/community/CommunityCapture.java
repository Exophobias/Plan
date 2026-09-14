package com.djrapitops.plan.community;

/** Independent observation admission. Pauses represent unknown activity, never zero activity. */
public final class CommunityCapture {
    private static volatile State state = new State(true, 0, 0);
    private CommunityCapture() { }
    public static State state() { return state; }
    public static synchronized void pause(boolean paused, long now) {
        if (state.paused() != paused) state = new State(paused, now, state.generation() + 1);
    }
    public record State(boolean paused, long changedAt, long generation) { }
}
