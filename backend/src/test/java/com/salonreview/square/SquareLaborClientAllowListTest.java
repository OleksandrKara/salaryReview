package com.salonreview.square;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquareLaborClientAllowListTest {

    @Test
    void allowsOnlyLaborTimecardsAndBreakTypes() {
        assertTrue(SquareLaborClient.isAllowed(HttpMethod.POST, "/v2/labor/timecards/search"));
        assertTrue(SquareLaborClient.isAllowed(HttpMethod.POST, "/v2/labor/timecards"));
        assertTrue(SquareLaborClient.isAllowed(HttpMethod.PUT, "/v2/labor/timecards/abc123"));
        assertTrue(SquareLaborClient.isAllowed(HttpMethod.GET, "/v2/labor/break-types"));
        assertTrue(SquareLaborClient.isAllowed(HttpMethod.POST, "/v2/labor/break-types"));
    }

    @Test
    void refusesEverythingElse() {
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.DELETE, "/v2/labor/timecards/abc123"));
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.PUT, "/v2/labor/break-types/abc"));
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.POST, "/v2/payments"));
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.POST, "/v2/bookings"));
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.PUT, "/v2/labor/timecards/../payments"));
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.POST, "/v2/labor/shifts"));
        assertFalse(SquareLaborClient.isAllowed(HttpMethod.GET, "/v2/labor/timecards?x=1"));
    }
}
