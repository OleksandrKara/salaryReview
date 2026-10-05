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
 * <p>How the pieces connect: the site's Text button pre-fills a natural first message naming the
 * page ("Hi! I have a question about Lip Blush Tattoo.") and, on tap, records a {@code click}
 * event with {@code target=sms}, the page topic and the visitor's attribution (UTM, referrer,
 * gclid/fbclid, landing page, GA client id) in marketing.events. When a text arrives shortly after,
 * this service ties it to that tap (see {@link #capture}) and:
 * <ul>
 *   <li>creates the marketing contact for the sender's number with the website visit's traffic
 *       source (first touch only: an existing contact is never overwritten);</li>
 *   <li>returns a one-line "from the website" context for the staff Telegram alert;</li>
 *   <li>sends {@code sms_lead} to GA4 through the Measurement Protocol when a target is configured
 *       for the business ({@code GA4_MP_TARGETS}, e.g. {@code 2=G-XTPZZV1DKR:apiSecret}), so it
 *       can be a key event and a Google Ads conversion.</li>
 * </ul>
 * A text that can't be tied to a tap is just an ordinary inbound message. Never throws: the
 * inbound-SMS webhook must keep working whatever happens here.
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

    /** How long after a tap on the website's Text button a text from a new number is still taken
     * to come from that tap. */
    static final int MATCH_WINDOW_MINUTES = 30;

    /** The site's own prefilled openings ("Hi! I have a question about Lip Blush Tattoo.", "Hi! I
     * just read "…" on your site and have a question."): a text that still starts this way came
     * from the Text button even if the sender is an existing client. */
    static final Pattern PREFILL = Pattern.compile("(?i)\\bi have a question about\\b|on your site and have a question");

    private static final String CLICK_COLUMNS = """
            SELECT e.id::text AS event_id, e.session_id::text AS visitor_id, e.metadata::text AS metadata,
                   lp.slug AS landing_page_slug, v.name AS variant_name
            FROM marketing.events e
            LEFT JOIN marketing.landing_pages lp ON lp.id = e.landing_page_id
            LEFT JOIN marketing.landing_variants v ON v.id = e.variant_id
            WHERE e.business_id = ? AND e.event_type = 'click' AND e.metadata->>'target' = 'sms'
            """;

    /** Website context for the staff alert ("page · traffic source"), empty when the text can't be
     * tied to a tap on the website's Text button.
     *
     * <p>How a text is tied to a tap (owner decision 2026-10-05: no codes in the message, clients
     * would delete them): a tap in the last {@value #MATCH_WINDOW_MINUTES} minutes not yet matched
     * to another number. Only for texts that look like a first message: still carrying the site's
     * prefilled opening, or from a number we haven't texted in 14 days (so a client's "5" reply
     * to a review request is never mistaken for a website lead). Several taps in the window: the
     * one whose page topic the text names; still ambiguous -> "possibly from the website", no
     * contact, no GA4 event. A legacy "Ref: XXXXXX" code (site version of 2026-10-04) still wins. */
    public Optional<String> capture(Long businessId, String phoneNumber, String body) {
        try {
            Optional<String> ref = extractRefCode(body);
            if (ref.isPresent()) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(CLICK_COLUMNS + """
                          AND upper(e.metadata->>'ref') = ? AND e.created_at > now() - interval '30 days'
                        ORDER BY e.created_at DESC LIMIT 1
                        """, businessId, ref.get());
                if (!rows.isEmpty()) return Optional.of(recordLead(businessId, phoneNumber, rows.get(0)));
            }

            boolean prefilled = body != null && PREFILL.matcher(body).find();
            if (!prefilled) {
                Integer recentOutbound = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM sms_message WHERE business_id = ? AND phone_number = ? "
                                + "AND direction = 'OUTBOUND' AND created_at > now() - interval '14 days'",
                        Integer.class, businessId, phoneNumber);
                if (recentOutbound != null && recentOutbound > 0) return Optional.empty();
            }

            List<Map<String, Object>> taps = jdbcTemplate.queryForList(CLICK_COLUMNS + """
                      AND e.created_at > now() - make_interval(mins => ?)
                      AND e.metadata->>'matched_phone' IS NULL
                    ORDER BY e.created_at DESC
                    """, businessId, MATCH_WINDOW_MINUTES);
            Optional<Map<String, Object>> match = pickTap(taps, body);
            if (match.isPresent()) return Optional.of(recordLead(businessId, phoneNumber, match.get()));
            long visitors = taps.stream().map(t -> t.get("visitor_id")).distinct().count();
            if (visitors > 1) {
                return Optional.of("🌐 Possibly from the website (" + visitors + " visitors tapped Text in the last "
                        + MATCH_WINDOW_MINUTES + " min, can't tell which)");
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("SMS website-lead capture failed (inbound SMS unaffected): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** One visitor's taps (they may tap twice) -> their latest; several visitors -> the one whose
     * page topic the text names, if exactly one does. Package-private for direct unit testing. */
    Optional<Map<String, Object>> pickTap(List<Map<String, Object>> taps, String body) {
        if (taps.isEmpty()) return Optional.empty();
        if (taps.stream().map(t -> t.get("visitor_id")).distinct().count() == 1) return Optional.of(taps.get(0));
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        Map<Object, Map<String, Object>> byVisitor = new java.util.LinkedHashMap<>();
        for (Map<String, Object> t : taps) {
            String topic = text(readMeta(t), "topic");
            if (topic != null && lower.contains(topic.toLowerCase(Locale.ROOT))) byVisitor.putIfAbsent(t.get("visitor_id"), t);
        }
        return byVisitor.size() == 1 ? Optional.of(byVisitor.values().iterator().next()) : Optional.empty();
    }

    private JsonNode readMeta(Map<String, Object> row) {
        try {
            return json.readTree((String) row.get("metadata"));
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private String recordLead(Long businessId, String phoneNumber, Map<String, Object> row) {
        JsonNode meta = readMeta(row);
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
        // A tap leads to at most one number: the next text from someone else can't reuse it.
        if (row.get("event_id") != null) {
            jdbcTemplate.update("UPDATE marketing.events SET metadata = metadata || jsonb_build_object('matched_phone', ?::text) "
                    + "WHERE id = ?::uuid", phoneNumber, row.get("event_id"));
        }

        sendGa4SmsLead(businessId, text(meta, "ga_client_id"), (String) row.get("visitor_id"), text(meta, "path"), source);

        String page = text(meta, "path");
        return "🌐 From the website" + (page != null ? ": " + page : "") + " · " + source
                + (inserted > 0 ? " (new contact)" : "");
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
