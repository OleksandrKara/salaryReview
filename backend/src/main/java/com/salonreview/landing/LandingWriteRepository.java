package com.salonreview.landing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL for the landing write endpoints. Tables stay owned by salonLandings (same schema, same
 * columns), so every statement here is the one the Python repository ran; uuid and jsonb columns
 * get explicit casts because the values arrive as text.
 */
@Repository
public class LandingWriteRepository {

    private final JdbcTemplate jdbc;

    public LandingWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEvent(long businessId, String sessionId, String landingPageId, String variantId,
                            String eventType, String metadataJson) {
        jdbc.update("""
                INSERT INTO marketing.events (business_id, session_id, landing_page_id, variant_id, event_type, metadata)
                VALUES (?, ?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb)
                """, businessId, sessionId, landingPageId, variantId, eventType, metadataJson);
    }

    public void insertFunnelEvent(long businessId, String sessionId, String landingPageId, String variantId,
                                  String flowKey, String stepKey, int stepIndex, int stepCountTotal, String metadataJson) {
        jdbc.update("""
                INSERT INTO marketing.funnel_events
                    (business_id, session_id, landing_page_id, variant_id, flow_key, step_key, step_index, step_count_total, metadata)
                VALUES (?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?::jsonb)
                """, businessId, sessionId, landingPageId, variantId, flowKey, stepKey, stepIndex, stepCountTotal,
                metadataJson);
    }

    public void insertAttribution(long businessId, String bookingId, String landingPageId, String variantId, String source) {
        jdbc.update("""
                INSERT INTO marketing.attribution (business_id, booking_id, landing_page_id, variant_id, source)
                VALUES (?, ?, ?::uuid, ?::uuid, ?)
                ON CONFLICT (booking_id) DO NOTHING
                """, businessId, bookingId, landingPageId, variantId, source);
    }

    public void insertVisit(long businessId, LandingTracking t, LandingUserAgents.Context c) {
        jdbc.update("""
                INSERT INTO marketing.visits (
                    business_id, visitor_id, landing_path, referrer,
                    utm_source, utm_medium, utm_campaign, utm_term, utm_content,
                    fbclid, gclid, user_agent, device_type, os_name, os_version,
                    browser_name, browser_version, ip_address
                ) VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, businessId, t.visitorId(), t.landingPath(), t.referrer(), t.utmSource(), t.utmMedium(),
                t.utmCampaign(), t.utmTerm(), t.utmContent(), t.fbclid(), t.gclid(), c.userAgent(), c.deviceType(),
                c.osName(), c.osVersion(), c.browserName(), c.browserVersion(), c.ipAddress());
    }

    public void insertSubmission(long businessId, String visitorId, String submissionType, LandingTracking t,
                                 LandingUserAgents.Context c, String squareBookingId, String serviceName,
                                 BigDecimal price, String customerEmail, String customerPhone,
                                 String landingPageSlug, String variantName, String trafficSource) {
        jdbc.update("""
                INSERT INTO marketing.submissions (
                    business_id, visitor_id, submission_type, square_booking_id, service_name, price,
                    customer_email, customer_phone, landing_path, referrer,
                    utm_source, utm_medium, utm_campaign, utm_term, utm_content,
                    fbclid, gclid, user_agent, device_type, os_name, os_version,
                    browser_name, browser_version, ip_address, landing_page_slug, variant_name, traffic_source
                ) VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, businessId, visitorId, submissionType, squareBookingId, serviceName, price, customerEmail,
                customerPhone, t == null ? null : t.landingPath(), t == null ? null : t.referrer(),
                t == null ? null : t.utmSource(), t == null ? null : t.utmMedium(),
                t == null ? null : t.utmCampaign(), t == null ? null : t.utmTerm(),
                t == null ? null : t.utmContent(), t == null ? null : t.fbclid(), t == null ? null : t.gclid(),
                c.userAgent(), c.deviceType(), c.osName(), c.osVersion(), c.browserName(), c.browserVersion(),
                c.ipAddress(), landingPageSlug, variantName, trafficSource);
    }

    public void insertSmsConsent(long businessId, String phone, boolean consented, String text, String version,
                                 String source, String visitorId, String ip) {
        jdbc.update("""
                INSERT INTO marketing.sms_consent
                    (business_id, phone_number, consented, consent_text, consent_version, source, visitor_id, ip_address)
                VALUES (?, ?, ?, ?, ?, ?, ?::uuid, ?)
                """, businessId, phone, consented, text, version, source, visitorId, ip);
    }

    public void insertEmailConsent(long businessId, String email, boolean consented, String text, String version,
                                   String source, String visitorId, String ip) {
        jdbc.update("""
                INSERT INTO marketing.email_consent
                    (business_id, email_address, consented, consent_text, consent_version, source, visitor_id, ip_address)
                VALUES (?, ?, ?, ?, ?, ?, ?::uuid, ?)
                """, businessId, email, consented, text, version, source, visitorId, ip);
    }

    /** Slug and variant name for a tracking snapshot's opaque ids; either is null when unknown. */
    public String[] resolveLandingContext(String landingPageId, String variantId) {
        String slug = null;
        String name = null;
        if (landingPageId != null && !landingPageId.isEmpty()) {
            List<String> rows = jdbc.queryForList("SELECT slug FROM marketing.landing_pages WHERE id = ?::uuid",
                    String.class, landingPageId);
            slug = rows.isEmpty() ? null : rows.get(0);
        }
        if (variantId != null && !variantId.isEmpty()) {
            List<String> rows = jdbc.queryForList("SELECT name FROM marketing.landing_variants WHERE id = ?::uuid",
                    String.class, variantId);
            name = rows.isEmpty() ? null : rows.get(0);
        }
        return new String[] {slug, name};
    }

    public void upsertContactStep1(long businessId, String phone, String givenName, String email,
                                   String trafficSource, String utmSource, String utmMedium, String utmCampaign,
                                   String referrer, String landingPageSlug, String variantName,
                                   LandingUserAgents.Context c, String squareCustomerId) {
        jdbc.update("""
                INSERT INTO marketing.contacts (
                    business_id, phone_number, given_name, email_address,
                    original_traffic_source, marketing_traffic_source,
                    utm_source, utm_medium, utm_campaign, referrer,
                    landing_page_slug, variant_name,
                    device_type, os_name, os_version, browser_name, browser_version,
                    square_customer_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (business_id, phone_number) DO UPDATE SET
                    given_name = EXCLUDED.given_name,
                    email_address = COALESCE(EXCLUDED.email_address, marketing.contacts.email_address),
                    marketing_traffic_source = EXCLUDED.marketing_traffic_source,
                    utm_source = EXCLUDED.utm_source,
                    utm_medium = EXCLUDED.utm_medium,
                    utm_campaign = EXCLUDED.utm_campaign,
                    referrer = EXCLUDED.referrer,
                    device_type = EXCLUDED.device_type,
                    os_name = EXCLUDED.os_name,
                    os_version = EXCLUDED.os_version,
                    browser_name = EXCLUDED.browser_name,
                    browser_version = EXCLUDED.browser_version,
                    square_customer_id = COALESCE(EXCLUDED.square_customer_id, marketing.contacts.square_customer_id),
                    updated_at = now()
                """, businessId, phone, givenName, email, trafficSource, trafficSource, utmSource, utmMedium,
                utmCampaign, referrer, landingPageSlug, variantName, c.deviceType(), c.osName(), c.osVersion(),
                c.browserName(), c.browserVersion(), squareCustomerId);
    }

    public void updateContactAfterBooking(long businessId, String phone, String givenName, String email,
                                          String trafficSource, String utmSource, String utmMedium,
                                          String utmCampaign, String referrer, String landingPageSlug,
                                          String variantName, LandingUserAgents.Context c, boolean smsConsent,
                                          boolean emailConsent, String squareCustomerId, String squareBookingId,
                                          String bookingStatus, Instant bookingStartAt, String serviceName,
                                          BigDecimal price, String artistName) {
        jdbc.update("""
                INSERT INTO marketing.contacts (
                    business_id, phone_number, given_name, email_address,
                    original_traffic_source, marketing_traffic_source,
                    utm_source, utm_medium, utm_campaign, referrer,
                    landing_page_slug, variant_name,
                    device_type, os_name, os_version, browser_name, browser_version,
                    sms_marketing_consent, email_marketing_consent,
                    square_customer_id, square_booking_id, booking_status, booking_start_at,
                    booking_service_name, booking_price, booking_artist_name, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()
                )
                ON CONFLICT (business_id, phone_number) DO UPDATE SET
                    given_name = EXCLUDED.given_name,
                    email_address = COALESCE(EXCLUDED.email_address, marketing.contacts.email_address),
                    marketing_traffic_source = EXCLUDED.marketing_traffic_source,
                    utm_source = EXCLUDED.utm_source,
                    utm_medium = EXCLUDED.utm_medium,
                    utm_campaign = EXCLUDED.utm_campaign,
                    referrer = EXCLUDED.referrer,
                    device_type = EXCLUDED.device_type,
                    os_name = EXCLUDED.os_name,
                    os_version = EXCLUDED.os_version,
                    browser_name = EXCLUDED.browser_name,
                    browser_version = EXCLUDED.browser_version,
                    sms_marketing_consent = (
                        COALESCE(marketing.contacts.sms_marketing_consent, false) OR EXCLUDED.sms_marketing_consent
                    ),
                    email_marketing_consent = EXCLUDED.email_marketing_consent,
                    square_customer_id = EXCLUDED.square_customer_id,
                    square_booking_id = EXCLUDED.square_booking_id,
                    booking_status = EXCLUDED.booking_status,
                    booking_start_at = EXCLUDED.booking_start_at,
                    booking_service_name = EXCLUDED.booking_service_name,
                    booking_price = EXCLUDED.booking_price,
                    booking_artist_name = EXCLUDED.booking_artist_name,
                    updated_at = now()
                """, businessId, phone, givenName, email, trafficSource, trafficSource, utmSource, utmMedium,
                utmCampaign, referrer, landingPageSlug, variantName, c.deviceType(), c.osName(), c.osVersion(),
                c.browserName(), c.browserVersion(), smsConsent, emailConsent, squareCustomerId, squareBookingId,
                bookingStatus, bookingStartAt == null ? null : OffsetDateTime.ofInstant(bookingStartAt, java.time.ZoneOffset.UTC),
                serviceName, price, artistName);
    }

    public void insertAbuseBlock(long businessId, String endpoint, String reason, String phone, String ip) {
        jdbc.update("""
                INSERT INTO marketing.abuse_blocks (business_id, endpoint, reason, phone_number, ip_address)
                VALUES (?, ?, ?, ?, ?)
                """, businessId, endpoint, reason, phone, ip);
    }

    public long countRecentSubmissionsByPhone(String phone, List<String> types, Instant since) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM marketing.submissions
                WHERE customer_phone = ? AND submission_type = ANY(?::text[]) AND occurred_at >= ?
                """, Long.class, phone, types.toArray(new String[0]),
                OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC));
        return n == null ? 0 : n;
    }

    public long countRecentSubmissionsByIp(String ip, List<String> types, Instant since) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM marketing.submissions
                WHERE ip_address = ? AND submission_type = ANY(?::text[]) AND occurred_at >= ?
                """, Long.class, ip, types.toArray(new String[0]),
                OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC));
        return n == null ? 0 : n;
    }
}
