/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.gathering.cache;

import com.djrapitops.plan.gathering.domain.ActiveSession;
import com.djrapitops.plan.gathering.domain.FinishedSession;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is used to store active sessions of players in memory.
 *
 * @author AuroraLS3
 */
@Singleton
public class SessionCache {

    private static final Map<UUID, ActiveSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static final Object ACTIVITY_LOCK = new Object();
    private static final Map<UUID, List<FinishedSession>> AWAITING_STORAGE = new HashMap<>();
    private static final Map<UUID, Long> ACTIVITY_VERSIONS = new HashMap<>();
    private static long activitySequence;

    private static void activityChanged(UUID player) {
        ACTIVITY_VERSIONS.put(player, ++activitySequence);
    }

    /** Captures memory sessions before a database query; callers must recheck the version. */
    public static ActivityMemory activityMemory(UUID player, com.djrapitops.plan.identification.ServerUUID server, long now) {
        synchronized (ACTIVITY_LOCK) {
            Map<Long, Long> sessions = new HashMap<>();
            for (FinishedSession pending : AWAITING_STORAGE.getOrDefault(player, List.of())) {
                if (pending.getServerUUID().equals(server)) {
                    if (pending.getLength() < 0 || pending.getAfkTime() < 0 || pending.getActiveTime() < 0
                            || pending.getEnd() > now) throw new IllegalStateException("Invalid pending activity");
                    sessions.merge(pending.getStart(), pending.getActiveTime(), Math::max);
                }
            }
            ActiveSession active = ACTIVE_SESSIONS.get(player);
            boolean online = active != null && active.getServerUUID().equals(server);
            if (online) sessions.merge(active.getStart(), active.confirmedActiveTime(now), Math::max);
            return new ActivityMemory(ACTIVITY_VERSIONS.getOrDefault(player, 0L),
                    Map.copyOf(sessions), online, now);
        }
    }

    public static boolean activityUnchanged(UUID player, ActivityMemory snapshot) {
        synchronized (ACTIVITY_LOCK) {
            return ACTIVITY_VERSIONS.getOrDefault(player, 0L) == snapshot.version();
        }
    }

    /** Called only after the storage future commits successfully. Failed writes stay represented. */
    public static void activityStored(FinishedSession stored) {
        synchronized (ACTIVITY_LOCK) {
            List<FinishedSession> pending = AWAITING_STORAGE.get(stored.getPlayerUUID());
            if (pending != null && pending.removeIf(session -> session.getStart() == stored.getStart()
                    && session.getEnd() == stored.getEnd() && session.getServerUUID().equals(stored.getServerUUID()))) {
                if (pending.isEmpty()) AWAITING_STORAGE.remove(stored.getPlayerUUID());
                activityChanged(stored.getPlayerUUID());
            }
        }
    }

    public record ActivityMemory(long version, Map<Long, Long> sessions, boolean online, long observedAt) { }

    @Inject
    public SessionCache() {
        // Dagger requires empty inject constructor
    }

    public static Collection<ActiveSession> getActiveSessions() {
        refreshActiveSessionsState();
        return new HashSet<>(ACTIVE_SESSIONS.values());
    }

    public static void clear() {
        synchronized (ACTIVITY_LOCK) {
            Set<UUID> players = new HashSet<>(ACTIVE_SESSIONS.keySet());
            players.addAll(AWAITING_STORAGE.keySet());
            players.forEach(SessionCache::activityChanged);
            ACTIVE_SESSIONS.clear();
            AWAITING_STORAGE.clear();
        }
    }

    public static void refreshActiveSessionsState() {
        ACTIVE_SESSIONS.values().forEach(ActiveSession::updateState);
    }

    /**
     * Used to get the Session of the player in the sessionCache.
     *
     * @param playerUUID UUID of the player.
     * @return Optional with the session inside it if found.
     */
    public static Optional<ActiveSession> getCachedSession(UUID playerUUID) {
        Optional<ActiveSession> found = Optional.ofNullable(ACTIVE_SESSIONS.get(playerUUID));
        found.ifPresent(ActiveSession::updateState);
        return found;
    }

    /**
     * Cache a new session.
     *
     * @param playerUUID UUID of the player
     * @param newSession Session to cache.
     * @return Optional: previous session. Recipients of this object should decide if it needs to be saved.
     */
    public Optional<FinishedSession> cacheSession(UUID playerUUID, ActiveSession newSession) {
        synchronized (ACTIVITY_LOCK) {
            Optional<ActiveSession> inProgress = getCachedSession(playerUUID);
            Optional<FinishedSession> finished = Optional.empty();
            if (inProgress.isPresent()) {
                finished = endSession(playerUUID, newSession.getStart(), inProgress.get());
            }
            ACTIVE_SESSIONS.put(playerUUID, newSession);
            activityChanged(playerUUID);
            return finished;
        }
    }

    /**
     * End a session and save it to database.
     *
     * @param playerUUID    UUID of the player.
     * @param time          Time the session ended.
     * @param activeSession Currently active session
     * @return Optional: ended session. Recipients of this object should decide if it needs to be saved.
     */
    public Optional<FinishedSession> endSession(UUID playerUUID, long time, ActiveSession activeSession) {
        synchronized (ACTIVITY_LOCK) {
            if (activeSession == null || ACTIVE_SESSIONS.get(playerUUID) != activeSession) {
                return Optional.empty();
            }
            ACTIVE_SESSIONS.remove(playerUUID);
            activityChanged(playerUUID);
            if (activeSession.getStart() > time) {
                return Optional.empty();
            }
            FinishedSession finished = activeSession.toFinishedSession(time);
            AWAITING_STORAGE.computeIfAbsent(playerUUID, ignored -> new ArrayList<>()).add(finished);
            return Optional.of(finished);
        }
    }

    public Optional<FinishedSession> endSession(UUID playerUUID, long time) {
        synchronized (ACTIVITY_LOCK) {
            return endSession(playerUUID, time, ACTIVE_SESSIONS.get(playerUUID));
        }
    }
}
