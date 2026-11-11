package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** A Google Ads landing (gclid + UTMs, term/content only on the stored visit) tapped to SMS keeps every parameter. */
@SpringBootTest
class GoogleAdsTapAttributionTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SmsWebsiteLeadService service;

    @Test
    void adsTapKeepsAllUtmsAndClassifiesAsGoogleAds() {
        long business = 987654L;
        UUID visitor = UUID.randomUUID();
        String phone = "+15550001234";
        try {
            jdbc.update("""
                    INSERT INTO marketing.visits (visitor_id, business_id, is_first_visit, utm_source, utm_medium, utm_campaign,
                        utm_term, utm_content, gclid, landing_url, referrer)
                    VALUES (?, ?, true, 'google', 'cpc', 'pmu-brows', 'microblading la', 'ad1', 'G-TEST-1',
                        'https://pmu-annakara.com/?gclid=G-TEST-1&utm_source=google', 'https://www.google.com/')
                    """, visitor, business);
            jdbc.update("""
                    INSERT INTO marketing.events (session_id, event_type, metadata, business_id)
                    VALUES (?, 'click', ?::jsonb, ?)
                    """, visitor, "{\"target\":\"sms\",\"path\":\"/brows/\",\"gclid\":\"G-TEST-1\",\"utm_source\":\"google\","
                    + "\"utm_medium\":\"cpc\",\"utm_campaign\":\"pmu-brows\"}", business);

            var capture = service.captureDetailed(business, phone, "Hi! I have a question about brows.");

            assertThat(capture).isPresent();
            assertThat(capture.get().trafficSource()).isEqualTo("Google Ads");
            assertThat(capture.get().attribution()).contains("\"utm_term\":\"microblading la\"", "\"utm_content\":\"ad1\"",
                    "\"gclid\":\"G-TEST-1\"", "\"traffic_source\":\"Google Ads\"");
        } finally {
            jdbc.update("DELETE FROM marketing.contacts WHERE business_id = ? AND phone_number = ?", business, phone);
            jdbc.update("DELETE FROM marketing.events WHERE business_id = ?", business);
            jdbc.update("DELETE FROM marketing.visits WHERE business_id = ?", business);
        }
    }
}
