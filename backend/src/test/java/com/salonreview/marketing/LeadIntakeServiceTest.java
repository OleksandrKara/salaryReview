package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LeadIntakeServiceTest {

    @Test
    void sourceLabels() {
        assertEquals("Google Ads", LeadIntakeService.sourceLabel("Google Ads (pmu-book)"));
        assertEquals("Meta Ads", LeadIntakeService.sourceLabel("Meta Ads (x)"));
        assertEquals("Instagram", LeadIntakeService.sourceLabel("Instagram (organic)"));
        assertEquals("Google organic", LeadIntakeService.sourceLabel("Google (organic)"));
        assertEquals("Direct", LeadIntakeService.sourceLabel("Direct / No referrer"));
        assertEquals("Unknown", LeadIntakeService.sourceLabel(null));
    }

    @Test
    void phonesAndNames() {
        assertEquals("+16195551234", LeadIntakeService.validPhone("(619) 555-1234"));
        assertNull(LeadIntakeService.validPhone("12345"));
        assertNull(LeadIntakeService.validPhone(null));
        assertArrayEquals(new String[] {"Anna", "Lee Kim"}, LeadIntakeService.splitName("Anna Lee Kim"));
        assertArrayEquals(new String[] {null, null}, LeadIntakeService.splitName(" "));
        assertEquals(2, LeadIntakeService.parseIds("1, 2,x").size());
    }

    @Test
    void foreignPhonesAreKeptSoRowsOfOnePersonMerge() {
        assertEquals("+989118522298", LeadIntakeService.foreignPhone("+98 911 852 2298"));
        assertNull(LeadIntakeService.foreignPhone("(619) 555-1234"));
        assertNull(LeadIntakeService.foreignPhone("+16195551234"));
        assertNull(LeadIntakeService.foreignPhone("12345"));
        assertNull(LeadIntakeService.foreignPhone(null));
    }
}
