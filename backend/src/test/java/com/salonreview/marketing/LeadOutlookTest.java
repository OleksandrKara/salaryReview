package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadOutlookTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");

    private static LeadFollowUpService.Appointment appt(String status, String start, String kind, String price) {
        return new LeadFollowUpService.Appointment("a", status, start, "Powder Brows", "Anna", 120, null, kind, null, null,
                price == null ? null : new BigDecimal(price));
    }

    private static LeadFollowUpService.Payment pay(String at, String amount) {
        return new LeadFollowUpService.Payment("p", at, new BigDecimal(amount), BigDecimal.ZERO, null);
    }

    private static LeadFollowUpService.Spend spend(String total) {
        return new LeadFollowUpService.Spend(new BigDecimal(total), BigDecimal.ZERO, 1, null, null, null);
    }

    @Test
    void depositAndRemainingForTheNextProcedure() {
        var o = LeadFollowUpService.outlook(
                List.of(appt("ACCEPTED", "2026-11-28T18:00:00Z", null, "500"),
                        appt("ACCEPTED", "2026-11-10T18:00:00Z", "OFFLINE", null)),
                spend("100"), List.of(pay("2026-11-15T10:00:00Z", "100")), NOW);
        assertEquals(1, o.upcoming().size());
        assertEquals(0, new BigDecimal("500").compareTo(o.estimate()));
        assertEquals(0, new BigDecimal("100").compareTo(o.prepaid()));
        assertEquals(0, new BigDecimal("400").compareTo(o.remaining()));
        assertEquals(0, new BigDecimal("100").compareTo(o.collected()));
        assertFalse(o.partial());
    }

    @Test
    void paymentsBeforeTheLastVisitAreNotAdvanceMoney() {
        var o = LeadFollowUpService.outlook(
                List.of(appt("ACCEPTED", "2026-12-05T18:00:00Z", null, "300"),
                        appt("ACCEPTED", "2026-10-01T18:00:00Z", null, "300")),
                spend("400"), List.of(pay("2026-09-20T10:00:00Z", "100")), NOW);
        assertEquals(0, BigDecimal.ZERO.compareTo(o.prepaid()));
        assertEquals(0, new BigDecimal("300").compareTo(o.remaining()));
        assertEquals(0, new BigDecimal("400").compareTo(o.collected()));
    }

    @Test
    void missingCatalogPriceMarksTheEstimatePartial() {
        var o = LeadFollowUpService.outlook(List.of(appt("ACCEPTED", "2026-12-05T18:00:00Z", null, null)), spend("0"), List.of(), NOW);
        assertTrue(o.partial());
        assertEquals(1, o.upcoming().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(o.remaining()));
    }

    @Test
    void nothingAheadMeansNothingToCollect() {
        var o = LeadFollowUpService.outlook(List.of(appt("CANCELLED_BY_CUSTOMER", "2026-12-05T18:00:00Z", null, "300")), spend("0"), List.of(), NOW);
        assertTrue(o.upcoming().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(o.remaining()));
    }
}
