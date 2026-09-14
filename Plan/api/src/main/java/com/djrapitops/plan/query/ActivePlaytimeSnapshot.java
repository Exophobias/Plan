package com.djrapitops.plan.query;

/** Immutable observation. Durations and observedAt are milliseconds; UUIDs are supplied to the query. */
public final class ActivePlaytimeSnapshot {
    private final long activePlaytime;
    private final long observedAt;
    private final boolean online;
    public ActivePlaytimeSnapshot(long activePlaytime, long observedAt, boolean online) {
        if (activePlaytime < 0 || observedAt <= 0) throw new IllegalArgumentException("Invalid activity snapshot");
        this.activePlaytime = activePlaytime;
        this.observedAt = observedAt;
        this.online = online;
    }
    public long activePlaytime() { return activePlaytime; }
    public long observedAt() { return observedAt; }
    public boolean online() { return online; }
}
