package com.salonreview.landing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingLeadAttributionTest {

    private static LandingTracking tracking(String landingUrl, String utmCampaign, String utmTerm, String gclid) {
        return new LandingTracking("v", "/book/", null, utmCampaign == null ? null : "google", utmCampaign == null ? null : "cpc",
                utmCampaign, utmTerm, null, null, gclid, null, null, null, null, null, landingUrl, null, false);
    }

    @Test
    void campaignAndKeywordComeFromTheVisitLandingUrl() {
        String url = "/book/?utm_term=nano%20brows&utm_campaign=Inweb_Search_Ombre_Powder_Nano&utm_source=google&utm_medium=cpc&gclid=abc";
        String json = LandingPmuBookingService.attributionOf(tracking(url, null, null, null));
        assertNotNull(json);
        assertTrue(json.contains("\"utm_campaign\":\"Inweb_Search_Ombre_Powder_Nano\""), json);
        assertTrue(json.contains("\"utm_term\":\"nano brows\""), json);
        assertTrue(json.contains("Google Ads"), json);
    }

    @Test
    void fallsBackToTheStoredFirstTouchWhenNoLandingUrlWasSent() {
        String json = LandingPmuBookingService.attributionOf(tracking(null, "Inweb_PMax_Permanent_Ombre", null, "abc"));
        assertNotNull(json);
        assertTrue(json.contains("Inweb_PMax_Permanent_Ombre"), json);
    }

    @Test
    void noTrackingMeansNoAttribution() {
        assertNull(LandingPmuBookingService.attributionOf(null));
    }
}
