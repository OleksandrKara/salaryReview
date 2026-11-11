package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributionTest {

    @Test
    void googleAdsClickKeepsEveryParameterAndClassifiesAsAds() {
        Attribution a = Attribution.fromMap(Map.of("utm_source", "google", "utm_medium", "cpc", "utm_campaign", "pmu-brows",
                "utm_term", "microblading la", "utm_content", "ad1", "gclid", "G123",
                "landing_url", "https://pmu-annakara.com/?gclid=G123", "referrer", "https://www.google.com/"));
        assertThat(a.trafficSource()).isEqualTo("Google Ads");
        assertThat(a.hasMarkers()).isTrue();
        assertThat(a.toMap()).containsEntry("utm_term", "microblading la").containsEntry("gclid", "G123");
        assertThat(a.toJson()).contains("\"traffic_source\":\"Google Ads\"").contains("\"utm_content\":\"ad1\"");
    }

    @Test
    void organicGoogleReferrerWithoutMarkersIsOrganic() {
        Attribution a = Attribution.fromMap(Map.of("referrer", "https://www.google.com/"));
        assertThat(a.hasMarkers()).isFalse();
        assertThat(a.trafficSource()).isEqualTo("Google (organic)");
    }

    @Test
    void emptyAttributionHasNoJson() {
        assertThat(Attribution.fromMap(Map.of()).toJson()).isNull();
    }

    @Test
    void fillFromOnlyFillsBlanks() {
        Attribution tap = Attribution.fromMap(Map.of("utm_source", "google"));
        Attribution visit = Attribution.fromMap(Map.of("utm_source", "bing", "utm_term", "kw"));
        Attribution merged = tap.fillFrom(visit);
        assertThat(merged.utmSource()).isEqualTo("google");
        assertThat(merged.utmTerm()).isEqualTo("kw");
    }
}
