package com.djrapitops.plan.community;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
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
        assertEquals(Set.of("schema_version", "status", "period", "summary", "daily", "busy_times", "retention", "omissions"), result.keySet());
    }
    @Test void malformedIntervalsFailInsteadOfInflatingPublicHours() {
        var bad = new Session("bad", BASE, BASE + DAY, true, List.of(new Span(BASE - 1, BASE + 1)));
        assertThrows(IllegalStateException.class, () -> calculate(START, START.plusDays(1), BASE + DAY,
                dataset(List.of(bad), List.of(new Span(BASE, BASE + DAY)))));
    }
    @Test void datesUseVancouverCalendarAndBoundTheWork() {
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START, BASE));
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START.plusDays(367), BASE + 400 * DAY));
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START.plusDays(2), BASE + 1));
        assertDoesNotThrow(() -> validateDates(START, START.plusDays(1), BASE + 1));
        long stillSeptember = Instant.parse("2026-10-01T06:59:59Z").toEpochMilli();
        assertThrows(IllegalArgumentException.class, () -> validateDates(START, START.plusDays(1), stillSeptember));
        assertDoesNotThrow(() -> validateDates(START.minusDays(1), START, stillSeptember));
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

    @ParameterizedTest
    @CsvSource({"2025-03-09,23", "2025-11-02,25", "2026-03-08,23", "2026-11-01,24", "2027-03-14,24"})
    void historicalTransitionsAndPermanentPacificHaveCorrectMeasuredDayLengths(String dateText, int hours) {
        LocalDate date = LocalDate.parse(dateText);
        long from = epoch(date), until = epoch(date.plusDays(1)), horizon = epoch(date.plusDays(2));
        assertEquals(hours * 3_600_000L, until - from);
        var report = calculate(date, date.plusDays(2), horizon,
                new Dataset(List.of(), group(from, until), List.of(new Span(from, horizon))));
        assertEquals(hours * 10.0, section(report, "summary").get("active_hours"));
        var days = (List<?>) report.get("daily");
        assertEquals(10, ((Map<?, ?>) days.get(0)).get("participants"));
        assertEquals(0, ((Map<?, ?>) days.get(1)).get("participants"));
    }

    @Test void novemberUsesSevenHourOffsetAndLocalMidnight() {
        assertEquals(Instant.parse("2026-11-01T07:00:00Z").toEpochMilli(), epoch(LocalDate.of(2026, 11, 1)));
        assertEquals(Instant.parse("2026-11-02T07:00:00Z").toEpochMilli(), epoch(LocalDate.of(2026, 11, 2)));
        assertEquals(LocalDate.of(2026, 11, 1), CommunityCalendar.date(Instant.parse("2026-11-02T06:59:59Z").toEpochMilli()));
        assertEquals(LocalDate.of(2026, 11, 2), CommunityCalendar.date(Instant.parse("2026-11-02T07:00:00Z").toEpochMilli()));
        assertEquals(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(), epoch(LocalDate.of(2026, 3, 8)));
        assertEquals(Instant.parse("2026-03-09T07:00:00Z").toEpochMilli(), epoch(LocalDate.of(2026, 3, 9)));
    }

    @Test void qualificationSplitsAtVancouverMidnightInsteadOfUtcMidnight() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        long from = epoch(date), until = epoch(date.plusDays(2));
        long a = Instant.parse("2026-11-02T06:58:00Z").toEpochMilli();
        var report = calculate(date, date.plusDays(2), until,
                new Dataset(List.of(), group(a, a + QUALIFYING), List.of(new Span(from, until))));
        assertEquals(10, section(report, "summary").get("participants"));
        var days = (List<?>) report.get("daily");
        assertEquals(2, days.size());
        // Each account played five minutes in total, but only two/three minutes on either local day.
        assertEquals(0, ((Map<?, ?>) days.get(0)).get("participants"));
        assertEquals(0, ((Map<?, ?>) days.get(1)).get("participants"));
    }

    @Test void firstWeekClosesAtVancouverMidnightWithoutNovemberFallback() {
        LocalDate date = LocalDate.of(2026, 10, 25);
        long first = Instant.parse("2026-10-26T06:55:00Z").toEpochMilli(); // Oct 25, 23:55 Vancouver
        long lastDay = Instant.parse("2026-11-02T06:55:00Z").toEpochMilli();
        long tooLate = Instant.parse("2026-11-02T07:10:00Z").toEpochMilli();
        long now = Instant.parse("2026-11-03T07:00:00Z").toEpochMilli();
        List<Member> members = new ArrayList<>(); List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String player = "return-" + i;
            members.add(new Member(player, first));
            long activity = i < 10 ? lastDay : tooLate;
            sessions.add(session(player, activity, activity + QUALIFYING));
        }
        var data = new Dataset(members, sessions, List.of(new Span(epoch(date), now)));
        var report = calculate(date, date.plusDays(1), now, data);
        assertEquals(20L, section(report, "retention").get("eligible"));
        assertEquals(10L, section(report, "retention").get("returned"));
        assertEquals(0.5, section(report, "retention").get("rate"));
        long cutoff = Instant.parse("2026-11-02T07:00:00Z").toEpochMilli();
        assertNotNull(section(calculate(date, date.plusDays(1), cutoff, data), "retention").get("rate"));
        assertNull(section(calculate(date, date.plusDays(1), cutoff - 1, data), "retention").get("rate"));
    }

    @Test void monthRemainsPartialUntilLocalMonthEndsAndAdvertisesNewCalendarContract() {
        LocalDate date = LocalDate.of(2026, 10, 1), end = LocalDate.of(2026, 11, 1);
        long midnightUtc = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        var data = new Dataset(List.of(), group(epoch(date), epoch(date) + QUALIFYING),
                List.of(new Span(epoch(date), epoch(end))));
        var partial = calculate(date, end, midnightUtc, data);
        assertEquals(false, section(partial, "period").get("complete"));
        var complete = calculate(date, end, epoch(end), data);
        assertEquals(true, section(complete, "period").get("complete"));
        assertEquals("America/Vancouver", section(complete, "period").get("timezone"));
        assertEquals(2, complete.get("schema_version"));
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> heatmap(Map<String, Object> report) {
        return (List<Map<String, Object>>) section(report, "busy_times").get("cells");
    }
    private Object cell(List<Map<String, Object>> cells, int day, int hour) {
        return cells.get((day - 1) * 24 + hour).get("average_active_players");
    }

    @Test void heatmapAveragesActiveTimeAcrossAllWeekdayOccurrencesAndUnionsDuplicateSessions() {
        long from = epoch(LocalDate.of(2026, 10, 5)); // Monday, 00:00 Vancouver
        long play = from + 18 * 3_600_000L;
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) sessions.add(new Session("account-" + i, play, play + 2 * 3_600_000L,
                true, List.of(new Span(play, play + 1_800_000)))); // Ninety connected minutes are AFK.
        sessions.add(sessions.get(0));
        var report = calculate(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 19), from + 14 * DAY,
                new Dataset(List.of(), sessions, List.of(new Span(from, from + 14 * DAY))));
        var cells = heatmap(report);
        assertEquals(168, cells.size());
        assertEquals(2.5, cell(cells, 1, 18)); // 10 accounts × half an hour / two Monday-hour occurrences.
        assertEquals(0.0, cell(cells, 1, 19));
        assertEquals(0.0, cell(cells, 2, 18));
        assertEquals(Set.of("day", "hour", "average_active_players"), cells.get(0).keySet());
    }

    @Test void heatmapWithholdsSmallOrDominatedCellsWithoutCallingThemQuiet() {
        long from = epoch(LocalDate.of(2026, 10, 5));
        List<Session> sessions = group(from, from + QUALIFYING);
        for (int i = 0; i < 4; i++) sessions.add(session("account-" + i, from + 3_600_000, from + 3_600_000 + QUALIFYING));
        for (int i = 0; i < 5; i++) sessions.add(session("account-" + i, from + 2 * 3_600_000,
                from + 2 * 3_600_000 + (i == 0 ? 3_600_000 : QUALIFYING)));
        var report = calculate(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12), from + 7 * DAY,
                new Dataset(List.of(), sessions, List.of(new Span(from, from + 7 * DAY))));
        var cells = heatmap(report);
        assertEquals(168, cells.size());
        assertEquals(10 * QUALIFYING / 3_600_000.0, cell(cells, 1, 0));
        assertNull(cell(cells, 1, 1));
        assertNull(cell(cells, 1, 2));
        assertEquals(0.0, cell(cells, 1, 3));
    }

    @Test void heatmapRequiresCompleteWeekAndDisappearsWhenNoCellsCanBePublished() {
        var shortPeriod = calculate(START, START.plusDays(6), BASE + 7 * DAY,
                dataset(group(BASE, BASE + QUALIFYING), List.of(new Span(BASE, BASE + 7 * DAY))));
        assertTrue(heatmap(shortPeriod).isEmpty());
        var incomplete = calculate(START, START.plusDays(7), BASE + 6 * DAY,
                dataset(group(BASE, BASE + QUALIFYING), List.of(new Span(BASE, BASE + 7 * DAY))));
        assertTrue(heatmap(incomplete).isEmpty());
        List<Session> scattered = new ArrayList<>();
        for (int i = 0; i < 10; i++) scattered.add(session("account-" + i, BASE + i * 3_600_000L,
                BASE + i * 3_600_000L + QUALIFYING));
        var hidden = calculate(START, START.plusDays(7), BASE + 7 * DAY,
                dataset(scattered, List.of(new Span(BASE, BASE + 7 * DAY))));
        assertEquals(10, section(hidden, "summary").get("participants"));
        assertTrue(heatmap(hidden).isEmpty());
        var gap = calculate(START, START.plusDays(7), BASE + 7 * DAY,
                dataset(group(BASE, BASE + QUALIFYING), List.of(new Span(BASE, BASE + DAY), new Span(BASE + 2 * DAY, BASE + 7 * DAY))));
        assertTrue(heatmap(gap).isEmpty());
    }

    @Test void heatmapHistoricalRepeatedHourUsesBothOccurrencesInItsDenominator() {
        LocalDate date = LocalDate.of(2025, 10, 27);
        long play = Instant.parse("2025-11-02T08:00:00Z").toEpochMilli(); // First Sunday 01:00
        var report = calculate(date, date.plusDays(7), epoch(date.plusDays(7)),
                new Dataset(List.of(), group(play, play + 3_600_000), List.of(new Span(epoch(date), epoch(date.plusDays(7))))));
        assertEquals(5.0, cell(heatmap(report), 7, 1)); // Ten active accounts for only one of two hours.
    }

    @Test void heatmapSpringMissingHourIsUnobservedButNovemberHasAnOrdinary24HourDay() {
        LocalDate spring = LocalDate.of(2026, 3, 2);
        long play = Instant.parse("2026-03-08T09:00:00Z").toEpochMilli();
        var springReport = calculate(spring, spring.plusDays(7), epoch(spring.plusDays(7)),
                new Dataset(List.of(), group(play, play + 3_600_000), List.of(new Span(epoch(spring), epoch(spring.plusDays(7))))));
        assertEquals(10.0, cell(heatmap(springReport), 7, 1));
        assertNull(cell(heatmap(springReport), 7, 2));
        LocalDate autumn = LocalDate.of(2026, 10, 26);
        long ordinary = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli();
        var autumnReport = calculate(autumn, autumn.plusDays(7), epoch(autumn.plusDays(7)),
                new Dataset(List.of(), group(ordinary, ordinary + 3_600_000), List.of(new Span(epoch(autumn), epoch(autumn.plusDays(7))))));
        assertEquals(10.0, cell(heatmap(autumnReport), 7, 1));
    }
}
