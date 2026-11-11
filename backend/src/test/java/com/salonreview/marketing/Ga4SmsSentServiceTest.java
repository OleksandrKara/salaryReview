package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Ga4SmsSentServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private JdbcTemplate jdbc;

    private final List<String> urls = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int status = 204;

    private Ga4SmsSentService service() {
        return new Ga4SmsSentService(jdbc, Map.of(2L, new String[] {"G-TEST", "sekret"}), (url, body) -> {
            urls.add(url);
            bodies.add(body);
            if (status < 0) throw new java.io.IOException("connection reset");
            return status;
        });
    }

    private static Ga4SmsSentService.Tap tap(String gaClient, String session) {
        return new Ga4SmsSentService.Tap(gaClient, session, "visitor-uuid", "g1", null, null, "Google Ads (google / cpc / brows)",
                "google", "cpc", "brows", "https://google.com/", "pmu-website", "/academy/", "/academy/courses/", "hero", "Courses");
    }

    private int count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM ga4_sms_sent_events WHERE status = ?", Integer.class, status);
    }

    @Test
    void sendsOnceWithGaClientSessionAndAttribution() throws Exception {
        Ga4SmsSentService s = service();
        s.record(2L, "ev-1", tap("123.456", "1700000000"), Instant.parse("2026-11-11T20:30:00Z"));
        s.record(2L, "ev-1", tap("123.456", "1700000000"), Instant.parse("2026-11-11T20:31:00Z"));
        s.sendPending();

        assertThat(bodies).hasSize(1);
        assertThat(urls.get(0)).isEqualTo("https://www.google-analytics.com/mp/collect?measurement_id=G-TEST&api_secret=sekret");
        JsonNode body = JSON.readTree(bodies.get(0));
        assertThat(body.path("client_id").asText()).isEqualTo("123.456");
        assertThat(body.path("timestamp_micros").asLong()).isEqualTo(Instant.parse("2026-11-11T20:30:00Z").getEpochSecond() * 1_000_000L);
        JsonNode event = body.path("events").get(0);
        assertThat(event.path("name").asText()).isEqualTo("sms_sent");
        JsonNode p = event.path("params");
        assertThat(p.path("session_id").asLong()).isEqualTo(1700000000L);
        assertThat(p.path("engagement_time_msec").asInt()).isEqualTo(1);
        assertThat(p.path("gclid").asText()).isEqualTo("g1");
        assertThat(p.path("utm_campaign").asText()).isEqualTo("brows");
        assertThat(p.path("link_location").asText()).isEqualTo("hero");
        assertThat(p.path("page_path").asText()).isEqualTo("/academy/courses/");
        assertThat(p.path("landing_path").asText()).isEqualTo("/academy/");
        assertThat(count("SENT")).isEqualTo(1);
    }

    @Test
    void withoutGaCookieFallsBackToVisitorIdAsClientId() throws Exception {
        Ga4SmsSentService s = service();
        s.record(2L, "ev-1", tap(null, null), Instant.now());

        JsonNode body = JSON.readTree(bodies.get(0));
        assertThat(body.path("client_id").asText()).isEqualTo("visitor-uuid");
        assertThat(body.path("events").get(0).path("params").has("session_id")).isFalse();
        assertThat(jdbc.queryForObject("SELECT client_id_is_ga FROM ga4_sms_sent_events", Boolean.class)).isFalse();
    }

    @Test
    void failureStaysPendingAndRetrySendsOnce() {
        status = -1;
        Ga4SmsSentService s = service();
        s.record(2L, "ev-1", tap("1.2", null), Instant.now());
        assertThat(count("PENDING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM ga4_sms_sent_events", String.class)).contains("connection reset");

        status = 500;
        s.sendPending();
        assertThat(jdbc.queryForObject("SELECT last_error FROM ga4_sms_sent_events", String.class)).contains("500");

        status = 204;
        assertThat(s.sendPending()).contains("1 sent");
        assertThat(s.sendPending()).contains("0 sent");
        assertThat(count("SENT")).isEqualTo(1);
    }

    @Test
    void oldPendingEventIsGivenUpAndOtherBusinessesAreIgnored() {
        status = 500;
        Ga4SmsSentService s = service();
        s.record(2L, "ev-1", tap("1.2", null), Instant.now().minusSeconds(71 * 3600));
        s.record(99L, "ev-2", tap("1.2", null), Instant.now());

        assertThat(count("FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ga4_sms_sent_events", Integer.class)).isEqualTo(1);
    }
}
