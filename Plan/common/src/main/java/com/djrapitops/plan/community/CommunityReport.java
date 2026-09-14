package com.djrapitops.plan.community;

import com.djrapitops.plan.storage.database.Database;
import com.google.gson.Gson;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** A bounded, consistent report snapshot. Only disclosure-checked aggregate fields leave this class. */
public final class CommunityReport {
    static final long DAY = 86_400_000L, QUALIFYING = 300_000L;
    static final int MIN_GROUP = 10, MIN_CELL = 5, MAX_ROWS = 200_000;
    private static final Gson JSON = new Gson();
    public record Span(long start, long end) { }
    public record Member(String uuid, long firstSeen) { }
    public record Session(String uuid, long start, long end, boolean valid, List<Span> intervals) { }
    public record Dataset(List<Member> members, List<Session> sessions, List<Span> coverage, long captureStarted) {
        public Dataset(List<Member> members, List<Session> sessions, List<Span> coverage) {
            this(members, sessions, coverage, coverage.stream().mapToLong(Span::start).min().orElse(0));
        }
    }
    private CommunityReport() { }

    public static void validateDates(LocalDate start, LocalDate end, long now) {
        if (start == null || end == null || start.isBefore(LocalDate.of(2000, 1, 1))
                || !end.isAfter(start) || ChronoUnit.DAYS.between(start, end) > 366
                || end.isAfter(CommunityCalendar.date(now).plusDays(1)))
            throw new IllegalArgumentException("Select between one and 366 Vancouver calendar days, ending no later than today");
    }

    public static Map<String, Object> load(Database database, UUID server, LocalDate start, LocalDate end, long now) {
        validateDates(start, end, now);
        long from = epoch(start), horizon = Math.min(now, epoch(end.plusDays(8)));
        AtomicReference<Dataset> snapshot = new AtomicReference<>();
        CommunityTables.commit(database, new CommunityTables.Tx() {
            @Override protected IsolationLevel getDesiredIsolationLevel() { return IsolationLevel.REPEATABLE_READ; }
            @Override protected void performCommunityOperations() {
                String key = server.toString();
                List<Member> members = rows("SELECT uuid,first_seen FROM " + CommunityTables.MEMBERS
                        + " WHERE server_uuid=? AND first_seen<? LIMIT 50001",
                        r -> new Member(r.getString(1), r.getLong(2)), key, epoch(end));
                long[] bytes = {0};
                List<Session> sessions = rows("SELECT uuid,session_start,session_end,valid,intervals FROM "
                        + CommunityTables.ACTIVITY + " WHERE server_uuid=? AND session_start<? AND session_end>? LIMIT 200001",
                        r -> new Session(r.getString(1), r.getLong(2), r.getLong(3), r.getInt(4) == 1,
                                decode(r.getString(5), bytes)), key, horizon, from);
                List<Span> coverage = rows("SELECT start_ms,end_ms FROM " + CommunityTables.COVERAGE
                        + " WHERE server_uuid=? AND start_ms<? AND end_ms>? LIMIT 100001",
                        r -> new Span(r.getLong(1), r.getLong(2)), key, horizon, from);
                if (members.size() > 50_000 || sessions.size() > MAX_ROWS || coverage.size() > 100_000)
                    throw new IllegalStateException("Report exceeds bounded capacity; use a shorter period");
                long captureStarted = one("SELECT MIN(start_ms) FROM " + CommunityTables.COVERAGE + " WHERE server_uuid=?",
                        r -> r.getLong(1), 0L, key);
                snapshot.set(new Dataset(List.copyOf(members), List.copyOf(sessions), List.copyOf(coverage), captureStarted));
            }
        }).join();
        return calculate(start, end, now, snapshot.get());
    }

    private static List<Span> decode(String json, long[] bytes) {
        if (json == null || (bytes[0] += 2L * json.length()) > 32L * 1024 * 1024)
            throw new IllegalStateException("Activity snapshot exceeds bounded capacity");
        Span[] spans = JSON.fromJson(json, Span[].class);
        if (spans == null || spans.length > 16_384) throw new IllegalStateException("Invalid recorded activity");
        return List.of(spans);
    }

    static Map<String, Object> calculate(LocalDate start, LocalDate end, long now, Dataset data) {
        validateDates(start, end, now);
        long requestedStart = epoch(start), requestedEnd = epoch(end);
        long from = Math.max(requestedStart, data.captureStarted);
        List<Span> coverage = validCoverage(data.coverage, data.sessions);
        long observedThrough = Math.min(now, coverage.stream().mapToLong(Span::end).max().orElse(0));
        long until = Math.min(requestedEnd, observedThrough);
        boolean usable = until > from && covered(from, until, coverage);
        boolean complete = usable && until == requestedEnd && from == requestedStart;
        Map<String, Object> summary = map("participants", null, "newcomers", null, "active_hours", null, "peak_online", null);
        List<Map<String, Object>> omissions = new ArrayList<>();
        Map<String, List<Span>> activity = activity(data.sessions);
        if (usable) {
            Map<String, Long> totals = totals(activity, from, until);
            Set<String> participants = qualifying(totals);
            if (participants.size() >= MIN_GROUP) {
                summary.put("participants", participants.size());
                long newcomers = data.members.stream().filter(m -> m.firstSeen >= from && m.firstSeen < until
                        && participants.contains(m.uuid)).map(Member::uuid).distinct().count();
                if (safeSplit(newcomers, participants.size())) summary.put("newcomers", newcomers);
                else omit(omissions, "newcomers", "New and established groups are too small to publish separately");
                long total = totals.values().stream().mapToLong(Long::longValue).sum();
                long largest = totals.values().stream().mapToLong(Long::longValue).max().orElse(0);
                if (total > 0 && largest <= total / 2) summary.put("active_hours", total / 3_600_000.0);
                else omit(omissions, "active_hours", "Activity is concentrated in too few accounts");
                summary.put("peak_online", peak(data.sessions, from, until));
            } else omit(omissions, "overview", "Fewer than ten qualifying participants in the selected period");
        } else omit(omissions, "overview", "The selected period has gaps or precedes activity recording");

        List<Map<String, Object>> daily = new ArrayList<>();
        boolean dailySafe = complete && ChronoUnit.DAYS.between(start, end) >= 2
                && summary.get("participants") != null;
        if (dailySafe) {
            Map<Long, Integer> dailyCounts = dailyCounts(activity, from, requestedEnd);
            for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
                long a = epoch(date);
                int count = dailyCounts.getOrDefault(a, 0);
                if (count > 0 && count < MIN_CELL) { dailySafe = false; break; }
                // Active hours per day are deliberately not published: they can expose one dominant player.
                daily.add(map("date", date.toString(), "participants", count, "active_hours", null, "covered", true));
            }
        }
        if (!dailySafe) {
            daily.clear();
            omit(omissions, "activity_chart", "A complete multi-day period with publishable daily groups is required");
        }

        List<Map<String, Object>> busyCells = complete && ChronoUnit.DAYS.between(start, end) >= 7
                && summary.get("participants") != null ? CommunityBusyTimes.calculate(activity, from, requestedEnd) : List.of();
        if (busyCells.isEmpty())
            omit(omissions, "busy_times", "A complete period of at least seven days and safely publishable hourly groups is required");
        else if (busyCells.stream().anyMatch(cell -> cell.get("average_active_players") == null))
            omit(omissions, "busy_times", "Some hourly cells are withheld because observations are absent, small or concentrated");

        long eligible = 0, returned = 0;
        for (Member member : data.members) {
            if (member.firstSeen < from || member.firstSeen >= Math.min(requestedEnd, now)) continue;
            LocalDate joined = CommunityCalendar.date(member.firstSeen);
            long lastReturn = epoch(joined.plusDays(8));
            if (lastReturn > observedThrough || !covered(member.firstSeen, lastReturn, coverage)) continue;
            eligible++;
            List<Span> personal = activity.getOrDefault(member.uuid, List.of());
            for (LocalDate day = joined.plusDays(1); day.isBefore(joined.plusDays(8)); day = day.plusDays(1)) {
                if (duration(personal, epoch(day), epoch(day.plusDays(1))) >= QUALIFYING) { returned++; break; }
            }
        }
        Map<String, Object> retention = map("eligible", null, "returned", null, "rate", null, "observed_through", observedThrough);
        if (eligible >= MIN_GROUP && safeSplit(returned, eligible)) {
            retention.put("eligible", eligible); retention.put("returned", returned); retention.put("rate", returned / (double) eligible);
        } else omit(omissions, "retention", "Return observations are incomplete or the groups are too small to publish safely");
        boolean ready = summary.values().stream().anyMatch(Objects::nonNull) || retention.get("rate") != null;
        return map("schema_version", 2, "status", ready ? "ready" : usable ? "empty" : "unavailable",
                "period", map("start_date", start.toString(), "end_date", end.toString(), "timezone", CommunityCalendar.TIMEZONE,
                        "recorded_from", Math.max(0, from), "as_of", Math.max(0, until), "complete", complete),
                "summary", summary, "daily", daily,
                "busy_times", map("metric", "average_active_players", "cells", busyCells),
                "retention", retention, "omissions", omissions);
    }

    private static Map<String, List<Span>> activity(List<Session> sessions) {
        Map<String, List<Span>> byPlayer = new HashMap<>();
        for (Session session : sessions) {
            if (session.start < 0 || session.end < session.start) throw new IllegalStateException("Invalid session bounds");
            if (!session.valid) continue;
            List<Span> target = byPlayer.computeIfAbsent(session.uuid, unused -> new ArrayList<>());
            for (Span span : session.intervals) {
                if (span == null || span.start < session.start || span.end > session.end || span.end <= span.start)
                    throw new IllegalStateException("Activity falls outside its recorded session");
                target.add(span);
            }
        }
        byPlayer.replaceAll((uuid, spans) -> merge(spans));
        return byPlayer;
    }
    private static Map<String, Long> totals(Map<String, List<Span>> activity, long from, long until) {
        Map<String, Long> out = new HashMap<>();
        activity.forEach((uuid, spans) -> out.put(uuid, duration(spans, from, until)));
        return out;
    }
    private static Set<String> qualifying(Map<String, Long> totals) {
        Set<String> out = new HashSet<>();
        totals.forEach((uuid, millis) -> { if (millis >= QUALIFYING) out.add(uuid); });
        return out;
    }
    private static long duration(List<Span> spans, long from, long until) {
        long total = 0;
        for (Span span : spans) total = Math.addExact(total, Math.max(0, Math.min(until, span.end) - Math.max(from, span.start)));
        return total;
    }
    static List<Span> merge(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans), merged = new ArrayList<>();
        sorted.sort(Comparator.comparingLong(Span::start));
        for (Span span : sorted) {
            if (span.start < 0 || span.end < span.start) throw new IllegalStateException("Invalid observation bounds");
            if (span.end == span.start) continue;
            if (!merged.isEmpty() && span.start <= merged.get(merged.size() - 1).end) {
                Span prior = merged.remove(merged.size() - 1);
                merged.add(new Span(prior.start, Math.max(prior.end, span.end)));
            } else merged.add(span);
        }
        return merged;
    }
    private static boolean covered(long from, long until, List<Span> coverage) {
        if (until <= from) return false;
        int low = 0, high = coverage.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (coverage.get(mid).start <= from) { found = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return found >= 0 && coverage.get(found).end >= until;
    }
    private static List<Span> validCoverage(List<Span> raw, List<Session> sessions) {
        List<Span> invalid = merge(sessions.stream().filter(s -> !s.valid).map(s -> new Span(s.start, s.end)).toList());
        List<Span> out = new ArrayList<>(); int index = 0;
        for (Span span : merge(raw)) {
            long cursor = span.start;
            while (index < invalid.size() && invalid.get(index).end <= cursor) index++;
            for (int i = index; i < invalid.size() && invalid.get(i).start < span.end; i++) {
                Span gap = invalid.get(i);
                if (gap.start > cursor) out.add(new Span(cursor, gap.start));
                cursor = Math.max(cursor, gap.end);
                if (cursor >= span.end) break;
            }
            if (cursor < span.end) out.add(new Span(cursor, span.end));
        }
        return out;
    }
    private static Map<Long, Integer> dailyCounts(Map<String, List<Span>> activity, long from, long until) {
        Map<Long, Integer> counts = new HashMap<>();
        for (List<Span> spans : activity.values()) {
            Map<Long, Long> millis = new HashMap<>();
            for (Span span : spans) {
                long cursor = Math.max(from, span.start), end = Math.min(until, span.end);
                while (cursor < end) {
                    LocalDate date = CommunityCalendar.date(cursor);
                    long day = epoch(date), through = Math.min(end, epoch(date.plusDays(1)));
                    millis.merge(day, through - cursor, Long::sum); cursor = through;
                }
            }
            millis.forEach((day, total) -> { if (total >= QUALIFYING) counts.merge(day, 1, Integer::sum); });
        }
        return counts;
    }
    private static int peak(List<Session> sessions, long from, long until) {
        Map<String, List<Span>> players = new HashMap<>();
        for (Session s : sessions) {
            long a = Math.max(from, s.start), b = Math.min(until, s.end);
            if (b > a) players.computeIfAbsent(s.uuid, unused -> new ArrayList<>()).add(new Span(a, b));
        }
        TreeMap<Long, Integer> changes = new TreeMap<>();
        players.values().forEach(spans -> merge(spans).forEach(span -> {
            changes.merge(span.start, 1, Integer::sum); changes.merge(span.end, -1, Integer::sum);
        }));
        int current = 0, peak = 0;
        for (int change : changes.values()) { current += change; peak = Math.max(peak, current); }
        return peak;
    }
    private static boolean safeSplit(long cell, long total) { return cell >= MIN_CELL && total - cell >= MIN_CELL; }
    private static void omit(List<Map<String, Object>> omissions, String section, String reason) { omissions.add(map("section", section, "reason", reason)); }
    static long epoch(LocalDate date) { return CommunityCalendar.start(date); }
    static Map<String, Object> map(Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]);
        return out;
    }
}
