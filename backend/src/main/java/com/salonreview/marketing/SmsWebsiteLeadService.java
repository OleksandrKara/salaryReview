package com.salonreview.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "a visitor tapped Text us on the website, then actually texted" into a real lead
 * (pmu-annakara.com, owner decision 2026-10-04: track SMS conversions before the domain cutover).
 *
 * <p>How the pieces connect: the site's Text button pre-fills the message with a short code
 * ("Ref: K7Q2X9") and, on tap, records a {@code click} event with {@code target=sms} and that
 * code in marketing.events, together with the visitor's attribution (UTM, referrer, gclid/fbclid,
 * landing page, GA client id). When a text containing the code arrives, this service finds that
 * click and:
 * <ul>
 *   <li>creates the marketing contact for the sender's number with the website visit's traffic
 *       source (first touch only: an existing contact is never overwritten);</li>
 *   <li>returns a one-line "from the website" context for the staff Telegram alert;</li>
 *   <li>sends {@code sms_lead} to GA4 through the Measurement Protocol when a target is configured
 *       for the business ({@code GA4_MP_TARGETS}, e.g. {@code 2=G-XTPZZV1DKR:apiSecret}), so it
 *       can be a key event and a Google Ads conversion.</li>
 * </ul>
 * A text without a code (typed by hand, code deleted) is just an ordinary inbound message. Never
 * throws: the inbound-SMS webhook must keep working whatever happens here.
 */
@Service
public class SmsWebsiteLeadService {

    private static final Logger log = LoggerFactory.getLogger(SmsWebsiteLeadService.class);

    /** Same alphabet the site generates from (no 0/O/1/I/L), 6 characters. */
    static final Pattern REF_CODE = Pattern.compile("(?i)\\bref[:#]?\\s*([A-HJ-KM-NP-Z2-9]{6})\\b");

    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, String[]> ga4Targets;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SmsWebsiteLeadService(JdbcTemplate jdbcTemplate, @Value("${marketing.ga4-mp-targets:}") String ga4MpTargets) {
        this.jdbcTemplate = jdbcTemplate;
        this.ga4Targets = parseTargets(ga4MpTargets);
    }

    /** {@code "2=G-XXXX:secret,5=G-YYYY:secret2"} -> businessId -> [measurementId, apiSecret]. */
    static Map<Long, String[]> parseTargets(String raw) {
        Map<Long, String[]> out = new HashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String entry : raw.split(",")) {
            String[] kv = entry.trim().split("=", 2);
            if (kv.length != 2) continue;
            String[] idSecret = kv[1].trim().split(":", 2);
            if (idSecret.length != 2 || idSecret[0].isBlank() || idSecret[1].isBlank()) continue;
            try {
                out.put(Long.parseLong(kv[0].trim()), new String[] {idSecret[0].trim(), idSecret[1].trim()});
            } catch (NumberFormatException ignored) {
                // skip a malformed entry rather than failing startup
            }
        }
        return out;
    }

    static Optional<String> extractRefCode(String body) {
        if (body == null) return Optional.empty();
        Matcher m = REF_CODE.matcher(body);
        return m.find() ? Optional.of(m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** Website context for the staff alert ("page · traffic source"), empty when the text carries
     * no known code. */
    public Optional<String> capture(Long businessId, String phoneNumber, String body) {
        try {
            Optional<String> ref = extractRefCode(body);
            if (ref.isEmpty()) return Optional.empty();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT e.session_id::text AS visitor_id, e.metadata::text AS metadata, lp.slug AS landing_page_slug,
                           v.name AS variant_name
                    FROM marketing.events e
                    LEFT JOIN marketing.landing_pages lp ON lp.id = e.landing_page_id
                    LEFT JOIN marketing.landing_variants v ON v.id = e.variant_id
                    WHERE e.business_id = ? AND e.event_type = 'click'
                      AND e.metadata->>'target' = 'sms' AND upper(e.metadata->>'ref') = ?
                      AND e.created_at > now() - interval '30 days'
                    ORDER BY e.created_at DESC LIMIT 1
                    """,
                    businessId, ref.get());
            if (rows.isEmpty()) {
                log.info("Inbound SMS carried ref {} with no matching website click (business {})", ref.get(), businessId);
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            JsonNode meta = json.readTree((String) row.get("metadata"));
            String source = classifyTrafficSource(text(meta, "utm_source"), text(meta, "utm_medium"),
                    text(meta, "utm_campaign"), text(meta, "referrer"), text(meta, "gclid"), text(meta, "fbclid"));

            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO marketing.contacts (
                        business_id, phone_number, original_traffic_source, marketing_traffic_source,
                        utm_source, utm_medium, utm_campaign, referrer, landing_page_slug, variant_name, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (business_id, phone_number) DO NOTHING
                    """,
                    businessId, phoneNumber, source, source, text(meta, "utm_source"), text(meta, "utm_medium"),
                    text(meta, "utm_campaign"), text(meta, "referrer"), row.get("landing_page_slug"), row.get("variant_name"));

            sendGa4SmsLead(businessId, text(meta, "ga_client_id"), (String) row.get("visitor_id"), text(meta, "path"), source);

            String page = text(meta, "path");
            return Optional.of("🌐 From the website" + (page != null ? ": " + page : "") + " · " + source
                    + (inserted > 0 ? " (new contact)" : ""));
        } catch (Exception e) {
            log.warn("SMS website-lead capture failed (inbound SMS unaffected): {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void sendGa4SmsLead(Long businessId, String gaClientId, String visitorId, String page, String source) {
        String[] target = ga4Targets.get(businessId);
        if (target == null) return;
        try {
            Map<String, Object> params = new HashMap<>();
            if (page != null) params.put("page_path", page);
            params.put("traffic_source", source);
            Map<String, Object> payload = Map.of(
                    // GA's own client id ties the lead to the visitor's session (and through it to the
                    // ad click); the visitor id is only a fallback so the event still lands.
                    "client_id", gaClientId != null ? gaClientId : visitorId,
                    "events", List.of(Map.of("name", "sms_lead", "params", params)));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.google-analytics.com/mp/collect?measurement_id=" + target[0] + "&api_secret=" + target[1]))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 300) log.warn("GA4 sms_lead send failed: HTTP {}", res.statusCode());
        } catch (Exception e) {
            log.warn("GA4 sms_lead send failed: {}", e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    /** Same labels salonLandings' classify_traffic_source gives a booking (app/services/
     * traffic_source.py), so an SMS lead and a booked contact read the same in Contacts and the
     * "Google Ads%"/"Meta Ads%" prefix matching keeps working. Keep the two in step. */
    static String classifyTrafficSource(String utmSource, String utmMedium, String utmCampaign, String referrer,
                                        String gclid, String fbclid) {
        String utmDetail = null;
        if (utmSource != null) {
            StringBuilder sb = new StringBuilder(utmSource);
            if (utmMedium != null) sb.append(" / ").append(utmMedium);
            if (utmCampaign != null) sb.append(" / ").append(utmCampaign);
            utmDetail = sb.toString();
        }
        if (fbclid != null) return utmDetail != null ? "Meta Ads (" + utmDetail + ")" : "Meta Ads (click)";
        if (gclid != null) return utmDetail != null ? "Google Ads (" + utmDetail + ")" : "Google Ads (click)";
        if (utmDetail != null) return utmDetail;

        String ref = referrer == null ? "" : referrer.toLowerCase(Locale.ROOT);
        if (ref.isEmpty()) return "Direct / No referrer";
        if (ref.contains("google.")) return "Google (organic)";
        if (ref.contains("instagram.com")) return "Instagram (organic)";
        if (ref.contains("facebook.com") || ref.contains("fb.com")) return "Facebook (organic)";
        if (ref.contains("bing.")) return "Bing (organic)";
        if (ref.contains("yahoo.")) return "Yahoo (organic)";
        try {
            String host = URI.create(referrer).getHost();
            return host != null ? "Referral: " + host : "Direct / Unknown";
        } catch (IllegalArgumentException e) {
            return "Direct / Unknown";
        }
    }
}
