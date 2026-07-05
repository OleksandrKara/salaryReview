package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads marketing.contacts — leads captured by the separate salonLandings service as soon as
 * Step 1 (name + phone) is submitted, later linked to a real Square booking/customer if one
 * happens. Plain JdbcTemplate for the same reason as MarketingDashboardRepository: this table
 * is owned and migrated by salonLandings, not by this app's Flyway/JPA.
 */
@Repository
public class MarketingContactsRepository {

    public record RawContact(
            UUID id,
            String phoneNumber,
            String givenName,
            String emailAddress,
            String originalTrafficSource,
            String marketingTrafficSource,
            String landingPageSlug,
            String variantName,
            String deviceType,
            String osName,
            String osVersion,
            String browserName,
            String browserVersion,
            Boolean smsMarketingConsent,
            Boolean emailMarketingConsent,
            String squareCustomerId,
            String squareBookingId,
            String bookingStatus,
            Instant bookingStartAt,
            String bookingServiceName,
            BigDecimal bookingPrice,
            String bookingArtistName,
            Instant createdAt
    ) {}

    /** One row of marketing.submissions — every Step 1 capture, booking, or 4-hand request this
     * phone/email ever made, each carrying the landing page/variant/UTM context at that moment.
     */
    public record RawSubmission(
            String submissionType,
            Instant occurredAt,
            String landingPageSlug,
            String variantName,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String serviceName,
            BigDecimal price
    ) {}

    private static final String CONTACT_COLUMNS = """
            id, phone_number, given_name, email_address,
            original_traffic_source, marketing_traffic_source,
            landing_page_slug, variant_name,
            device_type, os_name, os_version, browser_name, browser_version,
            sms_marketing_consent, email_marketing_consent,
            square_customer_id, square_booking_id, booking_status, booking_start_at,
            booking_service_name, booking_price, booking_artist_name, created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public MarketingContactsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RawContact> listAll() {
        String sql = "SELECT " + CONTACT_COLUMNS + " FROM marketing.contacts ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, MarketingContactsRepository::mapContact);
    }

    /** Every submission this phone number or email address ever made, most recent first —
     * matched on either since a contact's email can be filled in on a later visit than their
     * first (phone is the stable identifier; email may only appear from a later submission).
     */
    public List<RawSubmission> findSubmissionHistory(String phoneNumber, String emailAddress) {
        String sql = """
                SELECT submission_type, occurred_at, landing_page_slug, variant_name,
                       utm_source, utm_medium, utm_campaign, service_name, price
                FROM marketing.submissions
                WHERE customer_phone = ? OR (? IS NOT NULL AND customer_email = ?)
                ORDER BY occurred_at DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawSubmission(
                rs.getString("submission_type"),
                toInstant(rs.getTimestamp("occurred_at")),
                rs.getString("landing_page_slug"),
                rs.getString("variant_name"),
                rs.getString("utm_source"),
                rs.getString("utm_medium"),
                rs.getString("utm_campaign"),
                rs.getString("service_name"),
                rs.getBigDecimal("price")
        ), phoneNumber, emailAddress, emailAddress);
    }

    private static RawContact mapContact(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RawContact(
                (UUID) rs.getObject("id"),
                rs.getString("phone_number"),
                rs.getString("given_name"),
                rs.getString("email_address"),
                rs.getString("original_traffic_source"),
                rs.getString("marketing_traffic_source"),
                rs.getString("landing_page_slug"),
                rs.getString("variant_name"),
                rs.getString("device_type"),
                rs.getString("os_name"),
                rs.getString("os_version"),
                rs.getString("browser_name"),
                rs.getString("browser_version"),
                (Boolean) rs.getObject("sms_marketing_consent"),
                (Boolean) rs.getObject("email_marketing_consent"),
                rs.getString("square_customer_id"),
                rs.getString("square_booking_id"),
                rs.getString("booking_status"),
                toInstant(rs.getTimestamp("booking_start_at")),
                rs.getString("booking_service_name"),
                rs.getBigDecimal("booking_price"),
                rs.getString("booking_artist_name"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
