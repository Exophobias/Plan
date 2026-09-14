package com.djrapitops.plan.referrals;

import com.djrapitops.plan.storage.database.Database;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import static com.djrapitops.plan.referrals.ReferralAnalyticsService.*;

/** Fixed-horizon, prospective descriptive metrics. Never rebuild denominators from surviving raw users. */
public final class ReferralReport {
    static final long DAY = 86400000L, RETURN_MINIMUM = 300000L;
    static final List<String> GROUPS = List.of("recorded_referral", "no_recorded_referral", "late_referral", "unknown");
    static final Map<String, int[]> WINDOWS = new LinkedHashMap<>();
    static { WINDOWS.put("D1", new int[]{1,2}); WINDOWS.put("D7", new int[]{7,8}); WINDOWS.put("D30", new int[]{30,31});
        WINDOWS.put("W1", new int[]{7,14}); WINDOWS.put("W2", new int[]{14,21}); WINDOWS.put("W4", new int[]{28,35}); }
    private ReferralReport() { }
    public record Member(String uuid, long joined) { }
    public record Span(long start, long end) { }
    public record Session(String uuid, long start, long end, boolean valid, List<Span> intervals) { }
    public record Feed(long through, long started, long received) { }
    private record Data(List<Member> members, List<Session> sessions, List<Span> coverage, List<Event> events, Feed feed, long flush) { }

    static Map<String, Object> load(Database db, UUID server, long now, boolean failed) {
        java.util.concurrent.atomic.AtomicReference<Data> output = new java.util.concurrent.atomic.AtomicReference<>();
        ReferralTables.commit(db, new ReferralTables.Tx() {
            @Override protected void performOperations() { output.set(loadSnapshot(this, server, now, failed)); }
        }).join();
        Data data = output.get();
        return calculate(server, now, data.members, data.sessions, data.coverage, data.events, data.feed, data.flush, failed);
    }

    private static Data loadSnapshot(ReferralTables.Tx db, UUID server, long now, boolean failed) {
        String key = server.toString();
        List<Member> members = db.rows("SELECT uuid,first_join FROM " + ReferralTables.MEMBERS + " WHERE server_uuid=? ORDER BY first_join LIMIT 50001",
                r -> new Member(r.getString(1), r.getLong(2)), key);
        long[] bytes = {0};
        List<Session> sessions = db.rows("SELECT uuid,session_start,session_end,valid,intervals FROM " + ReferralTables.ACTIVITY + " WHERE server_uuid=? LIMIT 200001",
                r -> {
                    String encoded = r.getString(5); bytes[0] += encoded.length();
                    if (encoded.length() > 1500000 || bytes[0] > 32*1024*1024) throw new IllegalStateException("Referral interval budget exceeded");
                    ReferralActivity.Interval[] intervals = ReferralJournal.JSON.fromJson(encoded, ReferralActivity.Interval[].class);
                    return new Session(r.getString(1), r.getLong(2), r.getLong(3), r.getInt(4) == 1,
                            Arrays.stream(intervals).map(i -> new Span(i.start(), i.end())).collect(Collectors.toList()));
                }, key);
        List<Span> coverage = db.rows("SELECT start_ms,end_ms FROM " + ReferralTables.COVERAGE + " WHERE server_uuid=? ORDER BY start_ms LIMIT 100001",
                r -> new Span(r.getLong(1), r.getLong(2)), key);
        long flush = db.optional("SELECT MAX(flushed_at) FROM " + ReferralTables.COVERAGE + " WHERE server_uuid=?", r -> r.getLong(1), key).orElse(0L);
        Feed feed = db.optional("SELECT source_as_of,capture_started,received_at FROM " + ReferralTables.FEED + " WHERE server_uuid=?",
                r -> new Feed(r.getLong(1), r.getLong(2), r.getLong(3)), key).orElse(new Feed(0, 0, 0));
        List<Event> events = db.rows("SELECT record_json FROM " + ReferralTables.LATEST + " WHERE server_uuid=? LIMIT 200001",
                r -> ReferralJournal.JSON.fromJson(r.getString(1), Event.class), key);
        if (members.size() > 50000 || sessions.size() > 200000 || coverage.size() > 100000 || events.size() > 200000)
            throw new IllegalStateException("Referral report capacity requires archival");
        return new Data(members, sessions, coverage, events, feed, flush);
    }

    static Map<String, Object> calculate(UUID server, long now, List<Member> members, List<Session> sessions,
                                          List<Span> coverage, List<Event> events, Feed feed, long flushed, boolean failed) {
        List<Span> covered = merge(coverage);
        long started = covered.isEmpty() ? 0 : covered.get(0).start;
        long through = covered.isEmpty() ? 0 : covered.get(covered.size() - 1).end;
        Map<String, Claim> claims = new LinkedHashMap<>(); Map<String, Award> awards = new LinkedHashMap<>();
        for (Event event : events) {
            if (event.claim != null) claims.put(event.claim.claimId, event.claim);
            else awards.put(event.award.awardId, event.award);
        }
        Map<String, Claim> byPlayer = new HashMap<>(); claims.values().forEach(c -> byPlayer.put(c.newcomerUUID.toString(), c));
        Map<String, List<Session>> activity = sessions.stream().collect(Collectors.groupingBy(Session::uuid));
        List<Player> players = new ArrayList<>();
        for (Member member : members) {
            Claim claim = byPlayer.get(member.uuid);
            String group = attribution(member, claim, feed, now);
            List<Session> playerSessions = activity.getOrDefault(member.uuid, List.of());
            List<Span> intervals = merge(playerSessions.stream().filter(Session::valid).flatMap(s -> s.intervals.stream()).collect(Collectors.toList()));
            List<Span> invalid = playerSessions.stream().filter(s -> !s.valid).map(s -> new Span(s.start, s.end)).collect(Collectors.toList());
            players.add(new Player(member, claim, group, intervals, invalid, covered, now));
        }
        String status = failed || started == 0 || feed.through == 0 ? "unavailable"
                : now - flushed > 300000 || now - feed.received > 600000 || now - feed.through > 600000 ? "stale"
                : players.stream().anyMatch(p -> p.complete(7, 14)) ? "ready" : "collecting";
        Map<String,Object> summary = map("players", players.size());
        for (String group : GROUPS) summary.put(group, players.stream().filter(p -> p.matches(group)).count());
        summary.put("late_referral_is_subset", true);
        Map<String,List<Player>> weeks = players.stream().collect(Collectors.groupingBy(p -> week(p.member.joined), TreeMap::new, Collectors.toList()));
        List<Object> cohorts = new ArrayList<>();
        weeks.forEach((week, cohort) -> cohorts.add(map("week", week, "groups", comparisons(cohort))));
        Set<String> delivered = awards.values().stream().filter(a -> a.deliveredAt > 0).map(a -> a.claimId).collect(Collectors.toSet());
        Map<String,Object> funnel = map("scope", "all_recorded_claims", "requested", claims.size(),
                "accepted", claims.values().stream().filter(c -> c.acceptedAt > 0).count(),
                "qualified", claims.values().stream().filter(c -> c.qualifiedAt > 0).count(), "rewarded", delivered.size());
        for (String state : List.of("verifying", "pending", "rejected", "expired"))
            funnel.put(state, claims.values().stream().filter(c -> state.equals(c.status)).count());
        List<Player> referred = players.stream().filter(p -> "recorded_referral".equals(p.group)).collect(Collectors.toList());
        List<Player> eligible = referred.stream().filter(p -> p.complete(7,14)).collect(Collectors.toList());
        Set<String> eligibleClaims = eligible.stream().map(p -> p.claim.claimId).collect(Collectors.toSet());
        long retained = eligible.stream().filter(p -> p.active(7,14) >= RETURN_MINIMUM).count();
        List<Object> credit = new ArrayList<>();
        awards.values().stream().filter(a -> "credit".equals(a.kind)).collect(Collectors.groupingBy(a -> a.currency, TreeMap::new, Collectors.toList()))
                .forEach((currency, rows) -> {
                    long granted = sumAwards(rows, false, null), issued = sumAwards(rows, true, null);
                    long cohortGranted = sumAwards(rows, false, eligibleClaims), cohortIssued = sumAwards(rows, true, eligibleClaims);
                    credit.add(map("currency", currency, "window", "W1", "granted_minor", granted, "delivered_minor", issued,
                            "denominator_scope", "all_recorded_referral_players",
                            "cohort_granted_minor", cohortGranted, "cohort_delivered_minor", cohortIssued,
                            "retained_players", retained, "eligible", eligible.size(), "incomplete", referred.size() - eligible.size(),
                            "granted_minor_per_retained", retained == 0 ? null : (double) cohortGranted / retained,
                            "delivered_minor_per_retained", retained == 0 ? null : (double) cohortIssued / retained,
                            "post_reward", postReward(rows, players)));
                });
        List<Award> raffle = awards.values().stream().filter(a -> !"credit".equals(a.kind)).collect(Collectors.toList());
        return map("schema_version", 1, "server_uuid", server.toString(), "generated_at", now,
                "coverage", map("status", status, "started_at", started, "activity_through", through,
                        "referral_through", feed.through, "referral_capture_started_at", feed.started,
                        "unknown_gaps", Math.max(0, covered.size() - 1), "attribution_hours", 24,
                        "return_minimum_minutes", 5, "activity_flushed_at", flushed),
                "summary", summary, "comparison", comparisons(players), "cohorts", cohorts, "funnel", funnel,
                "credit", credit, "raffle", map("granted_entries", sumAwards(raffle, false, null), "delivered_entries", sumAwards(raffle, true, null)));
    }
    static String attribution(Member member, Claim claim, Feed feed, long now) {
        if (member.joined <= 0 || member.joined + DAY > now || feed.through < member.joined + DAY
                || feed.started <= 0 || member.joined < feed.started) return "unknown";
        if (claim == null) return "no_recorded_referral";
        if (!claim.initialAttributionKnown || claim.legacy || claim.requestedAt < feed.started) return "unknown";
        return claim.requestedAt < member.joined + DAY ? "recorded_referral" : "no_recorded_referral";
    }
    private static Map<String,Object> postReward(List<Award> awards, List<Player> players) {
        Map<String,Player> byClaim = new HashMap<>();
        players.stream().filter(p -> p.claim != null).forEach(p -> byClaim.put(p.claim.claimId, p));
        Map<String,Long> issued = new HashMap<>(); Set<String> unknown = new HashSet<>();
        for (Award award : awards) if (award.deliveredAt > 0) {
            if (award.issuedAt > 0) issued.merge(award.claimId, award.issuedAt, Math::min);
            else unknown.add(award.claimId);
        }
        unknown.removeAll(issued.keySet()); long eligible = 0, retained = 0, incomplete = 0;
        for (Map.Entry<String,Long> issue : issued.entrySet()) {
            Player player = byClaim.get(issue.getKey()); long start = issue.getValue() + DAY, end = start + 7*DAY;
            if (player == null || end > player.member.joined + 35*DAY || !player.completeAbsolute(start, end)) { incomplete++; continue; }
            eligible++; if (overlap(player.intervals, start, end) >= RETURN_MINIMUM) retained++;
        }
        return map("window", "days1_to8_after_issue", "eligible", eligible, "retained", retained, "incomplete", incomplete,
                "unknown_timing", unknown.size(), "rate", eligible == 0 ? null : (double)retained/eligible);
    }
    private static long sumAwards(List<Award> awards, boolean delivered, Set<String> claimIds) {
        long total = 0;
        for (Award a : awards) if ((!delivered || a.deliveredAt > 0) && (claimIds == null || claimIds.contains(a.claimId))) total = Math.addExact(total, a.amountMinor);
        return total;
    }
    private static List<Object> comparisons(List<Player> players) {
        List<Object> result = new ArrayList<>();
        for (String group : GROUPS) {
            List<Player> matching = players.stream().filter(p -> p.matches(group)).collect(Collectors.toList());
            List<Object> retention = new ArrayList<>(), activity = new ArrayList<>();
            WINDOWS.forEach((window, days) -> {
                long eligible = 0, retained = 0;
                for (Player p : matching) if (p.complete(days[0], days[1])) { eligible++; if (p.active(days[0], days[1]) >= RETURN_MINIMUM) retained++; }
                double[] interval = wilson(retained, eligible);
                retention.add(map("window", window, "eligible", eligible, "retained", retained, "incomplete", matching.size() - eligible,
                        "rate", eligible == 0 ? null : (double) retained / eligible,
                        "lower", eligible == 0 ? null : interval[0], "upper", eligible == 0 ? null : interval[1]));
            });
            for (int days : new int[]{7,30}) {
                List<Double> minutes = new ArrayList<>(), activeDays = new ArrayList<>();
                for (Player p : matching) if (p.complete(0, days)) {
                    minutes.add(p.active(0,days) / 60000.0); int count = 0;
                    for (int day = 0; day < days; day++) if (p.active(day, day+1) >= RETURN_MINIMUM) count++;
                    activeDays.add((double) count);
                }
                activity.add(map("days", days, "eligible", minutes.size(), "incomplete", matching.size() - minutes.size(),
                        "median_minutes", median(minutes), "median_active_days", median(activeDays)));
            }
            result.add(map("group", group, "players", matching.size(), "retention", retention, "activity", activity));
        }
        return result;
    }
    static double[] wilson(long retained, long eligible) {
        if (eligible == 0) return new double[]{0,1};
        double p = (double) retained / eligible, z = 1.959963984540054, z2 = z*z, denominator = 1 + z2/eligible;
        double centre = (p + z2/(2*eligible))/denominator;
        double margin = z * Math.sqrt(p*(1-p)/eligible + z2/(4.0*eligible*eligible))/denominator;
        return new double[]{Math.max(0, centre-margin), Math.min(1, centre+margin)};
    }
    static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        Collections.sort(values); int middle = values.size()/2;
        return values.size()%2 == 1 ? values.get(middle) : (values.get(middle-1)+values.get(middle))/2;
    }
    static List<Span> merge(List<Span> values) {
        List<Span> sorted = new ArrayList<>(values); sorted.sort(Comparator.comparingLong(Span::start));
        List<Span> result = new ArrayList<>();
        for (Span span : sorted) {
            if (span.end <= span.start) continue;
            if (result.isEmpty() || result.get(result.size()-1).end < span.start) result.add(span);
            else { Span prior = result.remove(result.size()-1); result.add(new Span(prior.start, Math.max(prior.end, span.end))); }
        }
        return result;
    }
    static boolean covers(List<Span> spans, long start, long end) {
        return spans.stream().anyMatch(span -> span.start <= start && span.end >= end);
    }
    static long overlap(List<Span> spans, long start, long end) {
        long total = 0;
        for (Span span : spans) total += Math.max(0, Math.min(end, span.end) - Math.max(start, span.start));
        return total;
    }
    private static String week(long time) {
        return Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i+1]);
        return result;
    }
    private record Player(Member member, Claim claim, String group, List<Span> intervals, List<Span> invalid,
                          List<Span> coverage, long now) {
        boolean matches(String requestedGroup) {
            if ("late_referral".equals(requestedGroup)) return "no_recorded_referral".equals(group) && claim != null
                    && claim.initialAttributionKnown && !claim.legacy && claim.requestedAt >= member.joined + DAY;
            return requestedGroup.equals(group);
        }
        boolean complete(int from, int to) {
            long start = member.joined + from*DAY, end = member.joined + to*DAY;
            return end <= now && covers(coverage, member.joined, member.joined + DAY)
                    && completeAbsolute(start, end);
        }
        boolean completeAbsolute(long start, long end) { return end <= now && covers(coverage, start, end) && overlap(invalid, start, end) == 0; }
        long active(int from, int to) { return overlap(intervals, member.joined + from*DAY, member.joined + to*DAY); }
    }
}
