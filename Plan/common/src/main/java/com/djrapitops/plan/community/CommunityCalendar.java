package com.djrapitops.plan.community;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Vancouver report dates, independent of the server's default zone and stale runtime tzdata. */
final class CommunityCalendar {
    static final String TIMEZONE = "America/Vancouver";
    private static final ZoneId VANCOUVER = ZoneId.of(TIMEZONE);
    private static final ZoneOffset PACIFIC = ZoneOffset.ofHours(-7);
    // B.C. Reg. 20/2026: the March 8 spring change is the last; November does not fall back.
    // Keep historical IANA rules before the change, including March 8's midnight at UTC-8.
    // Explicit current rules also cover supported JVMs whose bundled tzdata predates the decision.
    private static final Instant PERMANENT_FROM = Instant.parse("2026-03-08T10:00:00Z");
    private static final LocalDate FIRST_FULL_PERMANENT_DAY = LocalDate.of(2026, 3, 9);

    private CommunityCalendar() { }

    static LocalDate date(long epochMillis) {
        return at(epochMillis).toLocalDate();
    }

    static int hourOfWeek(long epochMillis) {
        ZonedDateTime time = at(epochMillis);
        return (time.getDayOfWeek().getValue() - 1) * 24 + time.getHour();
    }

    private static ZonedDateTime at(long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        return instant.atZone(instant.isBefore(PERMANENT_FROM) ? VANCOUVER : PACIFIC);
    }

    static long start(LocalDate date) {
        return date.atStartOfDay(date.isBefore(FIRST_FULL_PERMANENT_DAY) ? VANCOUVER : PACIFIC)
                .toInstant().toEpochMilli();
    }
}
