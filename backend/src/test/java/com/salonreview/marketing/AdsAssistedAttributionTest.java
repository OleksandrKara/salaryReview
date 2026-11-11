package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.landing.Attribution;
import com.salonreview.landing.TrafficSource;
import com.salonreview.landing.VisitAttribution;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Scenario table for per-visit attribution + ad history. The browser stores the first-touch ad params for 180 days and
 * sends them with every conversion; the label of a conversion must come from THIS visit only.
 */
class AdsAssistedAttributionTest {

    private static final long NOV8 = 1_762_560_000_000L;

    private static AcademyLeadService.AcademyLead lead(String landingUrl, String visitReferrer, String storedGclid,
            String storedSource, String storedMedium, String storedCampaign, Long capturedAt) {
        return new AcademyLeadService.AcademyLead("course_request", "Jane", "+16195550100", "jane@example.com", "brows",
                "Brows", null, true, true, "https://pmu-annakara.com/academy/brows/", storedSource, storedMedium,
                storedCampaign, storedGclid, null, null, null, "https://www.google.com/", null, null, "/academy/brows/",
                landingUrl, visitReferrer, capturedAt == null ? null : String.valueOf(capturedAt), "/academy/");
    }

    @Test
    void a_adLandingThenConvertOnAnotherPageSameVisit() {
        var l = lead("/academy/?gclid=G1&utm_source=google&utm_medium=cpc&utm_campaign=academy", "https://www.google.com/",
                "G1", "google", "cpc", "academy", NOV8);
        assertThat(AcademyLeadService.trafficSource(l)).isEqualTo("Google Ads");
        Map<String, String> history = AcademyLeadService.adHistory(l);
        assertThat(history).containsEntry("ad_first_source", "Google Ads").containsEntry("ad_first_campaign", "academy")
                .containsEntry("ad_first_click_id", "G1").containsKey("ad_first_at");
        assertThat(AcademyLeadService.currentVisit(l).toMap()).containsEntry("gclid", "G1").containsEntry("utm_campaign", "academy");
    }

    @Test
    void b_adLandingThenOrganicReturnDaysLater() {
        var l = lead("/academy/brows/", "https://www.google.com/", "G1", "google", "cpc", "academy", NOV8);
        assertThat(AcademyLeadService.trafficSource(l)).isEqualTo("Google (organic)");
        assertThat(AcademyLeadService.adHistory(l)).containsEntry("ad_first_source", "Google Ads")
                .containsEntry("ad_first_campaign", "academy").containsEntry("ad_first_at", "2025-11-08T00:00:00Z");
        var json = AcademyLeadService.currentVisit(l).toJson(AcademyLeadService.adHistory(l));
        assertThat(json).contains("\"traffic_source\":\"Google (organic)\"", "\"ad_first_source\":\"Google Ads\"")
                .doesNotContain("\"gclid\"");
    }

    @Test
    void c_adLandingThenDirectReturn() {
        var l = lead("/academy/brows/", "", "G1", "google", "cpc", "academy", NOV8);
        assertThat(AcademyLeadService.trafficSource(l)).isEqualTo(TrafficSource.DIRECT);
        assertThat(AcademyLeadService.adHistory(l)).containsEntry("ad_first_source", "Google Ads");
    }

    @Test
    void d_secondAdClickOverwritesStoredButFirstStaysInTheLeadsFirstRow() {
        // first conversion: stored = click A
        var first = lead("/academy/?gclid=A&utm_source=google&utm_medium=cpc&utm_campaign=camp-a", "https://www.google.com/",
                "A", "google", "cpc", "camp-a", NOV8);
        // later conversion after a second ad click: stored now = click B (browser overwrote it)
        var second = lead("/academy/?gclid=B&utm_source=google&utm_medium=cpc&utm_campaign=camp-b", "https://www.google.com/",
                "B", "google", "cpc", "camp-b", NOV8 + 3 * 86_400_000L);
        assertThat(AcademyLeadService.adHistory(first)).containsEntry("ad_first_campaign", "camp-a").containsEntry("ad_first_click_id", "A");
        assertThat(AcademyLeadService.adHistory(second)).containsEntry("ad_first_campaign", "camp-b");
        assertThat(AcademyLeadService.trafficSource(second)).isEqualTo("Google Ads");
    }

    @Test
    void e_noAdEver() {
        var l = lead("/academy/brows/", "https://www.google.com/", null, null, null, null, null);
        assertThat(AcademyLeadService.trafficSource(l)).isEqualTo("Google (organic)");
        assertThat(AcademyLeadService.adHistory(l)).isEmpty();
        assertThat(AcademyLeadService.currentVisit(l).toJson(AcademyLeadService.adHistory(l))).doesNotContain("ad_first");
    }

    @Test
    void oldClientWithoutVisitFieldsKeepsLegacyLabel() {
        var old = new AcademyLeadService.AcademyLead("course_request", "Jane", "+16195550100", "j@example.com", "brows", "Brows",
                null, true, true, null, "google", "cpc", "academy", "G1", null, null, null, "https://www.google.com/", null, null, null);
        assertThat(old.hasVisit()).isFalse();
        assertThat(AcademyLeadService.trafficSource(old)).isEqualTo("Google Ads");
    }

    @Test
    void storedAttributionThatIsNotPaidMakesNoAdHistory() {
        var stored = new Attribution("instagram", "social", null, null, null, null, null, null, null, "fb1", null, null, null);
        assertThat(VisitAttribution.adHistory(stored, null, "/")).isEmpty();
    }

    @Test
    void bookingSubmissionLabelUsesTheVisitNotTheStoredSnapshot() {
        var organicReturn = new com.salonreview.landing.LandingTracking("v", "/academy/", "https://www.google.com/", "google", "cpc",
                "academy", null, null, null, "G1", null, null, null, null, null, "/book/", "https://www.google.com/", false);
        assertThat(VisitAttribution.conversionSource(organicReturn)).isEqualTo("Google (organic)");
        assertThat(TrafficSource.classify(organicReturn)).isEqualTo("Google Ads");
        var oldClient = new com.salonreview.landing.LandingTracking("v", "/academy/", null, "google", "cpc", "academy", null, null,
                null, "G1", null, null);
        assertThat(VisitAttribution.conversionSource(oldClient)).isEqualTo("Google Ads");
    }
}
