package com.djrapitops.plan.community;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Time-weighted weekday/hour activity, with disclosure checks independent for every cell. */
final class CommunityBusyTimes {
    private static final long HOUR = 3_600_000L;
    private static final int CELLS = 7 * 24;
    private CommunityBusyTimes() { }

    static List<Map<String, Object>> calculate(Map<String, List<CommunityReport.Span>> activity,
                                               long from, long until) {
        long[] exposure = new long[CELLS], total = new long[CELLS], largest = new long[CELLS];
        int[] qualifyingAccounts = new int[CELLS];
        accumulate(exposure, from, until);
        for (List<CommunityReport.Span> intervals : activity.values()) {
            long[] personal = new long[CELLS];
            // The caller has already unioned each account's intervals, including overlapping sessions.
            for (CommunityReport.Span span : intervals)
                accumulate(personal, Math.max(from, span.start()), Math.min(until, span.end()));
            for (int cell = 0; cell < CELLS; cell++) {
                total[cell] = Math.addExact(total[cell], personal[cell]);
                largest[cell] = Math.max(largest[cell], personal[cell]);
                if (personal[cell] >= CommunityReport.QUALIFYING) qualifyingAccounts[cell]++;
            }
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        boolean useful = false;
        for (int cell = 0; cell < CELLS; cell++) {
            Double average = null;
            if (exposure[cell] > 0) {
                if (total[cell] == 0) average = 0.0;
                else if (qualifyingAccounts[cell] >= CommunityReport.MIN_CELL && largest[cell] <= total[cell] / 2) {
                    average = total[cell] / (double) exposure[cell];
                    useful = true;
                }
            }
            cells.add(CommunityReport.map("day", cell / 24 + 1, "hour", cell % 24,
                    "average_active_players", average));
        }
        return useful ? cells : List.of();
    }

    private static void accumulate(long[] target, long from, long until) {
        long cursor = from;
        while (cursor < until) {
            // Vancouver offsets since the supported year 2000 are whole hours. UTC hour boundaries
            // therefore align, including both occurrences of the old autumn repeated local hour.
            long next = Math.min(until, (cursor / HOUR + 1) * HOUR);
            int cell = CommunityCalendar.hourOfWeek(cursor);
            target[cell] = Math.addExact(target[cell], next - cursor);
            cursor = next;
        }
    }
}
