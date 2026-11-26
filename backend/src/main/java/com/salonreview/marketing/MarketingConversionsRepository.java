package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Repository
public class MarketingConversionsRepository {

    public record ContactRow(String customerId, String bookingId, String serviceName, Instant createdAt, String channel,
                             String name, String phone, String email, String campaign, String pageSlug,
                             String bookingStatus, Instant bookingStartAt, BigDecimal bookingPrice, String artist) {}

    public record CustomerRow(String customerId, String name, String phone, String email) {}

    public record PaymentRow(String customerId, BigDecimal amount, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    public MarketingConversionsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Distinct visitors per traffic channel (the channel of the visitor's first visit; "other"
     * when it fits no bucket) for page views, text taps and call taps in the window. */
    public Map<String, Map<String, Long>> countVisitorsByChannel(UUID landingPageId, Instant from, Instant to) {
        String sql = "SELECT COALESCE(x.ch, 'other') AS ch, x.kind AS kind, COUNT(DISTINCT x.session_id) AS n FROM ("
                + " SELECT e.session_id,"
                + "  CASE WHEN e.event_type = 'page_view' THEN 'views' WHEN e.metadata->>'target' = 'sms' THEN 'sms' ELSE 'call' END AS kind,"
                + "  (SELECT " + TrafficSourceSql.visitChannelCase("vi") + " FROM marketing.visits vi"
                + "   WHERE vi.visitor_id = e.session_id ORDER BY vi.occurred_at LIMIT 1) AS ch"
                + " FROM marketing.events e WHERE e.landing_page_id = ?"
                + " AND (e.event_type = 'page_view' OR (e.event_type = 'click' AND e.metadata->>'target' IN ('sms','phone')))"
                + " AND (?::timestamptz IS NULL OR e.created_at >= ?) AND (?::timestamptz IS NULL OR e.created_at < ?)"
                + " AND " + TrafficSourceSql.notAutomated("e.session_id")
                + ") x GROUP BY 1, 2";
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        Map<String, Map<String, Long>> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.computeIfAbsent(rs.getString("ch"), k -> new HashMap<>()).put(rs.getString("kind"), rs.getLong("n"));
        }, landingPageId, f, f, t, t);
        return out;
    }

    /** People who left contact details on this page in the window (one row per phone number). */
    public List<ContactRow> findContacts(Long businessId, String slug, Instant from, Instant to) {
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        return jdbc.query(
                "SELECT c.square_customer_id, c.square_booking_id, c.booking_service_name, c.created_at, "
                        + "c.given_name, c.phone_number, c.email_address, c.utm_campaign, c.landing_page_slug, "
                        + "c.booking_status, c.booking_start_at, c.booking_price, c.booking_artist_name, "
                        + "COALESCE(" + TrafficSourceSql.contactChannelCase("c") + ", 'other') AS channel "
                        + "FROM marketing.contacts c WHERE c.business_id = ? AND c.landing_page_slug = ? "
                        + "AND (?::timestamptz IS NULL OR c.created_at >= ?) AND (?::timestamptz IS NULL OR c.created_at < ?)",
                (rs, i) -> new ContactRow(rs.getString("square_customer_id"), rs.getString("square_booking_id"),
                        rs.getString("booking_service_name"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("channel"), rs.getString("given_name"), rs.getString("phone_number"),
                        rs.getString("email_address"), rs.getString("utm_campaign"), rs.getString("landing_page_slug"),
                        rs.getString("booking_status"),
                        rs.getTimestamp("booking_start_at") == null ? null : rs.getTimestamp("booking_start_at").toInstant(),
                        rs.getBigDecimal("booking_price"), rs.getString("booking_artist_name")),
                businessId, slug, f, f, t, t);
    }

    public List<CustomerRow> findCustomers(Long businessId, Collection<String> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(customerIds.size(), "?"));
        Object[] args = new Object[customerIds.size() + 1];
        args[0] = businessId;
        int i = 1;
        for (String id : customerIds) args[i++] = id;
        return jdbc.query(
                "SELECT square_customer_id, given_name, family_name, phone_number, email_address FROM square_customer "
                        + "WHERE business_id = ? AND square_customer_id IN (" + marks + ")",
                (rs, n) -> new CustomerRow(rs.getString("square_customer_id"),
                        ((rs.getString("given_name") == null ? "" : rs.getString("given_name")) + " "
                                + (rs.getString("family_name") == null ? "" : rs.getString("family_name"))).trim(),
                        rs.getString("phone_number"), rs.getString("email_address")),
                args);
    }

    public List<PaymentRow> findPayments(Long businessId, Collection<String> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(customerIds.size(), "?"));
        Object[] args = new Object[customerIds.size() + 1];
        args[0] = businessId;
        int i = 1;
        for (String id : customerIds) args[i++] = id;
        return jdbc.query(
                "SELECT square_customer_id, total_money, created_at FROM square_payment "
                        + "WHERE business_id = ? AND status = 'COMPLETED' AND total_money IS NOT NULL "
                        + "AND created_at IS NOT NULL AND square_customer_id IN (" + marks + ")",
                (rs, n) -> new PaymentRow(rs.getString("square_customer_id"), rs.getBigDecimal("total_money"),
                        rs.getTimestamp("created_at").toInstant()),
                args);
    }

    /**
     * Text and call taps of Google Ads visitors on one page, per campaign: the campaign id the click carried in the
     * landing URL (hsa_cam or utm_id, the Google Ads id), else its utm_campaign text. Keys are "id:123" or "name:text";
     * values are distinct sessions {sms, call}. Same session, window and bot rules as {@link #countVisitorsByChannel}.
     */
    public Map<String, long[]> googleTapsByCampaign(UUID landingPageId, Instant from, Instant to) {
        String sql = "SELECT COALESCE('id:' || fv.cid, 'name:' || fv.cname, '') AS camp, "
                + " CASE WHEN e.metadata->>'target' = 'sms' THEN 'sms' ELSE 'call' END AS kind, COUNT(DISTINCT e.session_id) AS n"
                + " FROM marketing.events e CROSS JOIN LATERAL ("
                + "  SELECT " + TrafficSourceSql.visitChannelCase("vi") + " AS ch,"
                + "   substring(vi.landing_url from '(?:hsa_cam|utm_id)=([0-9]+)') AS cid, NULLIF(vi.utm_campaign, '') AS cname"
                + "  FROM marketing.visits vi WHERE vi.visitor_id = e.session_id ORDER BY vi.occurred_at LIMIT 1) fv"
                + " WHERE e.landing_page_id = ? AND e.event_type = 'click' AND e.metadata->>'target' IN ('sms','phone')"
                + " AND (?::timestamptz IS NULL OR e.created_at >= ?) AND (?::timestamptz IS NULL OR e.created_at < ?)"
                + " AND " + TrafficSourceSql.notAutomated("e.session_id") + " AND fv.ch = 'google_ads' GROUP BY 1, 2";
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        Map<String, long[]> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            long[] v = out.computeIfAbsent(rs.getString("camp"), k -> new long[2]);
            v["sms".equals(rs.getString("kind")) ? 0 : 1] = rs.getLong("n");
        }, landingPageId, f, f, t, t);
        return out;
    }

    /**
     * Academy course requests that came from a Google ad (a Google click id, or utm_source google with medium cpc) and are
     * not repeats, with the utm_campaign text of each. This is the Academy form conversion.
     */
    public List<String> googleAcademyForms(Long businessId, Instant from, Instant to) {
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        return jdbc.query("SELECT COALESCE(utm_campaign, '') AS camp FROM academy_lead WHERE business_id = ? AND status <> 'duplicate' "
                        + "AND (COALESCE(gclid, '') <> '' OR (utm_source = 'google' AND utm_medium = 'cpc')) "
                        + "AND (?::timestamptz IS NULL OR created_at >= ?) AND (?::timestamptz IS NULL OR created_at < ?)",
                (rs, i) -> rs.getString("camp"), businessId, f, f, t, t);
    }

    /**
     * Phone calls received through the call tracker (Ringostat) that came from a Google ad, one per call, with the utm_campaign
     * text each carried. These are real calls, not taps on the number, and Google Ads counts them as the Ringostat_calls conversion.
     */
    public List<String> googleRingostatCalls(Long businessId, Instant from, Instant to) {
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        return jdbc.query("SELECT COALESCE(attribution->>'utm_campaign', '') AS camp FROM lead_intake WHERE business_id = ? AND channel = 'Call' "
                        + "AND source = 'Google Ads' AND outcome <> 'DUPLICATE' "
                        + "AND (?::timestamptz IS NULL OR created_at >= ?) AND (?::timestamptz IS NULL OR created_at < ?)",
                (rs, i) -> rs.getString("camp"), businessId, f, f, t, t);
    }

    /**
     * Texts received from a Google ad visitor, one per text, with the utm_campaign each carried. This is exactly what is sent to
     * Google Ads as the sms_sent conversion: an inbound text (Twilio) tied to a tap on the site's Text button.
     */
    public List<String> googleSmsReceived(Long businessId, Instant from, Instant to) {
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        return jdbc.query("SELECT COALESCE(utm_campaign, '') AS camp FROM ga4_sms_sent_events WHERE business_id = ? AND traffic_source = 'Google Ads' "
                        + "AND (?::timestamptz IS NULL OR occurred_at >= ?) AND (?::timestamptz IS NULL OR occurred_at < ?)",
                (rs, i) -> rs.getString("camp"), businessId, f, f, t, t);
    }

    /** Texts received from Google ad visitors per landing page slug. */
    public java.util.Map<String, Long> googleSmsReceivedByPage(Long businessId, Instant from, Instant to) {
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        jdbc.query("SELECT landing_page_slug AS slug, count(*) AS n FROM ga4_sms_sent_events WHERE business_id = ? AND traffic_source = 'Google Ads' "
                        + "AND landing_page_slug IS NOT NULL AND (?::timestamptz IS NULL OR occurred_at >= ?) AND (?::timestamptz IS NULL OR occurred_at < ?) GROUP BY 1",
                rs -> { out.put(rs.getString("slug"), rs.getLong("n")); }, businessId, f, f, t, t);
        return out;
    }

    /** Conversions Google Ads itself reports per campaign and conversion action (GA4 events imported into Ads), summed over the window. */
    public List<GoogleActionRow> googleConversionsByAction(Long businessId, java.time.LocalDate from, java.time.LocalDate to) {
        return jdbc.query("SELECT campaign_id, action_name, SUM(all_conversions) AS n FROM google_ads_conversions_daily WHERE business_id = ? "
                        + "AND (?::date IS NULL OR day >= ?) AND (?::date IS NULL OR day <= ?) GROUP BY campaign_id, action_name",
                (rs, i) -> new GoogleActionRow(rs.getLong("campaign_id"), rs.getString("action_name"), rs.getDouble("n")),
                businessId, from == null ? null : java.sql.Date.valueOf(from), from == null ? null : java.sql.Date.valueOf(from),
                to == null ? null : java.sql.Date.valueOf(to), to == null ? null : java.sql.Date.valueOf(to));
    }

    public record GoogleActionRow(long campaignId, String actionName, double conversions) {}

    /** Every (campaign id, name) pair the Google Ads sync has stored, so lead and form texts can be matched to a campaign. */
    public List<Map.Entry<Long, String>> googleAdsCampaignNames(Long businessId) {
        return jdbc.query("SELECT DISTINCT campaign_id, campaign_name FROM google_ads_daily WHERE business_id = ?",
                (rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)), businessId);
    }

    public record AdsCampaignRow(long campaignId, String name, String status, long costMicros, long impressions, long clicks, double conversions) {}

    /** Google Ads spend per campaign from the daily sync, for the window (inclusive dates). */
    public List<AdsCampaignRow> findGoogleAdsCampaigns(Long businessId, java.time.LocalDate from, java.time.LocalDate to) {
        return jdbc.query(
                "SELECT campaign_id, MAX(campaign_name) AS name, MAX(campaign_status) AS status, SUM(cost_micros) AS cost, "
                        + "SUM(impressions) AS imps, SUM(clicks) AS clicks, SUM(conversions) AS conv "
                        + "FROM google_ads_daily WHERE business_id = ? "
                        + "AND (?::date IS NULL OR day >= ?) AND (?::date IS NULL OR day <= ?) "
                        + "GROUP BY campaign_id HAVING SUM(cost_micros) > 0 OR SUM(clicks) > 0 ORDER BY SUM(cost_micros) DESC",
                (rs, i) -> new AdsCampaignRow(rs.getLong("campaign_id"), rs.getString("name"), rs.getString("status"),
                        rs.getLong("cost"), rs.getLong("imps"), rs.getLong("clicks"), rs.getDouble("conv")),
                businessId, from == null ? null : java.sql.Date.valueOf(from), from == null ? null : java.sql.Date.valueOf(from),
                to == null ? null : java.sql.Date.valueOf(to), to == null ? null : java.sql.Date.valueOf(to));
    }
}
