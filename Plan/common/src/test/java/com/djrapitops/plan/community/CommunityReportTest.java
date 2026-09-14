package com.djrapitops.plan.community;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.djrapitops.plan.community.CommunityReport.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityReportTest {
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final long BASE = epoch(START);
    private Session session(String player, long start, long end) {
        return new Session(player, start, end, true, List.of(new Span(start, end)));
    }
    private List<Session> group(long start, long end) {
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) sessions.add(session("account-" + i, start, end));
        return sessions;
    }
    private Dataset dataset(List<Session> sessions, List<Span> coverage) {
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) members.add(new Member("account-" + i, BASE + (i < 5 ? 1 : -DAY)));
        return new Dataset(members, sessions, coverage);
    }
    @SuppressWarnings("unchecked") private Map<String, Object> section(Map<String, Object> report, String name) {
        return (Map<String, Object>) report.get(name);
    }
    @Test void crossMidnightIntervalsAreClippedAndAccountsDeduplicated() {
        List<Session> sessions = group(BASE - DAY / 2, BASE + DAY / 2);
        sessions.add(sessions.get(0));
        var result = calculate(START, START.plusDays(1), BASE + 2 * DAY,
                dataset(sessions, List.of(new Span(BASE, BASE + DAY))));
        assertEquals(10, section(result, "summary").get("participants"));
        assertEquals(120.0, section(result, "summary").get("active_hours"));
        assertEquals(10, section(result, "summary").get("peak_online"));
        assertEquals(5L, section(result, "summary").get("newcomers"));
    }
    @Test void realZeroDaysSurviveButSmallPositiveDaysOmitWholeChart() {
        List<Session> sessions = group(BASE, BASE + 3_600_000);
        var data = dataset(sessions, List.of(new Span(BASE, BASE + 3 * DAY)));
        var report = calculate(START, START.plusDays(3), BASE + 4 * DAY, data);
        List<?> daily = (List<?>) report.get("daily");
        assertEquals(3, daily.size());
        assertEquals(0, ((Map<?, ?>) daily.get(1)).get("participants"));
        sessions.add(session("account-0", BASE + DAY, BASE + DAY + 600_000));
        report = calculate(START, START.plusDays(3), BASE + 4 * DAY,
                dataset(sessions, List.of(new Span(BASE, BASE + 3 * DAY))));
        assertTrue(((List<?>) report.get("daily")).isEmpty());
    }
    @Test void coverageGapDoesNotTurnIntoAConvincingZeroOrPartialTotal() {
        var report = calculate(START, START.plusDays(3), BASE + 4 * DAY,
                dataset(group(BASE, BASE + DAY), List.of(new Span(BASE, BASE + DAY), new Span(BASE + 2 * DAY, BASE + 3 * DAY))));
        assertEquals("unavailable", report.get("status"));
        assertTrue(section(report, "summary").values().stream().allMatch(Objects::isNull));
    }
    @Test void invalidSessionRemovesItsObservationCoverage() {
        List<Session> sessions = group(BASE, BASE + DAY);
        sessions.add(new Session("unknown", BASE, BASE + DAY, false, List.of()));
        var report = calculate(START, START.plusDays(1), BASE + 2 * DAY,
                dataset(sessions, List.of(new Span(BASE, BASE + DAY))));
        assertEquals("unavailable", report.get("status"));
    }
    @Test void currentDayIsLabelledWithTheActualRecordedCutoff() {
        long cutoff = BASE + DAY / 2;
        var report = calculate(START, START.plusDays(1), cutoff + 600_000,
                dataset(group(BASE, cutoff), List.of(new Span(BASE, cutoff))));
        assertEquals("ready", report.get("status"));
        assertEquals(false, section(report, "period").get("complete"));
        assertEquals(cutoff, section(report, "period").get("as_of"));
        assertTrue(((List<?>) report.get("daily")).isEmpty());
    }
    @Test void overlappingSessionsDoNotDoubleCountPeakAndBoundaryDeparturesComeFirst() {
        List<Session> sessions = group(BASE, BASE + 600_000);
        sessions.add(session("account-0", BASE + 100_000, BASE + 500_000));
        sessions.add(session("later", BASE + 600_000, BASE + 1_200_000));
        var report = calculate(START, START.plusDays(1), BASE + DAY,
                dataset(sessions, List.of(new Span(BASE, BASE + DAY))));
        assertEquals(10, section(report, "summary").get("peak_online"));
    }
    @Test void returnWindowsRequireMaturityAndAnActualQualifyingLaterDay() {
        List<Member> members = new ArrayList<>(); List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String id = "new-" + i; members.add(new Member(id, BASE + 1));
            sessions.add(session(id, BASE + 1, BASE + 600_001));
            if (i < 10) sessions.add(session(id, BASE + 2 * DAY, BASE + 2 * DAY + QUALIFYING));
        }
        members.add(new Member("too-recent", BASE + 6 * DAY));
        var data = new Dataset(members, sessions, List.of(new Span(BASE, BASE + 8 * DAY)));
        var report = calculate(START, START.plusDays(7), BASE + 8 * DAY, data);
        assertEquals(20L, section(report, "retention").get("eligible"));
        assertEquals(10L, section(report, "retention").get("returned"));
        assertEquals(0.5, section(report, "retention").get("rate"));
        var early = calculate(START, START.plusDays(7), BASE + 7 * DAY, data);
        assertNull(section(early, "retention").get("rate"));
    }
    @Test void smallGroupsAndDominantPlaytimeAreWithheld() {
        var small = calculate(START, START.plusDays(1), BASE + DAY,
                dataset(group(BASE, BASE + 100_000), List.of(new Span(BASE, BASE + DAY))));
        assertEquals("empty", small.get("status"));
        var sessions = group(BASE, BASE + QUALIFYING);
        sessions.add(session("dominant", BASE, BASE + DAY));
        var result = calculate(START, START.plusDays(1), BASE + DAY,
                dataset(sessions, List.of(new Span(BASE, BASE + DAY))));
        assertEquals(11, section(result, "summary").get("participants"));
        assertNull(section(result, "summary").get("active_hours"));
    }
    @Test void aggregateContractContainsNoAccountIdentifiers() {
        var result = calculate(START, START.plusDays(1), BASE + DAY,
                dataset(group(BASE, BASE + QUALIFYING), List.of(new Span(BASE, BASE + DAY))));
        String json = new com.google.gson.Gson().toJson(result);
        assertFalse(json.contains("account-")); assertFalse(json.contains("uuid"));
        assertEquals(Set.of("schema_version", "status", "period", "summary", "daily", "retention", "omissions"), result.keySet());
    }
    @Test void malformedIntervalsFailInsteadOfInflatingPublicHours() {
        var bad = new Session("bad", BASE, BASE + DAY, true, List.of(new Span(BASE - 1, BASE + 1)));
        assertThrows(IllegalStateException.class, () -> calculate(START, START.plusDays(1), BASE + DAY,
                dataset(List.of(bad), List.of(new Span(BASE, BASE + DAY)))));
    }
    @Test void datesUseUtcCalendarAndBoundTheWork() {
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START, BASE));
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START.plusDays(367), BASE + 400 * DAY));
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START.plusDays(2), BASE + 1));
        assertDoesNotThrow(() -> validateDates(START, START.plusDays(1), BASE + 1));
    }
    @Test void launchDayClipsToActualCaptureButAResumedGapIsNotRelabelledAsLaunch() {
        long launch = BASE + DAY / 2;
        var launched = calculate(START, START.plusDays(1), BASE + DAY,
                dataset(group(launch, launch + 600_000), List.of(new Span(launch, BASE + DAY))));
        assertEquals("ready", launched.get("status"));
        assertEquals(launch, section(launched, "period").get("recorded_from"));
        assertEquals(false, section(launched, "period").get("complete"));
        var resumed = new Dataset(List.of(), group(launch, launch + 600_000),
                List.of(new Span(launch, BASE + DAY)), BASE - DAY);
        assertEquals("unavailable", calculate(START, START.plusDays(1), BASE + DAY, resumed).get("status"));
    }
}
