package com.djrapitops.plan.referrals;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.djrapitops.plan.referrals.ReferralAnalyticsService.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferralJournalTest {
    private final Claim claim = new Claim("a".repeat(32),UUID.randomUUID(),"pending",1000,1100,0,9000,true,false,false);
    private Batch batch(long from,long through,long high,boolean more,List<Event> events) {
        return new Batch("b".repeat(32),from,through,high,5000,100,more,events);
    }
    @Test void validatesFreshPageAndCaughtUpEmptyHeartbeat() {
        assertDoesNotThrow(() -> ReferralJournal.validate(batch(0,1,1,false,List.of(new Event(1,2000,claim,null))),6000));
        assertDoesNotThrow(() -> ReferralJournal.validate(batch(1,1,1,false,List.of()),6000));
    }
    @Test void rejectsSkippedSequencesAndEmptyIncompletePages() {
        assertThrows(IllegalArgumentException.class,() -> ReferralJournal.validate(batch(0,2,2,false,List.of(new Event(2,2000,claim,null))),6000));
        assertThrows(IllegalArgumentException.class,() -> ReferralJournal.validate(batch(0,0,2,true,List.of()),6000));
    }
    @Test void rejectsPhantomAmountsAndDeliveryStateMismatch() {
        Award bad = new Award("award",claim.claimId,"credit","CAD",-1,1000,2000,1500,"delivered");
        assertThrows(IllegalArgumentException.class,() -> ReferralJournal.validate(batch(0,1,1,false,List.of(new Event(1,2000,null,bad))),6000));
        Award mismatch = new Award("award",claim.claimId,"credit","CAD",1,1000,0,0,"delivered");
        assertThrows(IllegalArgumentException.class,() -> ReferralJournal.validate(batch(0,1,1,false,List.of(new Event(1,2000,null,mismatch))),6000));
    }
    @Test void unknownIssuedTimeKeepsVerifiedDeliveredCreditValid() {
        Award a = new Award("award",claim.claimId,"credit","CAD",500,1000,2000,0,"delivered");
        assertDoesNotThrow(() -> ReferralJournal.validate(batch(0,1,1,false,List.of(new Event(1,2000,null,a))),6000));
    }
    @Test void provenDowntimeRequiresAChangedJvmAndValidChronology() {
        assertTrue(ReferralAnalyticsSvc.validDowntime("old",1000,"new",2000,3000));
        assertFalse(ReferralAnalyticsSvc.validDowntime("same",1000,"same",2000,3000));
        assertFalse(ReferralAnalyticsSvc.validDowntime("old",3000,"new",2000,4000));
        assertFalse(ReferralAnalyticsSvc.validDowntime("old",1000,"new",2000,1500));
    }
}
