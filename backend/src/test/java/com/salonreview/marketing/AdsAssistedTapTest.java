package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** SMS tap: the label is the tap's own visit; the stored first-touch ad params only become the ad history. */
@SpringBootTest
class AdsAssistedTapTest {

    private static final long BUSINESS = 987655L;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SmsWebsiteLeadService service;

    private SmsWebsiteLeadService.Capture tap(String phone, String metadata) {
        UUID visitor = UUID.randomUUID();
        jdbc.update("INSERT INTO marketing.events (session_id, event_type, metadata, business_id) VALUES (?, 'click', ?::jsonb, ?)",
                visitor, metadata, BUSINESS);
        return service.captureDetailed(BUSINESS, phone, "Hi! I have a question about brows.").orElseThrow();
    }

    private void clean(String phone) {
        jdbc.update("DELETE FROM marketing.contacts WHERE business_id = ? AND phone_number = ?", BUSINESS, phone);
        jdbc.update("DELETE FROM marketing.events WHERE business_id = ?", BUSINESS);
    }

    private static final String STORED = "\"gclid\":\"G1\",\"utm_source\":\"google\",\"utm_medium\":\"cpc\",\"utm_campaign\":\"academy\","
            + "\"first_touch_at\":1762560000000,\"landing_path\":\"/academy/\"";

    @Test
    void sameVisitAdLandingMovesToAnotherPage() {
        String phone = "+15550004001";
        try {
            var c = tap(phone, "{\"target\":\"sms\",\"path\":\"/brows/\"," + STORED
                    + ",\"landing_url\":\"/academy/?gclid=G1&utm_source=google&utm_medium=cpc&utm_campaign=academy\",\"visit_referrer\":\"https://www.google.com/\"}");
            assertThat(c.trafficSource()).isEqualTo("Google Ads");
            assertThat(c.attribution()).contains("\"gclid\":\"G1\"", "\"utm_campaign\":\"academy\"");
        } finally {
            clean(phone);
        }
    }

    @Test
    void organicReturnIsOrganicButKeepsAdHistory() {
        String phone = "+15550004002";
        try {
            var c = tap(phone, "{\"target\":\"sms\",\"path\":\"/brows/\"," + STORED
                    + ",\"landing_url\":\"/brows/\",\"visit_referrer\":\"https://www.google.com/\"}");
            assertThat(c.trafficSource()).isEqualTo("Google (organic)");
            assertThat(c.attribution()).contains("\"traffic_source\":\"Google (organic)\"", "\"ad_first_source\":\"Google Ads\"",
                    "\"ad_first_campaign\":\"academy\"", "\"ad_first_at\":\"2025-11-08T00:00:00Z\"").doesNotContain("\"gclid\"");
        } finally {
            clean(phone);
        }
    }

    @Test
    void directReturnIsDirectButKeepsAdHistory() {
        String phone = "+15550004003";
        try {
            var c = tap(phone, "{\"target\":\"sms\",\"path\":\"/brows/\"," + STORED + ",\"landing_url\":\"/brows/\",\"visit_referrer\":\"\"}");
            assertThat(c.trafficSource()).isEqualTo("Direct / No referrer");
            assertThat(c.attribution()).contains("\"ad_first_source\":\"Google Ads\"");
        } finally {
            clean(phone);
        }
    }

    @Test
    void noAdEverHasNoAdHistory() {
        String phone = "+15550004004";
        try {
            var c = tap(phone, "{\"target\":\"sms\",\"path\":\"/brows/\",\"landing_url\":\"/brows/\",\"visit_referrer\":\"https://www.google.com/\"}");
            assertThat(c.trafficSource()).isEqualTo("Google (organic)");
            assertThat(c.attribution()).doesNotContain("ad_first");
        } finally {
            clean(phone);
        }
    }
}
