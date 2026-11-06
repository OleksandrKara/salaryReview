package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadCloseReadyTest {
    private static Timestamp ago(int days) {
        return Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    }

    @Test
    void threeAttemptsClose() {
        assertTrue(LeadFollowUpService.closeReady("CONTACTED", 3, ago(1)));
    }

    @Test
    void twoAttemptsCloseOnlyAfterAWeek() {
        assertFalse(LeadFollowUpService.closeReady("CONTACTED", 2, ago(3)));
        assertTrue(LeadFollowUpService.closeReady("CONTACTED", 2, ago(8)));
    }

    @Test
    void closedLeadsNeverSuggested() {
        assertFalse(LeadFollowUpService.closeReady("LOST", 5, ago(9)));
        assertFalse(LeadFollowUpService.closeReady("BOOKED", 3, ago(1)));
    }
}
