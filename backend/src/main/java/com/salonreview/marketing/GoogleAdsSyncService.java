package com.salonreview.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.seo.GoogleAdsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the Google Ads account's campaign results, conversions by action and change history into
 * {@code google_ads_*}, read-only through the service account. One Ads account serves
 * PMU studio and Academy together (campaigns told apart by name), so it is stored under the PMU
 * business. Idempotent: every pull upserts, and recent days are re-pulled because Ads restates
 * conversions for a few days after the click.
 */
@Service
public class GoogleAdsSyncService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAdsSyncService.class);
    private static final ZoneId ACCOUNT_TZ = ZoneId.of("America/Los_Angeles");
    private static final Pattern CAMPAIGN_ID = Pattern.compile("/campaigns/(\\d+)");
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");

    private final JdbcTemplate jdbc;
    private final Map<Long, String> accounts;
    private final GoogleAdsClient client;

    public GoogleAdsSyncService(JdbcTemplate jdbc,
                                @Value("${marketing.google-ads.accounts:}") String accounts,
                                @Value("${marketing.google-ads.service-account-json-b64:}") String saJsonB64,
                                @Value("${marketing.google-ads.developer-token:}") String developerToken) {
        this.jdbc = jdbc;
        this.accounts = parseAccounts(accounts);
        this.client = saJsonB64 == null || saJsonB64.isBlank() || this.accounts.isEmpty()
                ? null
                : new GoogleAdsClient(new String(Base64.getDecoder().decode(saJsonB64.trim()), StandardCharsets.UTF_8), developerToken);
    }

    GoogleAdsSyncService(JdbcTemplate jdbc, Map<Long, String> accounts, GoogleAdsClient client) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.client = client;
    }

    /** {@code "2=7340887092,5=1112223333"} -> businessId -> customerId (digits only). */
    static Map<Long, String> parseAccounts(String raw) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String entry : raw.split(",")) {
            String[] kv = entry.trim().split("=", 2);
            if (kv.length != 2) continue;
            try {
                out.put(Long.parseLong(kv[0].trim()), kv[1].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                // skip a malformed entry rather than failing startup
            }
        }
        return out;
    }

    public boolean enabled() {
        return client != null;
    }

    public Map<Long, String> accounts() {
        return accounts;
    }

    /** True when no daily rows exist yet for the business, so the scheduler should backfill. */
    public boolean isEmpty(long businessId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM google_ads_daily WHERE business_id = ?", Integer.class, businessId);
        return n == null || n == 0;
    }

    /** Syncs every configured account for [from, to]; returns rows written per kind in a short text. */
    public String syncAll(LocalDate from, LocalDate to) {
        if (!enabled()) return "google ads sync is not configured";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, String> a : accounts.entrySet()) {
            int daily = syncDaily(a.getKey(), a.getValue(), from, to);
            int conv = syncConversions(a.getKey(), a.getValue(), from, to);
            LocalDate changesFrom = from.isBefore(to.minusDays(29)) ? to.minusDays(29) : from;
            int changes = syncChanges(a.getKey(), a.getValue(), changesFrom, to);
            sb.append("business ").append(a.getKey()).append(": ").append(daily).append(" campaign-days, ")
                    .append(conv).append(" conversion rows, ").append(changes).append(" changes. ");
        }
        return sb.toString().trim();
    }

    int syncDaily(long businessId, String customerId, LocalDate from, LocalDate to) {
        List<JsonNode> rows = client.search(customerId, """
                SELECT campaign.id, campaign.name, campaign.status, campaign.advertising_channel_type, segments.date,
                       metrics.cost_micros, metrics.impressions, metrics.clicks, metrics.conversions, metrics.all_conversions
                FROM campaign
                WHERE segments.date BETWEEN '%s' AND '%s' AND metrics.impressions > 0
                """.formatted(from, to));
        for (JsonNode r : rows) {
            jdbc.update("""
                    INSERT INTO google_ads_daily (customer_id, business_id, campaign_id, day, campaign_name, campaign_status,
                        channel_type, cost_micros, impressions, clicks, conversions, all_conversions, synced_at)
                    VALUES (?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (customer_id, campaign_id, day) DO UPDATE SET campaign_name = EXCLUDED.campaign_name,
                        campaign_status = EXCLUDED.campaign_status, channel_type = EXCLUDED.channel_type,
                        cost_micros = EXCLUDED.cost_micros, impressions = EXCLUDED.impressions, clicks = EXCLUDED.clicks,
                        conversions = EXCLUDED.conversions, all_conversions = EXCLUDED.all_conversions, synced_at = now()
                    """, customerId, businessId, r.path("campaign").path("id").asLong(), r.path("segments").path("date").asText(),
                    r.path("campaign").path("name").asText(), r.path("campaign").path("status").asText(null),
                    r.path("campaign").path("advertisingChannelType").asText(null),
                    r.path("metrics").path("costMicros").asLong(), r.path("metrics").path("impressions").asLong(),
                    r.path("metrics").path("clicks").asLong(), decimal(r.path("metrics").path("conversions")),
                    decimal(r.path("metrics").path("allConversions")));
        }
        log.info("Google Ads daily sync business {}: {} rows for {}..{}", businessId, rows.size(), from, to);
        return rows.size();
    }

    int syncConversions(long businessId, String customerId, LocalDate from, LocalDate to) {
        List<JsonNode> rows = client.search(customerId, """
                SELECT campaign.id, segments.date, segments.conversion_action_name, metrics.all_conversions, metrics.all_conversions_value
                FROM campaign
                WHERE segments.date BETWEEN '%s' AND '%s' AND metrics.all_conversions > 0
                """.formatted(from, to));
        for (JsonNode r : rows) {
            jdbc.update("""
                    INSERT INTO google_ads_conversions_daily (customer_id, business_id, campaign_id, day, action_name,
                        all_conversions, all_conv_value, synced_at)
                    VALUES (?, ?, ?, ?::date, ?, ?, ?, now())
                    ON CONFLICT (customer_id, campaign_id, day, action_name) DO UPDATE SET
                        all_conversions = EXCLUDED.all_conversions, all_conv_value = EXCLUDED.all_conv_value, synced_at = now()
                    """, customerId, businessId, r.path("campaign").path("id").asLong(), r.path("segments").path("date").asText(),
                    r.path("segments").path("conversionActionName").asText(), decimal(r.path("metrics").path("allConversions")),
                    decimal(r.path("metrics").path("allConversionsValue")));
        }
        return rows.size();
    }

    int syncChanges(long businessId, String customerId, LocalDate from, LocalDate to) {
        List<JsonNode> rows = client.search(customerId, """
                SELECT change_event.change_date_time, change_event.user_email, change_event.client_type,
                       change_event.change_resource_type, change_event.change_resource_name,
                       change_event.resource_change_operation, change_event.changed_fields, change_event.campaign
                FROM change_event
                WHERE change_event.change_date_time >= '%s 00:00:00' AND change_event.change_date_time <= '%s 23:59:59'
                ORDER BY change_event.change_date_time DESC
                LIMIT 10000
                """.formatted(from, to));
        int written = 0;
        for (JsonNode r : rows) {
            JsonNode e = r.path("changeEvent");
            Timestamp at = parseChangeTime(e.path("changeDateTime").asText(""));
            if (at == null) continue;
            Matcher m = CAMPAIGN_ID.matcher(e.path("campaign").asText(""));
            Long campaignId = m.find() ? Long.parseLong(m.group(1)) : null;
            written += jdbc.update("""
                    INSERT INTO google_ads_changes (customer_id, business_id, changed_at, user_email, client_type,
                        resource_type, resource_name, operation, campaign_id, changed_fields)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (customer_id, changed_at, resource_name, operation) DO NOTHING
                    """, customerId, businessId, at, e.path("userEmail").asText(null), e.path("clientType").asText(null),
                    e.path("changeResourceType").asText(""), e.path("changeResourceName").asText(""),
                    e.path("resourceChangeOperation").asText(""), campaignId, e.path("changedFields").asText(null));
        }
        return written;
    }

    static Timestamp parseChangeTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Timestamp.from(LocalDateTime.parse(raw, CHANGE_TIME).atZone(ACCOUNT_TZ).toInstant());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? BigDecimal.ZERO : new BigDecimal(n.asText("0"));
    }
}
