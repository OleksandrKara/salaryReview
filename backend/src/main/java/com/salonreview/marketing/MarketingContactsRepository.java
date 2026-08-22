package com.salonreview.marketing;

import com.salonreview.util.PhoneNumbers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
            /** One of the five TrafficSourceSql buckets, computed server-side — see
             * MarketingContactDto.Contact#channel. */
            String channel,
            /** Latest touch's raw UTM — like marketingTrafficSource, overwritten on every
             * capture event, not preserved as first-touch. */
            String utmSource,
            String utmMedium,
            String utmCampaign,
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
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** One row of marketing.submissions — every Step 1 capture, booking, or 4-hand request this
     * phone/email ever made, each carrying the landing page/variant/traffic-source context at
     * that moment. trafficSource is the same classify_traffic_source() label used for
     * contacts.original_traffic_source (falls back through fbclid/gclid/referrer to
     * "Direct / No referrer") — never blank on a submission recorded after this column existed;
     * null only on rows written before it did.
     */
    public record RawSubmission(
            String submissionType,
            Instant occurredAt,
            String landingPageSlug,
            String variantName,
            String trafficSource,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String serviceName,
            BigDecimal price
    ) {}

    /** The marketing.submissions row that actually created a given Square booking — matched by
     * square_booking_id, populated only for the "booking" submission_type.
     */
    public record RawAppointmentSubmission(
            String squareBookingId,
            Instant occurredAt,
            String trafficSource,
            String deviceType,
            String osName,
            String osVersion,
            String browserName
    ) {}

    private static final String CONTACT_COLUMNS = """
            id, phone_number, given_name, email_address,
            original_traffic_source, marketing_traffic_source,
            utm_source, utm_medium, utm_campaign,
            landing_page_slug, variant_name,
            device_type, os_name, os_version, browser_name, browser_version,
            sms_marketing_consent, email_marketing_consent,
            square_customer_id, square_booking_id, booking_status, booking_start_at,
            booking_service_name, booking_price, booking_artist_name, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public MarketingContactsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Scoped to the caller's own business — marketing.contacts.business_id is a plain trusted
     * integer written by salonLandings on every capture (see
     * openspec/changes/multi-tenant-salon-platform); previously this method pooled every
     * business's contacts together with no filter at all, so any business's owner saw every real
     * customer's name/phone/email from every other business too.
     */
    public List<RawContact> listAllForBusiness(Long businessId) {
        String sql = "SELECT " + CONTACT_COLUMNS + ", " + TrafficSourceSql.contactChannelCase("c") + " AS channel"
                + " FROM marketing.contacts c WHERE c.business_id = ? ORDER BY c.created_at DESC";
        return jdbcTemplate.query(sql, MarketingContactsRepository::mapContact, businessId);
    }

    /** Backs {@code MarketingContactsService#enrichContacts} — the lazy, scroll-triggered
     * follow-up fetch for just the contact rows actually visible on screen (see #listAllForBusiness's
     * own "most Square-call-heavy" note, now only paid for on-demand instead of for the whole list).
     * Empty input returns an empty list, no query fired. Scoped by business_id so a contact id
     * belonging to another business is simply absent from the result, not an error — same
     * "belongs to another business -> absent" contract as before, now actually enforced. */
    public List<RawContact> findByIds(Collection<UUID> ids, Long businessId) {
        if (ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT " + CONTACT_COLUMNS + ", " + TrafficSourceSql.contactChannelCase("c") + " AS channel"
                + " FROM marketing.contacts c WHERE c.id IN (" + placeholders + ") AND c.business_id = ?";
        List<Object> params = new java.util.ArrayList<>(ids);
        params.add(businessId);
        return jdbcTemplate.query(sql, MarketingContactsRepository::mapContact, params.toArray());
    }

    /** contacts is unique on (business_id, phone_number), so this is at most one row — backs the manager
     * conversation view's contact info panel (see openspec/changes, MessagesView contact
     * sidebar). Empty when this phone number never went through the tracked capture flow (e.g. a
     * checkout-review or lead-follow-up text sent purely from Square/booking data). */
    public Optional<RawContact> findByPhoneNumber(String phoneNumber, Long businessId) {
        // Last-10-digits match, not exact string equality — marketing.contacts is owned by the
        // separate salonLandings service, whose own phone-number format isn't guaranteed to match
        // whatever format the caller happens to be holding (e.g. this app's own E.164-normalized
        // numbers vs. a customer-typed "(310) 779-6334") — see PhoneNumbers' own doc comment.
        // Scoped by business_id so two different businesses' customers who happen to share a phone
        // number (e.g. a shared family/work line) never cross-resolve to each other's contact.
        String sql = "SELECT " + CONTACT_COLUMNS + ", " + TrafficSourceSql.contactChannelCase("c") + " AS channel"
                + " FROM marketing.contacts c"
                + " WHERE RIGHT(regexp_replace(c.phone_number, '[^0-9]', '', 'g'), 10) = ? AND c.business_id = ?";
        return jdbcTemplate.query(sql, MarketingContactsRepository::mapContact, PhoneNumbers.last10Digits(phoneNumber), businessId)
                .stream().findFirst();
    }

    /** Given name + linked Square customer id + this app's own SMS-marketing-consent column (if
     * any), for a batch of phone numbers — a cheap, name-only lookup backing the Messages
     * conversation list, deliberately not the full {@link #findByPhoneNumber} query (which also
     * pulls submission history). {@code last10} is the row's own phone_number reduced to its last
     * 10 digits — the reliable join key back to the caller's input, since marketing.contacts'
     * stored format isn't guaranteed to match the format the caller queried with (see
     * PhoneNumbers' own doc comment; MarketingContactsService#resolveDisplayNames keys its lookup
     * map by this field, not the raw {@code phoneNumber}). See
     * MarketingContactsService#resolveDisplayNames. */
    public record PhoneName(String phoneNumber, String last10, String givenName, String squareCustomerId,
                             Boolean smsMarketingConsent) {}

    public List<PhoneName> findNamesByPhoneNumbers(Collection<String> phoneNumbers) {
        if (phoneNumbers.isEmpty()) return List.of();
        List<String> last10s = phoneNumbers.stream().map(PhoneNumbers::last10Digits).collect(Collectors.toList());
        String placeholders = last10s.stream().map(p -> "?").collect(Collectors.joining(","));
        String sql = "SELECT phone_number, given_name, square_customer_id, sms_marketing_consent,"
                + " RIGHT(regexp_replace(phone_number, '[^0-9]', '', 'g'), 10) AS last10"
                + " FROM marketing.contacts"
                + " WHERE RIGHT(regexp_replace(phone_number, '[^0-9]', '', 'g'), 10) IN (" + placeholders + ")";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new PhoneName(rs.getString("phone_number"), rs.getString("last10"),
                        rs.getString("given_name"), rs.getString("square_customer_id"),
                        (Boolean) rs.getObject("sms_marketing_consent")),
                last10s.toArray());
    }

    /** Contacts eligible for {@code LeadFollowUpScheduler}'s poll — old enough ({@code
     * updatedAt <= olderThan}) but not so old the poll's own scan window has moved past them
     * ({@code updatedAt >= newerThan}), and never already processed by this automation
     * ({@code lead_followup_send}, this app's own table, joined read-only against the shared
     * marketing schema — see openspec/changes/lead-followup-and-manager-inbox design.md D1/D3).
     *
     * <p>Keyed on {@code updated_at}, not {@code created_at}: marketing.contacts is unique on
     * phone_number, so a returning lead who submits the capture form again months after their
     * first visit doesn't get a new row — the existing one is upserted, which only bumps
     * updated_at. Filtering on created_at (the original design) meant that row's age window closed
     * the moment it was first created and never opened again, so the automation could never see a
     * repeat lead at all, no matter how long after their first visit they came back — confirmed
     * live: a real re-submission months after a contact's created_at sat in this table for 10+
     * minutes without ever being picked up. updated_at also does the right thing for a genuinely
     * new contact (INSERT sets both columns to the same instant), so first-time leads are
     * unaffected.
     *
     * <p>The {@code NOT EXISTS} below compares against {@code lead_followup_send.contact_updated_at}
     * (not just {@code contact_id}) for the same reason: a lead who left contact info again after
     * already getting a nudge is a genuinely new touch (a fresh {@code updated_at}), not a repeat
     * of the first one — see V60 and {@code LeadFollowUpSend}'s own doc comment. Without this, the
     * updated_at-based re-eligibility above was only half the fix: the row itself became visible
     * to this query again, but the old contact_id-only exists-check still silently swallowed it —
     * confirmed live against a real repeat submission that got exactly one nudge, ever, no matter
     * how many times contact info was resubmitted afterward.
     */
    /** Scoped to one business — previously unscoped, meaning {@code LeadFollowUpScheduler} sent
     * every business's pending leads a follow-up text branded as (and sent from) whichever one
     * business it happened to be hardcoded to run as. */
    public List<RawContact> findPendingFollowUp(Instant olderThan, Instant newerThan, Long businessId) {
        String sql = "SELECT " + CONTACT_COLUMNS + ", " + TrafficSourceSql.contactChannelCase("c") + " AS channel"
                + " FROM marketing.contacts c"
                + " WHERE c.business_id = ? AND c.updated_at <= ? AND c.updated_at >= ?"
                + " AND NOT EXISTS (SELECT 1 FROM lead_followup_send lfs"
                + "     WHERE lfs.contact_id = c.id AND lfs.contact_updated_at >= c.updated_at)"
                + " ORDER BY c.updated_at ASC";
        return jdbcTemplate.query(sql, MarketingContactsRepository::mapContact,
                businessId, Timestamp.from(olderThan), Timestamp.from(newerThan));
    }

    /** One channel-attributed contact — phone_number is the stable match key within this business
     * (contacts is unique on (business_id, phone_number), and callers here always scope by
     * businessId), squareCustomerId the customer profile we happened to link at the time (nullable: none
     * captured yet), channel which {@link TrafficSourceSql} bucket the contact's own utm/referrer
     * columns classify as (null for the rare edge case that fits none of the five). The linked
     * square_customer_id can go stale — e.g. a follow-up appointment booked by phone gets matched or
     * created against a *different* Square profile for the same person — so callers should also
     * resolve by phone (SquareClient.customerIdsForPhone) rather than trust this id alone.
     */
    public record AdsAttributedContact(
            String phoneNumber, String squareCustomerId, Instant firstTouch, String channel) {}

    /** Every contact whose channel classifies into one of the given sources, each with the earliest
     * moment we ever captured them — used to tell a customer whose Square record was created fresh
     * off this touch from one who already existed in Square and simply came back through one.
     * contacts is unique on (business_id, phone_number), and this query is already scoped to one
     * business, so this is naturally one row per contact — no grouping/aggregation needed.
     */
    public List<AdsAttributedContact> findAdsAttributedContacts(Set<String> sources, Long businessId) {
        return findAdsAttributedContacts(sources, null, businessId);
    }

    /** Same as the two-arg overload, optionally scoped to one landing page (e.g. "home" vs "mani") —
     * {@code landingPageSlug == null} pools every page *within this business*, no longer every
     * business — {@code businessId} is always required now (previously, pooling was unscoped
     * across businesses entirely when landingPageSlug was also null). */
    public List<AdsAttributedContact> findAdsAttributedContacts(Set<String> sources, String landingPageSlug, Long businessId) {
        String sql = "SELECT phone_number, square_customer_id, created_at, " + TrafficSourceSql.contactChannelCase("c") + " AS channel"
                + " FROM marketing.contacts c WHERE " + TrafficSourceSql.contactInSources("c", sources)
                + " AND c.business_id = ?"
                + (landingPageSlug != null ? " AND c.landing_page_slug = ?" : "");
        Object[] params = landingPageSlug != null ? new Object[]{businessId, landingPageSlug} : new Object[]{businessId};
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AdsAttributedContact(
                rs.getString("phone_number"),
                rs.getString("square_customer_id"),
                toInstant(rs.getTimestamp("created_at")),
                rs.getString("channel")
        ), params);
    }

    /** Every contact regardless of channel (ads, organic, direct, referral) — the "All traffic"
     * counterpart to findAdsAttributedContacts, for pages like the homepage where paid clicks
     * aren't the only (or even the main) source of visitors. {@code channel} is null for anything
     * that isn't a recognized bucket; downstream code in "all traffic" mode never filters on it.
     * {@code landingPageSlug == null} pools every page, same convention as above.
     */
    public List<AdsAttributedContact> findAllAttributedContacts(String landingPageSlug, Long businessId) {
        String sql = "SELECT phone_number, square_customer_id, created_at, " + TrafficSourceSql.contactChannelCase("c") + " AS channel"
                + " FROM marketing.contacts c WHERE c.business_id = ?"
                + (landingPageSlug != null ? " AND c.landing_page_slug = ?" : "");
        Object[] params = landingPageSlug != null ? new Object[]{businessId, landingPageSlug} : new Object[]{businessId};
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AdsAttributedContact(
                rs.getString("phone_number"),
                rs.getString("square_customer_id"),
                toInstant(rs.getTimestamp("created_at")),
                rs.getString("channel")
        ), params);
    }

    /** Every submission this phone number ever made, most recent first. Phone is the sole match
     * key — it's the contact's stable identifier (marketing.contacts is keyed on phone_number,
     * and SquareCustomerGateway matches phone-first-then-email for the same reason): the same
     * real person can submit the form under more than one email over time, and matching on email
     * too would risk pulling a *different* phone number's history in just because it happens to
     * share an email (e.g. a shared family address).
     */
    public List<RawSubmission> findSubmissionHistory(String phoneNumber) {
        String sql = """
                SELECT submission_type, occurred_at, landing_page_slug, variant_name,
                       traffic_source, utm_source, utm_medium, utm_campaign, service_name, price
                FROM marketing.submissions
                WHERE customer_phone = ?
                ORDER BY occurred_at DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawSubmission(
                rs.getString("submission_type"),
                toInstant(rs.getTimestamp("occurred_at")),
                rs.getString("landing_page_slug"),
                rs.getString("variant_name"),
                rs.getString("traffic_source"),
                rs.getString("utm_source"),
                rs.getString("utm_medium"),
                rs.getString("utm_campaign"),
                rs.getString("service_name"),
                rs.getBigDecimal("price")
        ), phoneNumber);
    }

    /** The originating submission for each of the given Square booking ids, keyed by booking id
     * — only bookings that came through our own funnel have one. Empty map for an empty input,
     * no query fired.
     */
    public Map<String, RawAppointmentSubmission> findSubmissionsByBookingIds(List<String> bookingIds) {
        if (bookingIds.isEmpty()) return Map.of();
        String placeholders = bookingIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT square_booking_id, occurred_at, traffic_source,
                       device_type, os_name, os_version, browser_name
                FROM marketing.submissions
                WHERE square_booking_id IN (%s)
                """.formatted(placeholders);
        List<RawAppointmentSubmission> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new RawAppointmentSubmission(
                rs.getString("square_booking_id"),
                toInstant(rs.getTimestamp("occurred_at")),
                rs.getString("traffic_source"),
                rs.getString("device_type"),
                rs.getString("os_name"),
                rs.getString("os_version"),
                rs.getString("browser_name")
        ), bookingIds.toArray());
        return rows.stream().collect(Collectors.toMap(RawAppointmentSubmission::squareBookingId, r -> r, (a, b) -> a));
    }

    private static RawContact mapContact(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RawContact(
                (UUID) rs.getObject("id"),
                rs.getString("phone_number"),
                rs.getString("given_name"),
                rs.getString("email_address"),
                rs.getString("original_traffic_source"),
                rs.getString("marketing_traffic_source"),
                rs.getString("channel"),
                rs.getString("utm_source"),
                rs.getString("utm_medium"),
                rs.getString("utm_campaign"),
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
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
