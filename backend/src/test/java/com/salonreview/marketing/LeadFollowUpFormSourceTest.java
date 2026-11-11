package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LeadFollowUpFormSourceTest {

    @Test
    void googleClickIdWins() {
        assertEquals("Google Ads", LeadFollowUpService.academySource("abc", "Direct / No referrer", "google"));
    }

    @Test
    void classifiedSourceOfTheSubmissionIsShown() {
        assertEquals("Google (organic)", LeadFollowUpService.academySource(null, "Google (organic)", null));
        assertEquals("Direct / No referrer", LeadFollowUpService.academySource("", "Direct / No referrer", null));
    }

    @Test
    void fallsBackToUtmSourceThenNothing() {
        assertEquals("newsletter", LeadFollowUpService.academySource(null, "Unknown", "newsletter"));
        assertNull(LeadFollowUpService.academySource(null, null, null));
    }
}
