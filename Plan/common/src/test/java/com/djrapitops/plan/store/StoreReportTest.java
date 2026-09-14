package com.djrapitops.plan.store;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.djrapitops.plan.store.StoreAnalyticsService.*;
import static com.djrapitops.plan.store.StoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreReportTest {
    private static final long NOW=Instant.parse("2026-09-14T12:00:00Z").getEpochSecond(),AT=NOW-2*86400;
    private static final UUID SERVER=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private StoreReport.Dataset data(){
        var o=new Event(1,AT,"order",order(AT,"USD",false));var payment=new Event(2,AT,"payment",payment(AT,"completed",null,false));
        var refund=new Event(3,AT+86400,"payment",payment(AT,"refunded","completed",false));
        var legacy=order(AT,null,true);legacy.put("order_id",OTHER);var l=new Event(4,AT,"order",legacy);
        return new StoreReport.Dataset(List.of(o,refund,l),List.of(new StoreReport.Observation(o,true),new StoreReport.Observation(payment,true),new StoreReport.Observation(payment,false),new StoreReport.Observation(refund,true),new StoreReport.Observation(l,true)),new StoreReport.Feed(NOW,NOW,NOW-200*86400,AT,1));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    @Test void refundedCurrentStateDoesNotErasePriorRecordedCompletionAndLegacyIsSeparate() throws Exception {
        var report=StoreReport.calculate(SERVER,NOW,30,"USD",data(),false,false);
        assertEquals(1L,obj(report.get("summary")).get("saved_orders"));assertEquals(500L,obj(report.get("summary")).get("order_amount_minor"));
        assertEquals(1,rows(report.get("daily")).stream().mapToLong(r->(long)r.get("completed_payments")).sum());
        assertEquals("refunded",rows(rows(report.get("currencies")).getFirst().get("payments")).getFirst().get("status"));
        assertEquals(true,obj(report.get("period")).get("complete"));
        java.nio.file.Path file=java.nio.file.Path.of("build/store-report-fixture.json");java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,StoreRecords.JSON.toJson(report));
    }
    @Test void allCurrenciesNeverProduceCombinedMoneyAndDaysAreCompleteUtcBoundaries(){
        var report=StoreReport.calculate(SERVER,NOW,90,null,data(),false,false);
        assertNull(obj(report.get("summary")).get("order_amount_minor"));assertEquals(1L,obj(report.get("summary")).get("baseline_orders"));
        assertEquals(90,rows(report.get("daily")).size());for(var day:rows(report.get("daily")))assertNull(day.get("completed_amount_minor"));
        assertEquals(0L,((long)obj(report.get("period")).get("until"))%86400000);
    }
    @Test void partialCoverageDisablesDeltasAndUnavailableNeverClaimsComplete(){
        var data=data();var partial=new StoreReport.Dataset(data.latest(),data.history(),new StoreReport.Feed(NOW,NOW,AT,AT,1));
        var report=StoreReport.calculate(SERVER,NOW,30,"USD",partial,false,false);assertEquals(false,obj(report.get("period")).get("complete"));assertEquals(false,obj(report.get("period")).get("previous_complete"));
        report=StoreReport.calculate(SERVER,NOW,30,"USD",partial,false,true);assertEquals("unavailable",obj(report.get("coverage")).get("status"));assertEquals(0L,obj(report.get("summary")).get("saved_orders"));
    }
    @Test void newApiRejectsDeepListsAndFractionalNumbers(){Object nested="x";for(int i=0;i<8;i++)nested=List.of(nested);Object finalNested=nested;assertThrows(IllegalArgumentException.class,()->new Event(1,1,"order",Map.of("lines",finalNested)));assertThrows(IllegalArgumentException.class,()->new Event(1,1,"order",Map.of("amount_cents",1.5)));}
    @Test void orderLinesMustBalanceAndUnknownLegacyDatesCannotBecomeProspective(){var r=order(AT,"USD",false);r.put("amount_cents",501L);assertThrows(IllegalArgumentException.class,()->StoreRecords.validate("order",r,NOW));r.put("amount_cents",500L);r.put("created_at",null);assertThrows(IllegalArgumentException.class,()->StoreRecords.validate("order",r,NOW));r.put("legacy",true);assertDoesNotThrow(()->StoreRecords.validate("order",r,NOW));}
    @Test void currentFinancialTransitionsCanRetainUnknownHistoricalCreationWithoutLosingTheirObservedTime(){
        var payment=payment(AT,"completed","pending",false);payment.put("created_at",null);
        assertDoesNotThrow(()->StoreRecords.validate("payment",payment,NOW));payment.put("state_recorded_at",null);
        assertThrows(IllegalArgumentException.class,()->StoreRecords.validate("payment",payment,NOW));
        var funding=StoreReport.map("order_id",ID,"payer_key",ID,"gross_cents",500L,"credit_cents",200L,"cash_cents",300L,"currency","USD","state","captured","created_at",null,"state_recorded_at",AT,"legacy",false);
        assertDoesNotThrow(()->StoreRecords.validate("credit_funding",funding,NOW));funding.put("state_recorded_at",null);
        assertThrows(IllegalArgumentException.class,()->StoreRecords.validate("credit_funding",funding,NOW));
    }
    @Test void distinctProductIdentitiesSharingALabelNeverCollapse(){
        var r=order(AT,"USD",false);r.put("amount_cents",1000L);r.put("lines",List.of(StoreReport.map("product_id",ID,"name","Example","quantity",1L,"unit_cents",500L),StoreReport.map("product_id",OTHER,"name","Example","quantity",1L,"unit_cents",500L)));
        var event=new Event(1,AT,"order",r);var data=new StoreReport.Dataset(List.of(event),List.of(),new StoreReport.Feed(NOW,NOW,AT,0,0));
        assertEquals(2,rows(StoreReport.calculate(SERVER,NOW,30,"USD",data,false,false).get("products")).size());
    }
}
