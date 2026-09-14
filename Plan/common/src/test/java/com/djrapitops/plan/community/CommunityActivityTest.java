package com.djrapitops.plan.community;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommunityActivityTest {
    @AfterEach void paused() { CommunityCapture.pause(true, System.currentTimeMillis()); }
    private void start(long at) { CommunityCapture.pause(true, at - 1); CommunityCapture.pause(false, at); }

    @Test void activityRetainsBoundariesAndCoalescesAdjacentActiveDecisions() {
        start(1000);
        CommunityActivity activity = new CommunityActivity(1100);
        activity.classified(1200, true);
        activity.classified(1300, true);
        activity.classified(1800, false);
        activity.classified(1900, true);
        assertEquals(new CommunityActivity.Snapshot(1100, true, List.of(
                new CommunityActivity.Interval(1100, 1300), new CommunityActivity.Interval(1800, 1900))), activity.snapshot(1900));
    }

    @Test void firstObservationIsIndependentOfOldSessionAndBukkitFirstPlayed() {
        CommunityCapture.pause(true, 500);
        CommunityActivity existing = new CommunityActivity(600);
        CommunityCapture.pause(false, 1000);
        assertEquals(1000, existing.snapshot(1100).firstObserved());
        existing.classified(1200, true);
        assertEquals(List.of(new CommunityActivity.Interval(1000, 1200)), existing.snapshot(1200).intervals());
    }

    @Test void pauseNeverBecomesActiveTimeAndFixtureOnlySessionIsNotEnrolled() {
        start(1000);
        CommunityActivity normal = new CommunityActivity(1100);
        normal.classified(1200, true);
        CommunityCapture.pause(true, 1300);
        normal.classified(1400, true);
        CommunityActivity fixture = new CommunityActivity(1500);
        fixture.classified(1600, true);
        CommunityCapture.pause(false, 2000);
        normal.classified(2100, true);
        assertEquals(List.of(new CommunityActivity.Interval(1100, 1200), new CommunityActivity.Interval(2000, 2100)), normal.snapshot(2100).intervals());
        assertEquals(0, fixture.snapshot(1700).firstObserved());
    }

    @Test void referralPauseDoesNotControlCommunityHistory() {
        start(1000);
        com.djrapitops.plan.referrals.ReferralCapture.pause(true, 1000);
        CommunityActivity activity = new CommunityActivity(1100);
        activity.classified(1200, true);
        assertEquals(List.of(new CommunityActivity.Interval(1100, 1200)), activity.snapshot(1200).intervals());
    }

    @Test void clockRegressionAndIntervalOverflowInvalidateInsteadOfInventingActivity() {
        start(1000);
        CommunityActivity regression = new CommunityActivity(1100);
        regression.classified(1200, true);
        regression.classified(1199, true);
        assertFalse(regression.snapshot(1300).valid());
        CommunityActivity overflow = new CommunityActivity(1100);
        for (int i = 0; i < 16385; i++) {
            overflow.classified(1101 + 2L * i, true);
            overflow.classified(1102 + 2L * i, false);
        }
        assertFalse(overflow.snapshot(40000).valid());
        assertEquals(16384, overflow.snapshot(40000).intervals().size());
    }

    @Test void onlyADistinctProcessStartedAfterTheProvenStopCanCertifyDowntime() {
        assertTrue(CommunityAnalyticsSvc.validDowntime("old", 1000, "new", 2000, 3000));
        assertFalse(CommunityAnalyticsSvc.validDowntime("same", 1000, "same", 2000, 3000));
        assertFalse(CommunityAnalyticsSvc.validDowntime("old", 3000, "new", 2000, 4000));
        assertFalse(CommunityAnalyticsSvc.validDowntime("old", 1000, "new", 2000, 1500));
        assertFalse(CommunityAnalyticsSvc.validDowntime("old", 0, "new", 2000, 3000));
    }
}
