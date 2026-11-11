package com.salonreview.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the GA4 event {@code sms_sent} (Measurement Protocol) when a real inbound text has been tied to a
 * website Text tap: the visitor did not just tap, they texted us. Inweb marks it a key event in GA4 and
 * imports it into Google Ads as the primary conversion "SMS Sent"; nothing here needs Ads write access.
 *
 * <p>Attribution to the ad click works through the visitor's GA client id and session id, captured in
 * the browser at the tap ({@code ga_client_id}, {@code ga_session_id}). Without a {@code _ga} cookie the
 * tap's visitor id is used as a stable pseudo client id: the event still counts in GA4, just not in Ads.
 * The click ids, UTM, page, button and traffic source also go along as event parameters.
 *
 * <p>Each business has its own GA4 property: {@code marketing.ga4-mp-targets} ({@code 2=G-XXXX:secret}).
 * One row per tap in {@code ga4_sms_sent_events} (unique per business and tap) means once only; a failed send
 * stays PENDING and {@link #sendPending} retries it (GA4 takes events up to 72 hours old, sent with their real time).
 * Nothing here may break the SMS flow: it never throws.
 */
@Service
public class Ga4SmsSentService {

    private static final Logger log = LoggerFactory.getLogger(Ga4SmsSentService.class);
    static final String EVENT_NAME = "sms_sent";
    static final Duration GIVE_UP_AFTER = Duration.ofHours(70);
    private static final int BATCH = 200;
    private static final String ENDPOINT = "https://www.google-analytics.com/mp/collect";

    /** The tap's attribution, stored with the event and sent along as parameters. */
    public record Tap(String gaClientId, String gaSessionId, String visitorId, String gclid, String gbraid, String wbraid,
                      String trafficSource, String utmSource, String utmMedium, String utmCampaign, String referrer,
                      String landingPageSlug, String landingPath, String pagePath, String linkSource, String topic) {
    }

    /** Where the POST goes; replaceable in tests. */
    interface Transport {
        int post(String url, String json) throws Exception;
    }

    private final JdbcTemplate jdbc;
    private final Map<Long, String[]> targets;
    private final Transport transport;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public Ga4SmsSentService(JdbcTemplate jdbc, @Value("${marketing.ga4-mp-targets:}") String targets) {
        this(jdbc, SmsWebsiteLeadService.parseTargets(targets), httpTransport());
    }

    Ga4SmsSentService(JdbcTemplate jdbc, Map<Long, String[]> targets, Transport transport) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.transport = transport;
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, body) -> http.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    public boolean enabled() {
        return !targets.isEmpty();
    }

    /** Records the text once and tries to send it right away; a repeat for the same tap does nothing. */
    public void record(Long businessId, String eventId, Tap tap, Instant when) {
        try {
            if (!targets.containsKey(businessId) || eventId == null) return;
            boolean ga = tap.gaClientId() != null;
            String clientId = ga ? tap.gaClientId() : tap.visitorId();
            if (clientId == null) return;
            int inserted = jdbc.update("""
                    INSERT INTO ga4_sms_sent_events (business_id, order_id, client_id, client_id_is_ga, ga_session_id, occurred_at,
                        traffic_source, utm_source, utm_medium, utm_campaign, gclid, gbraid, wbraid, referrer, landing_page_slug,
                        landing_path, page_path, link_source, topic)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (business_id, order_id) DO NOTHING
                    """, businessId, "sms-" + eventId, clientId, ga, tap.gaSessionId(), Timestamp.from(when), tap.trafficSource(),
                    tap.utmSource(), tap.utmMedium(), tap.utmCampaign(), tap.gclid(), tap.gbraid(), tap.wbraid(), tap.referrer(),
                    tap.landingPageSlug(), tap.landingPath(), tap.pagePath(), tap.linkSource(), tap.topic());
            if (inserted > 0) sendPending();
        } catch (Exception e) {
            log.warn("sms_sent record failed (inbound SMS unaffected): {}", e.getMessage());
        }
    }

    /** Sends every PENDING row once; returns a one-line summary. */
    public String sendPending() {
        if (!enabled()) return "sms_sent is not configured";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ga4_sms_sent_events WHERE status = 'PENDING' ORDER BY id LIMIT ?", BATCH);
        int sent = 0, waiting = 0, failed = 0;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            Instant at = ((Timestamp) row.get("occurred_at")).toInstant();
            boolean expired = at.isBefore(Instant.now().minus(GIVE_UP_AFTER));
            String[] target = targets.get(((Number) row.get("business_id")).longValue());
            String error;
            if (target == null) {
                error = "no GA4 target for business";
            } else {
                try {
                    int status = transport.post(ENDPOINT + "?measurement_id=" + target[0] + "&api_secret=" + target[1], payload(row, at));
                    error = status < 300 ? null : "GA4 HTTP " + status;
                } catch (Exception e) {
                    error = String.valueOf(e.getMessage());
                }
            }
            if (error == null) {
                jdbc.update("UPDATE ga4_sms_sent_events SET status = 'SENT', sent_at = now(), last_error = NULL, attempts = attempts + 1, "
                        + "last_attempt_at = now() WHERE id = ?", id);
                sent++;
            } else {
                String status = expired ? "FAILED" : "PENDING";
                jdbc.update("UPDATE ga4_sms_sent_events SET status = ?, last_error = ?, attempts = attempts + 1, last_attempt_at = now() WHERE id = ?",
                        status, error.length() > 300 ? error.substring(0, 300) : error, id);
                if (expired) failed++; else waiting++;
            }
        }
        String summary = "sms_sent: " + sent + " sent, " + waiting + " waiting, " + failed + " failed";
        if (!rows.isEmpty() && sent < rows.size()) log.warn(summary);
        return summary;
    }

    String payload(Map<String, Object> row, Instant at) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        // engagement_time_msec makes GA4 count the event inside the visitor's session
        params.put("engagement_time_msec", 1);
        put(params, "session_id", row.get("ga_session_id") == null ? null : Long.parseLong((String) row.get("ga_session_id")));
        put(params, "traffic_source", row.get("traffic_source"));
        put(params, "utm_source", row.get("utm_source"));
        put(params, "utm_medium", row.get("utm_medium"));
        put(params, "utm_campaign", row.get("utm_campaign"));
        put(params, "gclid", row.get("gclid"));
        put(params, "gbraid", row.get("gbraid"));
        put(params, "wbraid", row.get("wbraid"));
        put(params, "referrer", row.get("referrer"));
        put(params, "landing_page", row.get("landing_page_slug"));
        put(params, "landing_path", row.get("landing_path"));
        put(params, "page_path", row.get("page_path"));
        put(params, "link_location", row.get("link_source"));
        put(params, "topic", row.get("topic"));
        put(params, "transaction_id", row.get("order_id"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", row.get("client_id"));
        body.put("timestamp_micros", at.getEpochSecond() * 1_000_000L + at.getNano() / 1000);
        body.put("events", List.of(Map.of("name", EVENT_NAME, "params", params)));
        return json.writeValueAsString(body);
    }

    private static void put(Map<String, Object> params, String key, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isBlank()) return;
        params.put(key, value instanceof Number ? value : s.length() > 100 ? s.substring(0, 100) : s);
    }
}
