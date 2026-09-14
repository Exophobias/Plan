package com.djrapitops.plan.referrals;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferralActivityTest {
    @BeforeEach void enable() { ReferralCapture.pause(true,0); ReferralCapture.pause(false,0); }
    @AfterEach void reset() { ReferralCapture.pause(true,System.currentTimeMillis()); }
    @Test void intervalsKeepActualAfkBoundaries() {
        ReferralActivity activity = new ReferralActivity(0);
        activity.classified(50,true); activity.classified(100,false); activity.classified(150,true);
        assertEquals(2,activity.snapshot().intervals().size());
        assertEquals(new ReferralActivity.Interval(100,150),activity.snapshot().intervals().get(1));
    }
    @Test void briefPauseBetweenCollectorRunsCannotBackfillFixtureActivity() {
        ReferralActivity activity = new ReferralActivity(0);
        activity.classified(50,true); ReferralCapture.pause(true,60); ReferralCapture.pause(false,100);
        activity.classified(150,true);
        assertEquals(java.util.List.of(new ReferralActivity.Interval(0,50),new ReferralActivity.Interval(100,150)),activity.snapshot().intervals());
    }
    @Test void pausedCaptureIsEmptyAndRepeatedAdmissionDoesNotResetEpoch() {
        ReferralCapture.pause(true,10); ReferralActivity activity = new ReferralActivity(10); activity.classified(100,true);
        assertTrue(activity.snapshot().intervals().isEmpty());
        ReferralCapture.pause(false,100); long generation = ReferralCapture.state().generation(); ReferralCapture.pause(false,200);
        assertEquals(generation,ReferralCapture.state().generation()); assertEquals(100,ReferralCapture.state().changedAt());
    }
    @Test void boundedHistoryOverflowFailsClosed() {
        ReferralActivity activity = new ReferralActivity(0);
        for (int i=1;i<33000;i++) activity.classified(i,i%2==0);
        assertFalse(activity.snapshot().valid()); assertTrue(activity.snapshot().intervals().size()<=16384);
    }
    @Test void backwardClockDoesNotCreateActivity() {
        ReferralActivity activity = new ReferralActivity(100); activity.classified(99,true);
        assertFalse(activity.snapshot().valid()); assertTrue(activity.snapshot().intervals().isEmpty());
    }
}
