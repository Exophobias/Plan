package com.djrapitops.plan.referrals;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.djrapitops.plan.referrals.ReferralAnalyticsService.*;
import static com.djrapitops.plan.referrals.ReferralReport.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferralReportTest {
    private static final long JOIN = 1800000000000L;
    private static final UUID PLAYER = UUID.randomUUID(), SERVER = UUID.randomUUID();
    private static final String CLAIM = "a".repeat(32);
    private Claim claim(long requested) { return new Claim(CLAIM, PLAYER, "pending", requested, requested+1000,0,requested+7*DAY,true,false,false); }
    private Map<String,Object> report(long age, List<Session> activity, List<Span> coverage, List<Event> events) {
        return calculate(SERVER,JOIN+age*DAY,List.of(new Member(PLAYER.toString(),JOIN)),activity,coverage,events,
                new Feed(JOIN+age*DAY,JOIN-1,JOIN+age*DAY),JOIN+age*DAY,false);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> group(Map<String,Object> report, String name) {
        return ((List<Map<String,Object>>) report.get("comparison")).stream().filter(g -> name.equals(g.get("group"))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private Map<String,Object> retention(Map<String,Object> report, String group, String window) {
        return ((List<Map<String,Object>>) group(report,group).get("retention")).stream().filter(g -> window.equals(g.get("window"))).findFirst().orElseThrow();
    }
    @Test void elapsedCoveredZeroIsARealZeroWhileImmatureWindowIsNull() {
        Map<String,Object> report = report(10,List.of(),List.of(new Span(JOIN,JOIN+10*DAY)),List.of());
        assertEquals(0.0,retention(report,"no_recorded_referral","D7").get("rate"));
        assertNull(retention(report,"no_recorded_referral","D30").get("rate"));
        assertEquals(1L,retention(report,"no_recorded_referral","D30").get("incomplete"));
    }
    @Test void aMissingCollectionIntervalExcludesPlayerRatherThanDeclaresNoReturn() {
        Map<String,Object> report = report(10,List.of(),List.of(new Span(JOIN,JOIN+7*DAY),new Span(JOIN+7*DAY+1,JOIN+10*DAY)),List.of());
        assertNull(retention(report,"no_recorded_referral","D7").get("rate"));
    }
    @Test void activityCrossingWindowBoundaryUsesExactIntervalsAndDeduplicatesOverlappingSessions() {
        Session session = new Session(PLAYER.toString(),JOIN,JOIN+10*DAY,true,List.of(new Span(JOIN+7*DAY-100000,JOIN+7*DAY+RETURN_MINIMUM)));
        Map<String,Object> report = report(10,List.of(session,session),List.of(new Span(JOIN,JOIN+10*DAY)),List.of());
        assertEquals(1.0,retention(report,"no_recorded_referral","D7").get("rate"));
        assertEquals(RETURN_MINIMUM,overlap(merge(session.intervals()),JOIN+7*DAY,JOIN+8*DAY));
    }
    @Test void shortReturnDoesNotQualify() {
        Session session = new Session(PLAYER.toString(),JOIN,JOIN+8*DAY,true,List.of(new Span(JOIN+7*DAY,JOIN+7*DAY+RETURN_MINIMUM-1)));
        assertEquals(0.0,retention(report(10,List.of(session),List.of(new Span(JOIN,JOIN+10*DAY)),List.of()),"no_recorded_referral","D7").get("rate"));
    }
    @Test void claimOutcomeDoesNotSelectAcquisitionCohort() {
        Claim rejected = new Claim(CLAIM,PLAYER,"rejected",JOIN+1000,0,0,JOIN+7*DAY,true,false,false);
        Map<String,Object> report = report(10,List.of(),List.of(new Span(JOIN,JOIN+10*DAY)),List.of(new Event(1,JOIN+1000,rejected,null)));
        assertEquals(1,group(report,"recorded_referral").get("players"));
    }
    @Test void lateClaimsNeverRemovePlayersFromOriginalNoReferralComparator() {
        Map<String,Object> before = report(10,List.of(),List.of(new Span(JOIN,JOIN+10*DAY)),List.of());
        Map<String,Object> after = report(10,List.of(),List.of(new Span(JOIN,JOIN+10*DAY)),List.of(new Event(1,JOIN+2*DAY,claim(JOIN+2*DAY),null)));
        assertEquals(group(before,"no_recorded_referral").get("players"),group(after,"no_recorded_referral").get("players"));
        assertEquals(1,group(after,"late_referral").get("players"));
        assertEquals(0,group(after,"recorded_referral").get("players"));
    }
    @Test void unfinishedFeedAndPreCoverageJoinRemainUnknown() {
        Member m = new Member(PLAYER.toString(),JOIN);
        assertEquals("unknown",attribution(m,null,new Feed(JOIN+DAY-1,JOIN-1,JOIN+DAY),JOIN+2*DAY));
        assertEquals("unknown",attribution(m,null,new Feed(JOIN+2*DAY,JOIN+1,JOIN+2*DAY),JOIN+2*DAY));
        Claim legacy = new Claim(CLAIM,PLAYER,"successful",JOIN+1000,0,0,0,false,true,false);
        assertEquals("unknown",attribution(m,legacy,new Feed(JOIN+2*DAY,JOIN-1,JOIN+2*DAY),JOIN+2*DAY));
    }
    @Test void fixedHorizonMedianIncludesInactivePlayersAndCountsFiveMinuteDays() {
        assertEquals(5.0,median(new ArrayList<>(List.of(0.0,10.0))));
        assertNull(median(new ArrayList<>()));
        Map<String,Object> report = report(31,List.of(),List.of(new Span(JOIN,JOIN+31*DAY)),List.of());
        @SuppressWarnings("unchecked") List<Map<String,Object>> activity = (List<Map<String,Object>>) group(report,"no_recorded_referral").get("activity");
        assertEquals(0.0,activity.get(0).get("median_minutes"));
        assertEquals(0.0,activity.get(0).get("median_active_days"));
    }
    @Test void wilsonIntervalIncludesUncertaintyForSmallAllSuccessSamples() {
        double[] interval = wilson(1,1);
        assertEquals(1,interval[1],1e-10); assertTrue(interval[0] > .20 && interval[0] < .21);
        assertTrue(wilson(0,1)[1] > .79);
    }
    @Test void immutableCurrencyTotalsAndExplicitSharedAcquisitionDenominator() {
        Claim claim = claim(JOIN+1000);
        Award cad = new Award("cad",CLAIM,"credit","CAD",500,JOIN+2000,JOIN+4000,JOIN+3000,"delivered");
        Award usd = new Award("usd",CLAIM,"credit","USD",200,JOIN+2000,0,0,"pending");
        Session session = new Session(PLAYER.toString(),JOIN,JOIN+15*DAY,true,List.of(new Span(JOIN+7*DAY,JOIN+7*DAY+RETURN_MINIMUM)));
        Map<String,Object> report = report(15,List.of(session),List.of(new Span(JOIN,JOIN+15*DAY)),List.of(new Event(1,JOIN+1000,claim,null),new Event(2,JOIN+5000,null,cad),new Event(3,JOIN+5000,null,usd)));
        @SuppressWarnings("unchecked") List<Map<String,Object>> credits = (List<Map<String,Object>>)report.get("credit");
        assertEquals(2,credits.size());
        assertEquals(500L,credits.get(0).get("delivered_minor")); assertEquals(0L,credits.get(1).get("delivered_minor"));
        assertEquals("all_recorded_referral_players",credits.get(0).get("denominator_scope"));
        assertEquals(500.0,credits.get(0).get("granted_minor_per_retained"));
        assertEquals(200.0,credits.get(1).get("granted_minor_per_retained"));
    }
    @Test void issuedCreditWithUnknownPostingTimeDoesNotInventPostRewardReturnWindow() {
        Award credit = new Award("cad",CLAIM,"credit","CAD",500,JOIN+2000,JOIN+4000,0,"delivered");
        Map<String,Object> report = report(50,List.of(),List.of(new Span(JOIN,JOIN+50*DAY)),List.of(new Event(1,JOIN+1000,claim(JOIN+1000),null),new Event(2,JOIN+5000,null,credit)));
        @SuppressWarnings("unchecked") Map<String,Object> row = ((List<Map<String,Object>>)report.get("credit")).get(0);
        @SuppressWarnings("unchecked") Map<String,Object> post = (Map<String,Object>)row.get("post_reward");
        assertEquals(1,post.get("unknown_timing")); assertNull(post.get("rate")); assertEquals(500L,row.get("delivered_minor"));
    }
    @Test void lateCreditBeyondThirtyFiveDayActivityHorizonIsIncomplete() {
        Award credit = new Award("cad",CLAIM,"credit","CAD",500,JOIN+39*DAY,JOIN+40*DAY,JOIN+40*DAY,"delivered");
        Map<String,Object> report = report(50,List.of(),List.of(new Span(JOIN,JOIN+50*DAY)),List.of(new Event(1,JOIN+1000,claim(JOIN+1000),null),new Event(2,JOIN+40*DAY,null,credit)));
        @SuppressWarnings("unchecked") Map<String,Object> post = (Map<String,Object>)((List<Map<String,Object>>)report.get("credit")).get(0).get("post_reward");
        assertEquals(1L,post.get("incomplete")); assertNull(post.get("rate"));
    }
    @Test void fiveMinutePollingHasTenMinuteGraceAndThenReportsStale() {
        long now = JOIN+20*DAY;
        for (long age : new long[]{300001,600000,600001}) {
            Map<String,Object> report = calculate(SERVER,now,List.of(new Member(PLAYER.toString(),JOIN)),List.of(),
                    List.of(new Span(JOIN,now)),List.of(),new Feed(now-age,JOIN-1,now-age),now,false);
            @SuppressWarnings("unchecked") Map<String,Object> coverage = (Map<String,Object>)report.get("coverage");
            assertEquals(age>600000?"stale":"ready",coverage.get("status"));
        }
    }
    @Test void aKnownPreJoinClaimIsAcquisitionExposureWithoutTimestampRoundingPenalty() {
        assertEquals("recorded_referral",attribution(new Member(PLAYER.toString(),JOIN),claim(JOIN-1000),
                new Feed(JOIN+2*DAY,JOIN-DAY,JOIN+2*DAY),JOIN+2*DAY));
    }
    @Test void qualificationMedianUsesOnlyObservedCompletedClaimsAndNullForNoSample() {
        List<Event> events = new ArrayList<>();
        String[] states = {"successful","successful","pending","rejected","successful"};
        long[] hours = {2,6,0,0,100};
        for (int i=0;i<states.length;i++) {
            long qualified = hours[i] == 0 ? 0 : JOIN + hours[i]*3600000;
            Claim c = new Claim(String.valueOf(i).repeat(32),UUID.randomUUID(),states[i],JOIN,JOIN,qualified,0,i!=4,i==4,false);
            events.add(new Event(i+1,JOIN+10*DAY,c,null));
        }
        @SuppressWarnings("unchecked") Map<String,Object> funnel = (Map<String,Object>) report(10,List.of(),List.of(new Span(JOIN,JOIN+10*DAY)),events).get("funnel");
        assertEquals(5,funnel.get("requested")); assertEquals(3L,funnel.get("qualified"));
        assertEquals(1L,funnel.get("pending")); assertEquals(1L,funnel.get("rejected"));
        assertEquals(2,funnel.get("qualification_sample")); assertEquals(4.0,funnel.get("median_qualification_hours"));
        @SuppressWarnings("unchecked") Map<String,Object> empty = (Map<String,Object>) report(10,List.of(),List.of(),List.of()).get("funnel");
        assertEquals(0,empty.get("qualification_sample")); assertNull(empty.get("median_qualification_hours"));
    }
}
