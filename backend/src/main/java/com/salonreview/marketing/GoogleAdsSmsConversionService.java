package com.salonreview.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.seo.GoogleAdsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports "the visitor really texted us" to Google Ads as the offline click conversion {@code SMS Sent}.
 * The tap on a site's Text button is only a click; the send is known solely when the inbound text
 * reaches Twilio, so {@link SmsWebsiteLeadService} calls {@link #enqueue} once it ties that text to a
 * tap that carried a Google click id. Rows wait in {@code google_ads_sms_conversions} and
 * {@link #uploadPending} sends them through {@code uploadClickConversions}.
 *
 * <p>Nothing here may break the SMS flow: enqueue never throws, and while the conversion action does
 * not exist yet (or the service account has no write access) rows simply stay PENDING with the reason in
 * {@code last_error} and are retried by the scheduler until they are older than {@link #GIVE_UP_DAYS}.
 * Enabled per business via {@code marketing.google-ads.sms-conversion-accounts} ("2=7340887092").
 */
@Service
public class GoogleAdsSmsConversionService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAdsSmsConversionService.class);
    /** Conversion times are sent in the Ads account's time zone (PMU/Academy and nails accounts are Pacific). */
    private static final ZoneId ACCOUNT_TZ = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter ADS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx");
    static final int GIVE_UP_DAYS = 14;
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final Map<Long, String> accounts;
    private final GoogleAdsClient client;
    private final String conversionName;
    private final Map<String, String> actionCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public GoogleAdsSmsConversionService(JdbcTemplate jdbc,
                                         @Value("${marketing.google-ads.sms-conversion-accounts:}") String accounts,
                                         @Value("${marketing.google-ads.service-account-json-b64:}") String saJsonB64,
                                         @Value("${marketing.google-ads.developer-token:}") String developerToken,
                                         @Value("${marketing.google-ads.sms-conversion-name:SMS Sent}") String conversionName) {
        this.jdbc = jdbc;
        this.accounts = GoogleAdsSyncService.parseAccounts(accounts);
        this.conversionName = conversionName;
        this.client = saJsonB64 == null || saJsonB64.isBlank() || this.accounts.isEmpty()
                ? null
                : new GoogleAdsClient(new String(Base64.getDecoder().decode(saJsonB64.trim()), StandardCharsets.UTF_8), developerToken);
    }

    GoogleAdsSmsConversionService(JdbcTemplate jdbc, Map<Long, String> accounts, GoogleAdsClient client, String conversionName) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.client = client;
        this.conversionName = conversionName;
    }

    public boolean enabled() {
        return client != null && !accounts.isEmpty();
    }

    /** The tap's attribution, stored with the conversion. */
    public record Click(String gclid, String gbraid, String wbraid, String trafficSource, String utmSource, String utmMedium,
                        String utmCampaign, String referrer, String landingPageSlug, String landingPath, String pagePath,
                        String linkSource, String topic) {
    }

    /** Queues one conversion for a matched tap; a click id with no business account, or a repeat, is ignored. */
    public void enqueue(Long businessId, String eventId, Click c, Instant when) {
        try {
            String customer = accounts.get(businessId);
            if (customer == null || eventId == null) return;
            if (c.gclid() == null && c.gbraid() == null && c.wbraid() == null) return;
            jdbc.update("""
                    INSERT INTO google_ads_sms_conversions (business_id, customer_id, order_id, gclid, gbraid, wbraid, conversion_time,
                        traffic_source, utm_source, utm_medium, utm_campaign, referrer, landing_page_slug, landing_path, page_path,
                        link_source, topic)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, businessId, customer, "sms-" + eventId, c.gclid(), c.gbraid(), c.wbraid(), java.sql.Timestamp.from(when),
                    c.trafficSource(), c.utmSource(), c.utmMedium(), c.utmCampaign(), c.referrer(), c.landingPageSlug(),
                    c.landingPath(), c.pagePath(), c.linkSource(), c.topic());
        } catch (Exception e) {
            log.warn("SMS Sent conversion enqueue failed (inbound SMS unaffected): {}", e.getMessage());
        }
    }

    /** Tries every PENDING row once; returns a one-line summary for logs. */
    public String uploadPending() {
        if (!enabled()) return "sms conversion upload is not configured";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, customer_id, order_id, gclid, gbraid, wbraid, conversion_time, created_at < now() - make_interval(days => ?) AS expired
                FROM google_ads_sms_conversions WHERE status = 'PENDING' ORDER BY id LIMIT ?
                """, GIVE_UP_DAYS, BATCH);
        int uploaded = 0, failed = 0, waiting = 0;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            boolean expired = Boolean.TRUE.equals(row.get("expired"));
            try {
                String error = uploadOne(row);
                if (error == null) {
                    jdbc.update("UPDATE google_ads_sms_conversions SET status = 'UPLOADED', uploaded_at = now(), last_error = NULL, "
                            + "attempts = attempts + 1, last_attempt_at = now() WHERE id = ?", id);
                    uploaded++;
                } else if (isPermanent(error) || expired) {
                    mark(id, "FAILED", error);
                    failed++;
                } else {
                    mark(id, "PENDING", error);
                    waiting++;
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mark(id, expired ? "FAILED" : "PENDING", msg);
                if (expired) failed++; else waiting++;
            }
        }
        String summary = "SMS Sent upload: " + uploaded + " uploaded, " + waiting + " waiting, " + failed + " failed";
        if (!rows.isEmpty()) log.info(summary);
        return summary;
    }

    /** Null on success (or an already-imported duplicate), otherwise the reason. */
    private String uploadOne(Map<String, Object> row) {
        String customer = (String) row.get("customer_id");
        String action = conversionAction(customer);
        if (action == null) return "conversion action '" + conversionName + "' not found (or not enabled) in account " + customer;

        Map<String, Object> conv = new LinkedHashMap<>();
        if (row.get("gclid") != null) conv.put("gclid", row.get("gclid"));
        else if (row.get("gbraid") != null) conv.put("gbraid", row.get("gbraid"));
        else conv.put("wbraid", row.get("wbraid"));
        conv.put("conversionAction", action);
        conv.put("conversionDateTime", ((java.sql.Timestamp) row.get("conversion_time")).toInstant().atZone(ACCOUNT_TZ).format(ADS_TIME));
        conv.put("orderId", row.get("order_id"));

        JsonNode res = client.uploadClickConversions(customer, Map.of("conversions", List.of(conv), "partialFailure", true));
        JsonNode err = res.path("partialFailureError");
        if (err.isMissingNode() || err.isNull()) return null;
        String text = err.toString();
        if (text.contains("DUPLICATE_ORDER_ID") || text.contains("ALREADY_IMPORTED")) return null;
        return "Google Ads rejected the conversion: " + text;
    }

    /** Looks the action up by name; a miss is not cached so a newly created action is picked up on the next run. */
    private String conversionAction(String customer) {
        String cached = actionCache.get(customer);
        if (cached != null) return cached;
        List<JsonNode> rows = client.search(customer, "SELECT conversion_action.resource_name, conversion_action.status FROM conversion_action "
                + "WHERE conversion_action.name = '" + conversionName.replace("'", "") + "'");
        List<String> names = new ArrayList<>();
        for (JsonNode r : rows) {
            JsonNode a = r.path("conversionAction");
            if ("ENABLED".equals(a.path("status").asText())) names.add(a.path("resourceName").asText());
        }
        if (names.isEmpty()) return null;
        actionCache.put(customer, names.get(0));
        return names.get(0);
    }

    static boolean isPermanent(String error) {
        return error != null && (error.contains("EXPIRED_EVENT") || error.contains("UNPARSEABLE_GCLID")
                || error.contains("INVALID_GCLID") || error.contains("CONVERSION_PRECEDES_EVENT"));
    }

    private void mark(long id, String status, String error) {
        jdbc.update("UPDATE google_ads_sms_conversions SET status = ?, last_error = ?, attempts = attempts + 1, last_attempt_at = now() WHERE id = ?",
                status, abbreviate(error), id);
    }

    private static String abbreviate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
