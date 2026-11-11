package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.salonreview.marketing.LeadFollowUpService.Touch;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeadTouchSourceTest {

    private static Map<String, String> payload(boolean matched) {
        Map<String, String> p = new LinkedHashMap<>();
        if (matched) p.put(LeadFollowUpService.TAP_MATCHED_KEY, "1");
        p.put("FromCity", "SAN DIEGO");
        return p;
    }

    @Test
    void textTiedToTapShowsItsSourceAndHidesTheFlag() {
        Touch t = LeadFollowUpService.touchOf(1, "SMS", "Google (organic)", null, "2026-11-11T11:29:00Z", payload(true));
        assertEquals("Google (organic)", t.source());
        assertEquals(Map.of("FromCity", "SAN DIEGO"), t.payload());
    }

    @Test
    void textWithoutMatchedTapShowsNoSourceNotTheFirstTouch() {
        assertNull(LeadFollowUpService.touchOf(2, "SMS", "Direct / No referrer", null, "x", payload(false)).source());
        assertNull(LeadFollowUpService.touchOf(3, "Text", "Direct / No referrer", null, "x", payload(false)).source());
        assertNull(LeadFollowUpService.touchOf(4, "SMS", "Direct", null, "x", null).source());
    }

    @Test
    void flagOnlyPayloadBecomesNull() {
        Touch t = LeadFollowUpService.touchOf(5, "SMS", "Google (organic)", null, "x", Map.of(LeadFollowUpService.TAP_MATCHED_KEY, "1"));
        assertEquals("Google (organic)", t.source());
        assertNull(t.payload());
    }

    @Test
    void otherChannelsKeepTheirSource() {
        Touch t = LeadFollowUpService.touchOf(6, "Form", "Instagram", "x", "y", null);
        assertEquals("Instagram", t.source());
    }
}
