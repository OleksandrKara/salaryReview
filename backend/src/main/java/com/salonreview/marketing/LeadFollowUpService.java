package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.web.GeoIpService;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Lead CRM. A lead is one person (phone digits, else email): its state lives on the earliest
 * lead_intake row of that person, every later row is a "touch" (another call, text or form).
 */
@Service
public class LeadFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(LeadFollowUpService.class);
    static final Set<String> STATUSES = Set.of("NEW", "CONTACTED", "BOOKED", "LOST", "SPAM");
    /** System-only status of the pre-CRM archive; a lead can leave it but nothing can put one there (see V196). */
    static final String OUTDATED = "OUTDATED";
    static final int OUTDATED_PAGE = 50;
    static final Set<String> LOST_REASONS = Set.of("PRICE", "NO_ANSWER", "NOT_INTERESTED", "BOUGHT_ELSEWHERE",
            "NOT_ELIGIBLE", "TOO_FAR", "WRONG_NUMBER_SPAM", "TIMING", "OTHER");
    static final Set<String> SOURCES = Set.of("Instagram", "Yelp", "Google", "Facebook", "TikTok", "Referral",
            "Walk-in", "Phone", "Other", "Unknown");
    static final Set<String> MANUAL_EVENT_TYPES = Set.of("NOTE");
    private static final String SQUARE_CUSTOMER_URL = "https://app.squareup.com/dashboard/customers/directory/customer/";

    /** Identity of a person across touches. */
    private static final String KEY = "COALESCE(NULLIF(regexp_replace(COALESCE(phone_number, ''), '\\D', '', 'g'), ''), "
            + "lower(NULLIF(email, '')), 'id' || id::text)";

    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext business;
    private final TelegramNotificationService telegram;
    private final String publicBaseUrl;
    private final GeoIpService geo;
    private final SquareClientProvider squares;
    private final LeadIntakeService intake;
    private final ConsultationCatalog consultations;
    private final com.salonreview.repo.MailchimpConfigRepository mailchimpConfigs;
    private final com.salonreview.sms.MailchimpClient mailchimpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public LeadFollowUpService(JdbcTemplate jdbc, CurrentBusinessContext business, TelegramNotificationService telegram, GeoIpService geo,
                               SquareClientProvider squares, LeadIntakeService intake, ConsultationCatalog consultations,
                               com.salonreview.repo.MailchimpConfigRepository mailchimpConfigs, com.salonreview.sms.MailchimpClient mailchimpClient,
                               MarketingContactsService contactsService, com.salonreview.repo.BlockedNumberRepository blockedNumbers,
                               com.salonreview.sms.SmsMessageLogService smsLog,
                               @org.springframework.beans.factory.annotation.Value("${app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.business = business;
        this.telegram = telegram;
        this.publicBaseUrl = publicBaseUrl;
        this.geo = geo;
        this.squares = squares;
        this.intake = intake;
        this.consultations = consultations;
        this.mailchimpConfigs = mailchimpConfigs;
        this.mailchimpClient = mailchimpClient;
        this.contactsService = contactsService;
        this.blockedNumbers = blockedNumbers;
        this.smsLog = smsLog;
    }

    private final MarketingContactsService contactsService;
    private final com.salonreview.repo.BlockedNumberRepository blockedNumbers;
    private final com.salonreview.sms.SmsMessageLogService smsLog;

    public record Place(String countryCode, String country, String city, String region, String flag, String scope) {}
    public record Badges(boolean vip, Integer visitCount, boolean smsConsent, boolean negativeFeedback, boolean blocked,
                         boolean optedOut, boolean spamFlag, boolean clickedGoogle, boolean clickedYelp, boolean clickedFeedback) {}

    public record ListBadge(boolean vip, Integer visitCount, boolean smsConsent, boolean negativeFeedback, boolean blocked,
                            boolean optedOut, boolean spamFlag, boolean clickedGoogle, boolean clickedYelp, boolean clickedFeedback,
                            String flag, String place, String scope) {}

    public record LeadView(long id, String channel, String source, String phoneNumber, String email, String name,
                           String detail, String status, String callbackAt, String createdAt, String lastTouchAt,
                           int touches, String squareCustomerId, String squareUrl,
                           String lostReason, String lostComment, boolean sourceEditable,
                           String lastNote, String lastNoteBy, String lastNoteAt, String activityAt,
                           boolean academy, String consultState, String consultAt,
                           int attempts, boolean closeReady) {}

    public record Touch(long id, String channel, String source, String detail, String createdAt,
                        java.util.Map<String, String> payload) {}

    public record Origin(String type, String at, String landingPage, String variant, String trafficSource,
                         String utmSource, String utmMedium, String utmCampaign, String utmTerm, String utmContent,
                         boolean googleClick, boolean facebookClick, String referrer, String device, String os,
                         String browser, String ip, String location, String service) {}

    public record Event(long id, String type, String text, String createdBy, String createdAt) {}

    public record Appointment(String id, String status, String startAt, String services, String technician,
                              Integer minutes, java.math.BigDecimal paid, String consultKind) {}

    public record Spend(java.math.BigDecimal total, java.math.BigDecimal tips, int visits, String firstVisit,
                        String lastVisit, String nextAppointment) {}

    public record Sms(long id, String direction, String body, String at, String status) {}

    public record EmailSent(long id, String kind, String sentAt, String openedAt, String clickedAt, boolean hasContent) {}

    /** One website form the person filled in: what they typed and where they came from. */
    public record Form(String key, String kind, String at, java.util.Map<String, String> fields) {}

    public record LeadDetail(LeadView lead, List<Touch> touches, List<Event> events, List<Origin> origins,
                             List<Appointment> appointments, Spend spend, List<EmailSent> emails,
                             List<Sms> sms, String squareNote, List<Payment> payments, List<Form> forms,
                             Place place, Badges badges) {}

    /** A completed Square payment that is not part of a booked visit day (invoice, deposit, course payment). */
    public record Payment(String id, String at, java.math.BigDecimal amount, java.math.BigDecimal tip, String description) {}

    public record UpdateRequest(String status, String callbackAt, Boolean clearCallback, String lostReason,
                                String lostComment, String source) {}

    public record LostRow(String reason, String source, int count) {}

    public record LostReport(int days, int total, List<LostRow> byReason, List<LostRow> bySource) {}

    public record EventRequest(String type, String text) {}

    private final RowMapper<LeadView> leadMapper = (rs, i) -> {
        String customer = rs.getString("square_customer_id");
        return new LeadView(rs.getLong("id"), rs.getString("channel"), rs.getString("source"),
                rs.getString("phone_number"), rs.getString("email"), rs.getString("name"), rs.getString("detail"),
                rs.getString("status"), iso(rs.getTimestamp("callback_at")), iso(rs.getTimestamp("created_at")),
                iso(rs.getTimestamp("last_touch_at")), rs.getInt("touches"), customer,
                customer == null ? null : SQUARE_CUSTOMER_URL + customer,
                rs.getString("lost_reason"), rs.getString("lost_comment"), rs.getBoolean("source_editable"),
                rs.getString("last_note"), rs.getString("last_note_by"), iso(rs.getTimestamp("last_note_at")), iso(rs.getTimestamp("activity_at")),
                rs.getBoolean("academy"), rs.getString("consult_state"), iso(rs.getTimestamp("consult_at")),
                rs.getInt("attempts"), closeReady(rs.getString("status"), rs.getInt("attempts"), rs.getTimestamp("first_attempt_at")));
    };

    static final int ATTEMPT_LIMIT = 3;
    static final int ATTEMPT_WINDOW_DAYS = 7;
    static final int FOLLOW_UP_DAYS = 2;

    /** Suggest closing as LOST: 3 unanswered attempts, or 2 when the first one is a week old. */
    static boolean closeReady(String status, int attempts, Timestamp firstAttempt) {
        if (!"NEW".equals(status) && !"CONTACTED".equals(status)) return false;
        if (attempts >= ATTEMPT_LIMIT) return true;
        return attempts >= 2 && firstAttempt != null
                && firstAttempt.toInstant().isBefore(Instant.now().minus(java.time.Duration.ofDays(ATTEMPT_WINDOW_DAYS)));
    }

    private static final String LEADS_SELECT = """
            WITH keyed AS (
              SELECT l.*, %s AS k FROM lead_intake l
              WHERE business_id = ? AND outcome <> 'DUPLICATE'
            ), agg AS (
              SELECT k, count(*) AS touches, max(created_at) AS last_touch_at,
                     (array_agg(square_customer_id ORDER BY (square_customer_id IS NULL), created_at))[1] AS customer,
                     (array_agg(name ORDER BY (name IS NULL), created_at))[1] AS any_name,
                     bool_or(channel = 'Academy') AS academy
              FROM keyed GROUP BY k
            )
            SELECT f.id, f.channel, f.source, COALESCE(sc.phone_number, f.phone_number) AS phone_number,
                   COALESCE(sc.email_address, f.email) AS email,
                   COALESCE(NULLIF(trim(concat_ws(' ', sc.given_name, sc.family_name)), ''), f.name, agg.any_name) AS name,
                   f.detail, f.status, f.callback_at, f.created_at, agg.last_touch_at, agg.touches::int AS touches,
                   agg.customer AS square_customer_id, agg.academy, f.lost_reason, f.lost_comment,
                   (f.channel = 'Square' OR f.source = 'Unknown') AS source_editable,
                   n.text AS last_note, n.created_by AS last_note_by, n.created_at AS last_note_at,
                   GREATEST(agg.last_touch_at, f.updated_at, n.created_at) AS activity_at,
                   COALESCE(att.attempts, 0) AS attempts, att.first_attempt_at
            FROM (SELECT DISTINCT ON (k) * FROM keyed ORDER BY k, created_at, id) f JOIN agg ON agg.k = f.k
              LEFT JOIN square_customer sc ON sc.business_id = f.business_id AND sc.square_customer_id = agg.customer
              LEFT JOIN LATERAL (SELECT e.text, e.created_by, e.created_at FROM lead_events e WHERE e.lead_id = f.id
                                 AND e.type IN ('NOTE', 'CALL', 'AGREEMENT') ORDER BY e.created_at DESC LIMIT 1) n ON TRUE
              LEFT JOIN LATERAL (SELECT count(*)::int AS attempts, min(e.created_at) AS first_attempt_at FROM lead_events e
                                 WHERE e.lead_id = f.id AND e.type = 'ATTEMPT' AND e.created_at > GREATEST(agg.last_touch_at,
                                   COALESCE((SELECT max(c.created_at) FROM lead_events c WHERE c.lead_id = f.id AND c.type = 'STATUS'
                                             AND c.text IN ('LOST', 'BOOKED', 'SPAM')), 'epoch'))) att ON TRUE
            """.formatted(KEY);

    /** Leads plus the person's latest consultation: UPCOMING, NO_SHOW (client did not come), or DONE when no real procedure was booked after it. */
    private String leadsSql(long businessId, boolean includeOutdated) {
        return "SELECT x.*, c.consult_at, c.consult_state FROM (" + LEADS_SELECT
                + (includeOutdated ? "" : " WHERE f.status <> 'OUTDATED' ") + ") x LEFT JOIN LATERAL ("
                + "SELECT b.start_at AS consult_at, CASE WHEN b.status = 'NO_SHOW' THEN 'NO_SHOW' WHEN b.start_at > now() THEN 'UPCOMING' ELSE 'DONE' END AS consult_state "
                + "FROM square_booking b WHERE b.business_id = ? AND b.square_customer_id = x.square_customer_id AND b.status IN ('ACCEPTED', 'NO_SHOW') "
                + "AND " + ConsultationCatalog.isConsultationSql("b", consultations.ids(businessId))
                + " AND NOT EXISTS (SELECT 1 FROM square_booking p WHERE p.business_id = b.business_id AND p.square_customer_id = b.square_customer_id "
                + "AND p.status = 'ACCEPTED' AND p.start_at >= b.start_at AND NOT "
                + ConsultationCatalog.isConsultationSql("p", consultations.ids(businessId)) + ") "
                + "ORDER BY b.start_at DESC LIMIT 1) c ON TRUE";
    }

    public List<LeadView> list(String status) {
        return listFor(business.id(), status);
    }

    public List<LeadView> listFor(long businessId, String status) {
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("SELECT * FROM (" + leadsSql(businessId, false) + ") y WHERE (?::text IS NULL OR status = ?) "
                + "ORDER BY (status = 'NEW') DESC, activity_at DESC LIMIT 1000", leadMapper, businessId, businessId, filter, filter);
    }

    public record OutdatedRow(long id, String name, String phoneNumber, String email, String source, String createdAt,
                              int visits, String lastVisitAt, String nextVisitAt, boolean consulted) {}

    public record OutdatedPage(int total, int clients, int never, int page, int pages, List<OutdatedRow> items) {}

    /** The pre-CRM archive, newest first. seg: "clients" (has visited), "never" (never visited) or all. */
    public OutdatedPage outdated(String q, String seg, int page) {
        long b = business.id();
        String cons = ConsultationCatalog.isConsultationSql("b", consultations.ids(b));
        String needle = q == null ? "" : q.trim().toLowerCase();
        String digits = needle.matches("[\\d\\s()+.-]+") ? needle.replaceAll("\\D", "") : "";
        String like = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String base = "SELECT y.*, COALESCE(st.visits, 0) AS visits, st.last_visit, st.next_visit, COALESCE(st.consulted, FALSE) AS consulted FROM ("
                + LEADS_SELECT + " WHERE f.status = 'OUTDATED') y LEFT JOIN LATERAL ("
                + "SELECT count(*) FILTER (WHERE b.status = 'ACCEPTED' AND b.start_at <= now() AND NOT " + cons + ")::int AS visits, "
                + "max(b.start_at) FILTER (WHERE b.status = 'ACCEPTED' AND b.start_at <= now() AND NOT " + cons + ") AS last_visit, "
                + "min(b.start_at) FILTER (WHERE b.status = 'ACCEPTED' AND b.start_at > now() AND NOT " + cons + ") AS next_visit, "
                + "bool_or(b.status = 'ACCEPTED' AND " + cons + ") AS consulted "
                + "FROM square_booking b WHERE b.business_id = ? AND y.square_customer_id IS NOT NULL AND b.square_customer_id = y.square_customer_id) st ON TRUE "
                + "WHERE (?::text = '' OR lower(COALESCE(y.name, '')) LIKE ? ESCAPE '\\' OR lower(COALESCE(y.email, '')) LIKE ? ESCAPE '\\' "
                + "OR (?::text <> '' AND regexp_replace(COALESCE(y.phone_number, ''), '\\D', '', 'g') LIKE '%' || ?::text || '%'))";
        Object[] args = {b, b, needle, like, like, digits, digits};
        var counts = jdbc.queryForMap("SELECT count(*)::int AS total, count(*) FILTER (WHERE visits > 0)::int AS clients FROM (" + base + ") z", args);
        int total = ((Number) counts.get("total")).intValue();
        int clients = ((Number) counts.get("clients")).intValue();
        String filter = "clients".equals(seg) ? " WHERE visits > 0" : "never".equals(seg) ? " WHERE visits = 0" : "";
        int shown = "clients".equals(seg) ? clients : "never".equals(seg) ? total - clients : total;
        int pages = Math.max(1, (shown + OUTDATED_PAGE - 1) / OUTDATED_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        List<OutdatedRow> items = jdbc.query("SELECT * FROM (" + base + ") z" + filter
                        + " ORDER BY created_at DESC, id DESC LIMIT " + OUTDATED_PAGE + " OFFSET " + (p * OUTDATED_PAGE),
                (rs, i) -> new OutdatedRow(rs.getLong("id"), rs.getString("name"), rs.getString("phone_number"), rs.getString("email"),
                        rs.getString("source"), iso(rs.getTimestamp("created_at")), rs.getInt("visits"),
                        iso(rs.getTimestamp("last_visit")), iso(rs.getTimestamp("next_visit")), rs.getBoolean("consulted")), args);
        return new OutdatedPage(total, clients, total - clients, p, pages, items);
    }

    public LeadDetail detail(long id) {
        LeadView lead = find(id);
        String key = leadKey(id);
        List<Touch> touches = jdbc.query("SELECT id, channel, source, detail, created_at, payload FROM (SELECT l.*, " + KEY
                        + " AS k FROM lead_intake l WHERE business_id = ? AND outcome <> 'DUPLICATE') t WHERE k = ? ORDER BY created_at DESC",
                (rs, i) -> new Touch(rs.getLong("id"), rs.getString("channel"), rs.getString("source"),
                        rs.getString("detail"), iso(rs.getTimestamp("created_at")), parsePayload(rs.getString("payload"))),
                business.id(), key);
        List<Event> events = jdbc.query("SELECT id, type, text, created_by, created_at FROM lead_events "
                        + "WHERE lead_id = ? AND business_id = ? ORDER BY created_at DESC",
                (rs, i) -> new Event(rs.getLong("id"), rs.getString("type"), rs.getString("text"),
                        rs.getString("created_by"), iso(rs.getTimestamp("created_at"))), id, business.id());
        return new LeadDetail(lead, touches, events, origins(lead), appointments(lead), spend(lead), emails(lead),
                sms(lead), squareNote(lead), payments(lead), forms(lead, touches), place(lead), badges(lead));
    }

    /** Geo of the most recent website visit that has an IP; scope tells a manager whether the person is local. */
    private Place place(LeadView lead) {
        try {
            String phone = lead.phoneNumber();
            String email = lead.email() == null ? null : lead.email().toLowerCase();
            if (phone == null && email == null) return null;
            List<String> ips = jdbc.queryForList("SELECT ip_address FROM marketing.submissions "
                    + "WHERE business_id = ? AND ip_address IS NOT NULL AND (customer_phone = ? OR (?::text IS NOT NULL AND lower(customer_email) = ?)) "
                    + "ORDER BY occurred_at DESC LIMIT 3", String.class, business.id(), phone, email, email);
            for (String ip : ips) {
                GeoIpService.Place p = geo.locate(ip);
                if (p == null) continue;
                String scope = !"US".equals(p.countryCode()) ? "INTERNATIONAL" : "CA".equals(p.region()) ? "LOCAL" : "OUT_OF_STATE";
                return new Place(p.countryCode(), p.country(), p.city(), p.region(), p.flag(), scope);
            }
        } catch (RuntimeException e) {
            log.warn("Lead place lookup failed: {}", e.toString());
        }
        return null;
    }

    private Badges badges(LeadView lead) {
        String phone = lead.phoneNumber();
        if (phone == null) return null;
        try {
            Long biz = business.id();
            List<String> one = List.of(phone);
            MarketingContactsService.ContactNameInfo info = contactsService.resolveDisplayNames(one).get(phone);
            String blockedSource = null;
            boolean blocked = false;
            for (com.salonreview.domain.BlockedNumber b : blockedNumbers.findByPhoneNumberIn(one)) {
                blocked = true;
                blockedSource = b.getSource();
            }
            boolean negative = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM sms_message WHERE business_id = ? AND phone_number = ? AND negative_feedback_at IS NOT NULL)",
                    Boolean.class, biz, phone));
            return new Badges(info != null && info.vip(), info == null ? null : info.visitCount(),
                    info != null && info.smsConsent(), negative, blocked,
                    com.salonreview.domain.BlockedNumber.SOURCE_STOP_REQUEST.equals(blockedSource),
                    !smsLog.phoneNumbersFlaggedAsSpam(biz, one).isEmpty(),
                    !smsLog.phoneNumbersWithClickedLinkTarget(biz, one, com.salonreview.sms.CheckoutReviewLinks.GOOGLE_REVIEW_TARGET).isEmpty(),
                    !smsLog.phoneNumbersWithClickedLinkTarget(biz, one, com.salonreview.sms.CheckoutReviewLinks.YELP_REVIEW_TARGET).isEmpty(),
                    !smsLog.phoneNumbersWithClickedLinkTarget(biz, one, com.salonreview.sms.CheckoutReviewLinks.FEEDBACK_FORM_TARGET).isEmpty());
        } catch (RuntimeException e) {
            log.warn("Lead badges lookup failed: {}", e.toString());
            return null;
        }
    }

    private static final int PLACE_LOOKUPS = 40;

    /** Icons for the whole lead list in a handful of queries: only people who have at least one signal are returned. */
    public java.util.Map<Long, ListBadge> listBadges(List<LeadView> leads) {
        java.util.Map<Long, ListBadge> out = new java.util.HashMap<>();
        List<String> phones = leads.stream().map(LeadView::phoneNumber).filter(java.util.Objects::nonNull).distinct().toList();
        if (phones.isEmpty()) return out;
        try {
            Long biz = business.id();
            var names = contactsService.resolveDisplayNames(phones);
            java.util.Set<String> blockedAll = new java.util.HashSet<>();
            java.util.Set<String> optedOut = new java.util.HashSet<>();
            for (com.salonreview.domain.BlockedNumber b : blockedNumbers.findByPhoneNumberIn(phones)) {
                blockedAll.add(b.getPhoneNumber());
                if (com.salonreview.domain.BlockedNumber.SOURCE_STOP_REQUEST.equals(b.getSource())) optedOut.add(b.getPhoneNumber());
            }
            java.util.Set<String> negative = new java.util.HashSet<>();
            for (int i = 0; i < phones.size(); i += 500) {
                List<String> chunk = phones.subList(i, Math.min(phones.size(), i + 500));
                negative.addAll(jdbc.queryForList("SELECT DISTINCT phone_number FROM sms_message WHERE business_id = ? AND negative_feedback_at IS NOT NULL AND phone_number IN ("
                        + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")", String.class,
                        java.util.stream.Stream.concat(java.util.stream.Stream.of((Object) biz), chunk.stream()).toArray()));
            }
            var spam = smsLog.phoneNumbersFlaggedAsSpam(biz, phones);
            var google = smsLog.phoneNumbersWithClickedLinkTarget(biz, phones, com.salonreview.sms.CheckoutReviewLinks.GOOGLE_REVIEW_TARGET);
            var yelp = smsLog.phoneNumbersWithClickedLinkTarget(biz, phones, com.salonreview.sms.CheckoutReviewLinks.YELP_REVIEW_TARGET);
            var form = smsLog.phoneNumbersWithClickedLinkTarget(biz, phones, com.salonreview.sms.CheckoutReviewLinks.FEEDBACK_FORM_TARGET);
            java.util.Map<String, GeoIpService.Place> places = listPlaces(leads, biz);
            for (LeadView l : leads) {
                String ph = l.phoneNumber();
                if (ph == null) continue;
                var info = names.get(ph);
                GeoIpService.Place pl = places.get(ph);
                String scope = pl == null ? null : !"US".equals(pl.countryCode()) ? "INTERNATIONAL" : "CA".equals(pl.region()) ? "LOCAL" : "OUT_OF_STATE";
                ListBadge b = new ListBadge(info != null && info.vip(), info == null ? null : info.visitCount(), info != null && info.smsConsent(),
                        negative.contains(ph), blockedAll.contains(ph), optedOut.contains(ph), spam.contains(ph),
                        google.contains(ph), yelp.contains(ph), form.contains(ph),
                        pl == null ? null : pl.flag(), pl == null ? null : (pl.city() != null ? pl.city() : pl.country()), scope);
                if (b.vip() || b.smsConsent() || b.negativeFeedback() || b.blocked() || b.spamFlag() || b.clickedGoogle() || b.clickedYelp()
                        || b.clickedFeedback() || b.flag() != null) {
                    out.put(l.id(), b);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Lead list badges lookup failed: {}", e.toString());
        }
        return out;
    }

    /** Location of the latest website visit, only for people still being worked (NEW/CONTACTED) and capped so a cold cache stays fast. */
    private java.util.Map<String, GeoIpService.Place> listPlaces(List<LeadView> leads, Long biz) {
        List<String> active = leads.stream().filter(l -> l.phoneNumber() != null && ("NEW".equals(l.status()) || "CONTACTED".equals(l.status())))
                .map(LeadView::phoneNumber).distinct().limit(PLACE_LOOKUPS).toList();
        if (active.isEmpty()) return java.util.Map.of();
        try {
            java.util.Map<String, String> ipByPhone = new java.util.HashMap<>();
            jdbc.query("SELECT DISTINCT ON (customer_phone) customer_phone, ip_address FROM marketing.submissions WHERE business_id = ? AND ip_address IS NOT NULL AND customer_phone IN ("
                            + String.join(",", java.util.Collections.nCopies(active.size(), "?")) + ") ORDER BY customer_phone, occurred_at DESC",
                    rs -> { ipByPhone.put(rs.getString(1), rs.getString(2)); },
                    java.util.stream.Stream.concat(java.util.stream.Stream.of((Object) biz), active.stream()).toArray());
            java.util.Map<String, GeoIpService.Place> out = new java.util.concurrent.ConcurrentHashMap<>();
            ipByPhone.entrySet().parallelStream().forEach(e -> {
                GeoIpService.Place p = geo.locate(e.getValue());
                if (p != null) out.put(e.getKey(), p);
            });
            return out;
        } catch (RuntimeException e) {
            log.warn("Lead list places lookup failed: {}", e.toString());
            return java.util.Map.of();
        }
    }

    private java.util.Map<String, String> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** Website visits behind this person: every form step and booking with its traffic and device data. */
    private List<Origin> origins(LeadView lead) {
        String phone = lead.phoneNumber();
        String email = lead.email() == null ? null : lead.email().toLowerCase();
        if (phone == null && email == null) return List.of();
        java.util.Map<String, String> places = new java.util.HashMap<>();
        return jdbc.query("""
                SELECT submission_type, occurred_at, landing_page_slug, landing_path, variant_name, traffic_source,
                       utm_source, utm_medium, utm_campaign, utm_term, utm_content, gclid, fbclid, referrer,
                       device_type, os_name, os_version, browser_name, browser_version, ip_address, service_name
                FROM marketing.submissions
                WHERE business_id = ? AND (customer_phone = ? OR (?::text IS NOT NULL AND lower(customer_email) = ?))
                ORDER BY occurred_at DESC LIMIT 12
                """, (rs, i) -> {
            String ip = rs.getString("ip_address");
            String where = ip == null ? null : places.computeIfAbsent(ip, k -> {
                String d = places.size() < 6 ? geo.describe(k, null) : null;
                return d == null ? "" : d;
            });
            String slug = rs.getString("landing_page_slug");
            return new Origin(rs.getString("submission_type"), iso(rs.getTimestamp("occurred_at")),
                    slug != null ? slug : rs.getString("landing_path"), rs.getString("variant_name"),
                    rs.getString("traffic_source"), rs.getString("utm_source"), rs.getString("utm_medium"),
                    rs.getString("utm_campaign"), rs.getString("utm_term"), rs.getString("utm_content"),
                    rs.getString("gclid") != null, rs.getString("fbclid") != null, rs.getString("referrer"),
                    rs.getString("device_type"), join(rs.getString("os_name"), rs.getString("os_version")),
                    join(rs.getString("browser_name"), rs.getString("browser_version")), ip,
                    where == null || where.isEmpty() ? null : where, rs.getString("service_name"));
        }, business.id(), phone, email, email);
    }

    /** The lead's Square customer plus duplicate profiles of the same person (same phone or email in this business). */
    private List<String> customerIds(LeadView lead) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (lead.squareCustomerId() == null) return List.of();
        ids.add(lead.squareCustomerId());
        String digits = lead.phoneNumber() == null ? "" : lead.phoneNumber().replaceAll("\\D", "");
        String phone = digits.length() < 10 ? null : digits.substring(digits.length() - 10);
        String email = lead.email() == null || lead.email().isBlank() ? null : lead.email().trim().toLowerCase();
        if (phone != null || email != null) {
            ids.addAll(jdbc.queryForList("""
                    SELECT square_customer_id FROM square_customer WHERE business_id = ?
                      AND ((?::text IS NOT NULL AND right(regexp_replace(coalesce(phone_number, ''), '\\D', '', 'g'), 10) = ?)
                           OR (?::text IS NOT NULL AND lower(email_address) = ?))
                    """, String.class, business.id(), phone, phone, email, email));
        }
        return List.copyOf(ids);
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private Object[] args(long businessId, List<String> ids, Object... tail) {
        List<Object> a = new java.util.ArrayList<>();
        a.add(businessId);
        a.addAll(ids);
        a.addAll(List.of(tail));
        return a.toArray();
    }

    /** Appointments of the linked Square customer, read from the webhook-fed mirror (no Square call
     * except cached catalog names for the service labels). */
    private List<Appointment> appointments(LeadView lead) {
        List<String> customers = customerIds(lead);
        if (customers.isEmpty()) return List.of();
        List<Object[]> rows = new java.util.ArrayList<>();
        jdbc.query("""
                SELECT square_booking_id, status, start_at, appointment_segments_json::text AS segments
                FROM square_booking WHERE business_id = ? AND square_customer_id IN (%s)
                ORDER BY start_at DESC NULLS LAST LIMIT 20
                """.formatted(marks(customers.size())), rs -> {
            rows.add(new Object[]{rs.getString(1), rs.getString(2), iso(rs.getTimestamp(3)), rs.getString(4)});
        }, args(business.id(), customers));
        java.util.Set<String> variationIds = new java.util.LinkedHashSet<>();
        List<List<String>> perRow = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            List<String> ids = new java.util.ArrayList<>();
            try {
                if (r[3] != null) json.readTree((String) r[3]).forEach(n -> {
                    String v = n.path("serviceVariationId").asText(null);
                    if (v != null) ids.add(v);
                });
            } catch (Exception ignored) {
                // unreadable segments: show the appointment without service names
            }
            variationIds.addAll(ids);
            perRow.add(ids);
        }
        java.util.Map<String, String> names = java.util.Map.of();
        if (!variationIds.isEmpty()) {
            try {
                names = squares.forBusiness(business.id()).catalogNames(variationIds);
            } catch (Exception e) {
                log.warn("Lead appointment service names unavailable: {}", e.toString());
            }
        }
        java.util.Map<String, String> techs = new java.util.HashMap<>();
        jdbc.query("SELECT m.square_team_member_id, COALESCE(NULLIF(p.display_name, ''), p.name) FROM provider_square_member m "
                + "JOIN providers p ON p.id = m.provider_id WHERE p.business_id = ?", rs -> {
            techs.put(rs.getString(1), rs.getString(2));
        }, business.id());
        java.util.Map<java.time.LocalDate, java.math.BigDecimal> paidByDay = paidByDay(customers);
        java.time.ZoneId la = java.time.ZoneId.of("America/Los_Angeles");
        List<Appointment> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            java.util.Map<String, String> n = names;
            String services = perRow.get(i).stream().map(n::get).filter(java.util.Objects::nonNull).distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
            String tech = null;
            Integer minutes = null;
            try {
                if (r[3] != null) {
                    int total = 0;
                    java.util.LinkedHashSet<String> names2 = new java.util.LinkedHashSet<>();
                    for (var seg : json.readTree((String) r[3])) {
                        total += seg.path("durationMinutes").asInt(0);
                        String tm = techs.get(seg.path("teamMemberId").asText(""));
                        if (tm != null) names2.add(tm);
                    }
                    minutes = total > 0 ? total : null;
                    tech = names2.isEmpty() ? null : String.join(", ", names2);
                }
            } catch (Exception ignored) {
                // unreadable segments: no technician or duration
            }
            java.math.BigDecimal paid = null;
            if (r[2] != null && "ACCEPTED".equals(r[1])) {
                paid = paidByDay.get(java.time.OffsetDateTime.parse((String) r[2]).atZoneSameInstant(la).toLocalDate());
            }
            String kind = null;
            if (!services.isEmpty() && services.toLowerCase().contains("consultation")) {
                kind = services.toLowerCase().contains("online") ? "ONLINE" : "OFFLINE";
            }
            out.add(new Appointment((String) r[0], (String) r[1], (String) r[2], services.isEmpty() ? null : services, tech, minutes, paid, kind));
        }
        return out;
    }

    private List<Payment> payments(LeadView lead) {
        List<String> customers = customerIds(lead);
        if (customers.isEmpty()) return List.of();
        java.time.ZoneId la = java.time.ZoneId.of("America/Los_Angeles");
        java.util.Set<java.time.LocalDate> visitDays = new java.util.HashSet<>();
        jdbc.query("SELECT start_at FROM square_booking WHERE business_id = ? AND status = 'ACCEPTED' AND start_at IS NOT NULL "
                + "AND square_customer_id IN (" + marks(customers.size()) + ")",
                rs -> { visitDays.add(rs.getTimestamp(1).toInstant().atZone(la).toLocalDate()); }, args(business.id(), customers));
        List<Payment> out = new java.util.ArrayList<>();
        jdbc.query("""
                SELECT p.square_payment_id, p.created_at, p.total_money, p.tip_money,
                       (SELECT string_agg(DISTINCT e->>'name', ', ') FROM jsonb_array_elements(COALESCE(o.line_items_json, '[]'::jsonb)) e) AS items
                FROM square_payment p LEFT JOIN square_order o ON o.business_id = p.business_id AND o.square_order_id = p.square_order_id
                WHERE p.business_id = ? AND p.status = 'COMPLETED' AND p.created_at IS NOT NULL
                  AND p.square_customer_id IN (%s) ORDER BY p.created_at DESC LIMIT 50
                """.formatted(marks(customers.size())), rs -> {
            var at = rs.getTimestamp(2).toInstant();
            if (visitDays.contains(at.atZone(la).toLocalDate())) return;
            java.math.BigDecimal tip = rs.getBigDecimal(4) == null ? java.math.BigDecimal.ZERO : rs.getBigDecimal(4);
            out.add(new Payment(rs.getString(1), at.toString(), rs.getBigDecimal(3).subtract(tip), tip, rs.getString(5)));
        }, args(business.id(), customers));
        return out;
    }

    private java.util.Map<java.time.LocalDate, java.math.BigDecimal> paidByDay(List<String> customerIds) {
        java.util.Map<java.time.LocalDate, java.math.BigDecimal> out = new java.util.HashMap<>();
        if (customerIds.isEmpty()) return out;
        jdbc.query("""
                SELECT (created_at AT TIME ZONE 'America/Los_Angeles')::date AS d, sum(total_money) - COALESCE(sum(tip_money), 0) AS amount
                FROM square_payment WHERE business_id = ? AND square_customer_id IN (%s) AND status = 'COMPLETED'
                GROUP BY 1
                """.formatted(marks(customerIds.size())), rs -> {
            out.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2));
        }, args(business.id(), customerIds));
        return out;
    }

    /** Lifetime money and visits of the linked Square customer, from the payment mirror (tips reported apart). */
    private Spend spend(LeadView lead) {
        java.math.BigDecimal zero = java.math.BigDecimal.ZERO;
        List<String> customers = customerIds(lead);
        if (customers.isEmpty()) return new Spend(zero, zero, 0, null, null, null);
        var days = paidByDay(customers);
        java.math.BigDecimal total = days.values().stream().reduce(zero, java.math.BigDecimal::add);
        java.math.BigDecimal tips = jdbc.queryForObject("SELECT COALESCE(sum(tip_money), 0) FROM square_payment WHERE business_id = ? "
                + "AND square_customer_id IN (" + marks(customers.size()) + ") AND status = 'COMPLETED'", java.math.BigDecimal.class,
                args(business.id(), customers));
        var sorted = days.keySet().stream().sorted().toList();
        String next = jdbc.query("SELECT min(start_at) FROM square_booking WHERE business_id = ? AND square_customer_id IN ("
                        + marks(customers.size()) + ") AND status = 'ACCEPTED' AND start_at > now()",
                rs -> rs.next() ? iso(rs.getTimestamp(1)) : null, args(business.id(), customers));
        return new Spend(total, tips, days.size(), sorted.isEmpty() ? null : sorted.get(0).toString(),
                sorted.isEmpty() ? null : sorted.get(sorted.size() - 1).toString(), next);
    }

    private static void put(java.util.Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v.trim());
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    private List<Form> forms(LeadView lead, List<Touch> touches) {
        List<Form> out = new java.util.ArrayList<>();
        String[] mailArr = emailsOf(lead).toArray(new String[0]);
        String tail = phoneTail(lead);
        out.addAll(jdbc.query("""
                SELECT id, kind, created_at, name, phone, email, course_name, message, source_page_url, utm_source, utm_medium,
                       utm_campaign, gclid, sms_consent, email_consent, status
                FROM academy_lead WHERE id IN (""" + ACADEMY_OF_LEAD + ") ORDER BY created_at",
                (rs, i) -> {
                    java.util.Map<String, String> f = new java.util.LinkedHashMap<>();
                    boolean course = "course_request".equals(rs.getString("kind"));
                    put(f, "Form", course ? "Academy course request" : "Academy newsletter signup");
                    put(f, "Name", rs.getString("name"));
                    put(f, "Phone", rs.getString("phone"));
                    put(f, "Email", rs.getString("email"));
                    put(f, "Course", rs.getString("course_name"));
                    put(f, "Message", rs.getString("message"));
                    put(f, "Page", rs.getString("source_page_url"));
                    put(f, "Source", rs.getString("gclid") != null && !rs.getString("gclid").isBlank() ? "Google Ads" : rs.getString("utm_source"));
                    put(f, "UTM medium", rs.getString("utm_medium"));
                    put(f, "UTM campaign", rs.getString("utm_campaign"));
                    put(f, "OK to text", yesNo(rs.getBoolean("sms_consent")));
                    put(f, "OK to email", yesNo(rs.getBoolean("email_consent")));
                    if ("duplicate".equals(rs.getString("status"))) put(f, "Funnel", "not restarted: repeat request within 14 days");
                    return new Form("a" + rs.getLong("id"), "academy", iso(rs.getTimestamp("created_at")), f);
                }, business.id(), tail, tail, mailArr, mailArr));
        if (touches.stream().anyMatch(t -> "Form".equals(t.channel()))) {
            out.addAll(jdbc.query("""
                    SELECT id, created_at, phone_number, given_name, email_address, original_traffic_source, marketing_traffic_source,
                           utm_source, utm_medium, utm_campaign, referrer, landing_page_slug, variant_name, device_type, os_name,
                           browser_name, sms_marketing_consent, email_marketing_consent, booking_service_name
                    FROM marketing.contacts WHERE business_id = ?
                      AND ((?::text IS NOT NULL AND right(regexp_replace(coalesce(phone_number, ''), '\\D', '', 'g'), 10) = ?)
                           OR (cardinality(?::text[]) > 0 AND lower(email_address) = ANY(?::text[])))
                    ORDER BY created_at
                    """, (rs, i) -> {
                        java.util.Map<String, String> f = new java.util.LinkedHashMap<>();
                        put(f, "Form", "Website contact form");
                        put(f, "Name", rs.getString("given_name"));
                        put(f, "Phone", rs.getString("phone_number"));
                        put(f, "Email", rs.getString("email_address"));
                        put(f, "Service", rs.getString("booking_service_name"));
                        put(f, "Landing page", rs.getString("landing_page_slug"));
                        put(f, "Variant", rs.getString("variant_name"));
                        put(f, "Source", rs.getString("original_traffic_source") != null ? rs.getString("original_traffic_source") : rs.getString("marketing_traffic_source"));
                        put(f, "UTM source", rs.getString("utm_source"));
                        put(f, "UTM medium", rs.getString("utm_medium"));
                        put(f, "UTM campaign", rs.getString("utm_campaign"));
                        put(f, "Referrer", rs.getString("referrer"));
                        put(f, "Device", join(join(rs.getString("device_type"), rs.getString("os_name")), rs.getString("browser_name")));
                        if (rs.getObject("sms_marketing_consent") != null) put(f, "OK to text", yesNo(rs.getBoolean("sms_marketing_consent")));
                        if (rs.getObject("email_marketing_consent") != null) put(f, "OK to email", yesNo(rs.getBoolean("email_marketing_consent")));
                        return new Form("c" + rs.getString("id"), "contact", iso(rs.getTimestamp("created_at")), f);
                    }, business.id(), tail, tail, mailArr, mailArr));
        }
        return out;
    }

    private static final java.util.Map<String, String> FUNNEL_EMAIL = java.util.Map.of(
            "confirm_email", "Academy auto funnel: request confirmation",
            "reviews_email", "Academy auto funnel: student reviews (day 1)",
            "checkin", "Academy auto funnel: check-in (day 3)",
            "payment_email", "Academy auto funnel: payment plans (day 7)");

    /** Ids at or below this are rows of mailchimp_sent_email; ids between it and 0 are Academy funnel touches. */
    private static final long LOG_ID_BASE = 1_000_000_000L;

    private static String phoneTail(LeadView lead) {
        String d = lead.phoneNumber() == null ? "" : lead.phoneNumber().replaceAll("\\D", "");
        return d.length() < 10 ? null : d.substring(d.length() - 10);
    }

    /** The lead's own email plus the emails of its Square customer profiles. */
    private List<String> emailsOf(LeadView lead) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (lead.email() != null && !lead.email().isBlank()) out.add(lead.email().trim().toLowerCase());
        List<String> ids = customerIds(lead);
        if (!ids.isEmpty()) {
            out.addAll(jdbc.queryForList("SELECT lower(email_address) FROM square_customer WHERE business_id = ? AND square_customer_id IN ("
                    + marks(ids.size()) + ") AND coalesce(email_address, '') <> ''", String.class, args(business.id(), ids)));
        }
        return List.copyOf(out);
    }

    private static String logKind(String title, String subject) {
        String t = title == null ? "" : title;
        if (t.startsWith("consultation_follow_up info")) return "Consultation follow-up: info email";
        if (t.startsWith("consultation_follow_up offer")) return "Consultation follow-up: last-chance offer";
        if (t.startsWith("lead_follow_up")) return "Website lead nudge: email";
        return subject != null && !subject.isBlank() ? subject : t;
    }

    private static final String ACADEMY_OF_LEAD = """
            SELECT id FROM academy_lead WHERE business_id = ?
              AND ((?::text IS NOT NULL AND right(regexp_replace(coalesce(phone, ''), '\\D', '', 'g'), 10) = ?)
                   OR (cardinality(?::text[]) > 0 AND lower(email) = ANY(?::text[])))
            """;

    private List<EmailSent> emails(LeadView lead) {
        List<String> mails = emailsOf(lead);
        String[] mailArr = mails.toArray(new String[0]);
        String tail = phoneTail(lead);
        List<EmailSent> out = new java.util.ArrayList<>(jdbc.query("""
                SELECT id, automation_key, created_at, opened_at, email_clicked_at, content_html IS NOT NULL AS has_content
                FROM winback_email_send WHERE business_id = ? AND state = 'SENT'
                  AND (square_customer_id = ? OR (?::text IS NOT NULL AND lower(email_address) = lower(?::text)))
                ORDER BY created_at DESC LIMIT 100
                """, (rs, i) -> new EmailSent(rs.getLong("id"), rs.getString("automation_key"), iso(rs.getTimestamp("created_at")),
                iso(rs.getTimestamp("opened_at")), iso(rs.getTimestamp("email_clicked_at")), rs.getBoolean("has_content")),
                business.id(), lead.squareCustomerId() == null ? "" : lead.squareCustomerId(), lead.email(), lead.email()));
        // Academy funnel emails: Mailchimp campaigns tracked per touch, listed with the negative touch id.
        out.addAll(jdbc.query("""
                SELECT t.id, t.step, t.created_at, t.opened_at, t.clicked_at FROM academy_lead_touch t
                WHERE t.channel = 'email' AND t.state = 'sent' AND t.campaign_id IS NOT NULL
                  AND t.lead_id IN (""" + ACADEMY_OF_LEAD + ")",
                (rs, i) -> new EmailSent(-rs.getLong("id"), FUNNEL_EMAIL.getOrDefault(rs.getString("step"), "Academy auto funnel: " + rs.getString("step")),
                iso(rs.getTimestamp("created_at")), iso(rs.getTimestamp("opened_at")), iso(rs.getTimestamp("clicked_at")), true),
                business.id(), tail, tail, mailArr, mailArr));
        // Every other single-recipient Mailchimp email (consultation follow-up, website nudge, ...).
        if (!mails.isEmpty()) {
            out.addAll(jdbc.query("""
                    SELECT e.id, e.campaign_title, e.subject, e.sent_at FROM mailchimp_sent_email e
                    WHERE e.business_id = ? AND lower(e.email) = ANY(?::text[])
                      AND NOT EXISTS (SELECT 1 FROM winback_email_send w WHERE w.mailchimp_campaign_id = e.campaign_id)
                      AND NOT EXISTS (SELECT 1 FROM academy_lead_touch t WHERE t.campaign_id = e.campaign_id)
                    ORDER BY e.sent_at DESC LIMIT 100
                    """, (rs, i) -> new EmailSent(-(LOG_ID_BASE + rs.getLong("id")), logKind(rs.getString("campaign_title"), rs.getString("subject")),
                    iso(rs.getTimestamp("sent_at")), null, null, true), business.id(), (Object) mailArr));
        }
        out.sort(java.util.Comparator.comparing(EmailSent::sentAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return out;
    }

    /** Stored HTML of one email sent to this lead (checked against the lead's customer id or email). */
    public String emailHtml(long leadId, long emailId) {
        LeadView lead = find(leadId);
        if (emailId < 0) return campaignEmailHtml(lead, -emailId);
        List<String> html = jdbc.queryForList("""
                SELECT content_html FROM winback_email_send WHERE id = ? AND business_id = ? AND content_html IS NOT NULL
                  AND (square_customer_id = ? OR (?::text IS NOT NULL AND lower(email_address) = lower(?::text)))
                """, String.class, emailId, business.id(), lead.squareCustomerId() == null ? "" : lead.squareCustomerId(),
                lead.email(), lead.email());
        if (html.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such email");
        return html.get(0);
    }

    private String campaignEmailHtml(LeadView lead, long code) {
        String[] mailArr = emailsOf(lead).toArray(new String[0]);
        String tail = phoneTail(lead);
        List<String> campaigns;
        if (code > LOG_ID_BASE) {
            campaigns = jdbc.queryForList("SELECT campaign_id FROM mailchimp_sent_email WHERE id = ? AND business_id = ? AND lower(email) = ANY(?::text[])",
                    String.class, code - LOG_ID_BASE, business.id(), (Object) mailArr);
        } else {
            campaigns = jdbc.queryForList("SELECT t.campaign_id FROM academy_lead_touch t WHERE t.id = ? AND t.campaign_id IS NOT NULL AND t.lead_id IN ("
                    + ACADEMY_OF_LEAD + ")", String.class, code, business.id(), tail, tail, mailArr, mailArr);
        }
        var config = mailchimpConfigs.findByBusinessId(business.id()).filter(com.salonreview.domain.MailchimpConfig::isConfigured).orElse(null);
        if (campaigns.isEmpty() || config == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such email");
        try {
            String html = mailchimpClient.fetchCampaignHtml(config, campaigns.get(0));
            if (html == null || html.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such email");
            return html;
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mailchimp is unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mailchimp is unavailable");
        }
    }

    private static String join(String a, String b) {
        if (a == null) return null;
        return b == null ? a : a + " " + b;
    }

    /** Lead id for a phone number, used by the Telegram "Open lead" link. */
    public Long lookupByPhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        List<Long> ids = jdbc.queryForList("SELECT id FROM (SELECT l.*, " + KEY
                + " AS k FROM lead_intake l WHERE business_id = ? AND outcome <> 'DUPLICATE') t WHERE k = ? "
                + "ORDER BY created_at, id LIMIT 1", Long.class, business.id(), digits);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void update(long id, UpdateRequest req, String by) {
        if (OUTDATED.equals(req.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outdated is a system archive: leads can leave it, never enter it");
        }
        if (req.status() != null && !STATUSES.contains(req.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status");
        }
        String reason = req.lostReason() == null ? null : req.lostReason().trim().toUpperCase();
        String comment = req.lostComment() == null || req.lostComment().isBlank() ? null : req.lostComment().trim();
        if ("LOST".equals(req.status())) {
            if (reason == null || !LOST_REASONS.contains(reason)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lost reason required");
            }
            if ("OTHER".equals(reason) && comment == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment required for reason OTHER");
            }
        }
        String source = req.source() == null || req.source().isBlank() ? null : req.source().trim();
        if (source != null) {
            if (!SOURCES.contains(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown source");
            if (!find(id).sourceEditable()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "source is known and locked");
        }
        Timestamp callback = null;
        if (req.callbackAt() != null && !req.callbackAt().isBlank()) {
            try {
                callback = Timestamp.from(Instant.parse(req.callbackAt()));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "callbackAt must be an ISO instant");
            }
        }
        boolean clear = Boolean.TRUE.equals(req.clearCallback());
        LeadView current = find(id);
        if ("CONTACTED".equals(req.status()) && callback == null && !clear
                && (current.callbackAt() == null || Instant.parse(current.callbackAt()).isBefore(Instant.now()))) {
            callback = Timestamp.from(Instant.now().plus(java.time.Duration.ofDays(FOLLOW_UP_DAYS)));
        }
        jdbc.update("""
                UPDATE lead_intake SET
                  status = COALESCE(?, status),
                  lost_reason = CASE WHEN ? = 'LOST' THEN ? WHEN ? IS NOT NULL THEN NULL ELSE lost_reason END,
                  lost_comment = CASE WHEN ? = 'LOST' THEN ? WHEN ? IS NOT NULL THEN NULL ELSE lost_comment END,
                  lost_at = CASE WHEN ? = 'LOST' THEN now() WHEN ? IS NOT NULL THEN NULL ELSE lost_at END,
                  callback_at = CASE WHEN ? THEN NULL WHEN ?::timestamptz IS NOT NULL THEN ?::timestamptz ELSE callback_at END,
                  callback_reminded = CASE WHEN ? OR ?::timestamptz IS NOT NULL THEN FALSE ELSE callback_reminded END,
                  updated_by = ?, updated_at = now()
                WHERE id = ? AND business_id = ?
                """, req.status(), req.status(), reason, req.status(), req.status(), comment, req.status(),
                req.status(), req.status(), clear, callback, callback, clear, callback, by, id, business.id());
        if (source != null) {
            jdbc.update("UPDATE lead_intake SET source = ?, source_manual = TRUE WHERE id = ? AND business_id = ?", source, id, business.id());
            addEvent(id, "SOURCE", source, by);
            String customer = find(id).squareCustomerId();
            if (customer != null) intake.tagSource(business.id(), customer, source);
        }
        if (req.status() != null) addEvent(id, "STATUS", req.status(), by);
        if ("LOST".equals(req.status())) addEvent(id, "LOST_REASON", comment == null ? reason : reason + ": " + comment, by);
        if (callback != null) addEvent(id, "CALLBACK", callback.toInstant().toString(), by);
        if (clear) addEvent(id, "CALLBACK_CLEARED", null, by);
    }

    static final Set<String> ATTEMPT_CHANNELS = Set.of("CALL", "SMS", "MESSAGE");

    /** One unanswered attempt: logs it, makes sure the lead is CONTACTED and schedules the next try in 2 days. */
    public void recordAttempt(long id, String channel, String by) {
        String ch = channel == null || channel.isBlank() ? null : channel.trim().toUpperCase();
        if (ch != null && !ATTEMPT_CHANNELS.contains(ch)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown channel");
        LeadView lead = find(id);
        if (!"NEW".equals(lead.status()) && !"CONTACTED".equals(lead.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lead is closed");
        }
        jdbc.update("""
                UPDATE lead_intake SET status = 'CONTACTED',
                  callback_at = now() + make_interval(days => ?), callback_reminded = FALSE, updated_by = ?, updated_at = now()
                WHERE id = ? AND business_id = ?
                """, FOLLOW_UP_DAYS, by, id, business.id());
        if ("NEW".equals(lead.status())) addEvent(id, "STATUS", "CONTACTED", by);
        addEvent(id, "ATTEMPT", ch, by);
    }

    /**
     * Moves leads to BOOKED when their Square customer has an accepted procedure (consultations do not count):
     * made after the lead's last touch, or still upcoming for a lead that is not LOST.
     */
    public int autoBook() {
        int total = 0;
        for (long businessId : intake.enabledBusinesses()) total += autoBook(businessId, null);
        return total;
    }

    /** Immediate variant for one Square customer, called when a booking webhook lands so the lead never sits in NEW. */
    public int autoBookCustomer(long businessId, String squareCustomerId) {
        if (squareCustomerId == null || squareCustomerId.isBlank() || !intake.enabledBusinesses().contains(businessId)) return 0;
        return autoBook(businessId, squareCustomerId);
    }

    private int autoBook(long businessId, String onlyCustomer) {
        var rows = jdbc.queryForList("""
                WITH keyed AS (
                  SELECT l.*, %s AS k FROM lead_intake l WHERE outcome <> 'DUPLICATE' AND business_id = ?
                ), agg AS (
                  SELECT k, (array_agg(square_customer_id ORDER BY (square_customer_id IS NULL), created_at))[1] AS customer,
                         max(created_at) AS last_touch
                  FROM keyed GROUP BY k
                ), f AS (
                  SELECT DISTINCT ON (k) * FROM keyed ORDER BY k, created_at, id
                )
                UPDATE lead_intake li SET status = 'BOOKED', callback_at = NULL, lost_reason = NULL, lost_comment = NULL,
                       lost_at = NULL, updated_by = 'Square', updated_at = now()
                FROM f JOIN agg ON agg.k = f.k
                WHERE li.id = f.id AND f.status IN ('NEW', 'CONTACTED', 'LOST', 'OUTDATED') AND agg.customer IS NOT NULL
                  AND (?::text IS NULL OR agg.customer = ?)
                  AND EXISTS (SELECT 1 FROM square_booking b WHERE b.business_id = f.business_id
                              AND b.square_customer_id = agg.customer AND b.status = 'ACCEPTED' AND NOT %s
                              AND (CASE WHEN f.status = 'OUTDATED' THEN b.created_at >= now() - interval '3 days'
                                        WHEN f.legacy AND f.status = 'NEW' AND agg.last_touch = f.created_at THEN TRUE
                                        ELSE b.created_at >= agg.last_touch - interval '2 hours'
                                             OR (f.status <> 'LOST' AND b.start_at > now()) END))
                RETURNING li.id, li.business_id
                """.formatted(KEY, ConsultationCatalog.isConsultationSql("b", consultations.ids(businessId))), businessId, onlyCustomer, onlyCustomer);
        for (var r : rows) {
            jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by) VALUES (?, ?, 'STATUS', 'BOOKED', 'Square')",
                    ((Number) r.get("id")).longValue(), ((Number) r.get("business_id")).longValue());
        }
        return rows.size();
    }

    private List<Sms> sms(LeadView lead) {
        String digits = lead.phoneNumber() == null ? "" : lead.phoneNumber().replaceAll("\\D", "");
        if (digits.length() < 10) return List.of();
        return jdbc.query("""
                SELECT id, direction, body, created_at, COALESCE(delivery_status, status) AS st FROM sms_message
                WHERE business_id = ? AND right(regexp_replace(phone_number, '\\D', '', 'g'), 10) = ?
                ORDER BY created_at DESC LIMIT 100
                """, (rs, i) -> new Sms(rs.getLong("id"), rs.getString("direction"), rs.getString("body"),
                iso(rs.getTimestamp("created_at")), rs.getString("st")), business.id(), digits.substring(digits.length() - 10));
    }

    private String squareNote(LeadView lead) {
        if (lead.squareCustomerId() == null) return null;
        try {
            return squares.forBusiness(business.id()).customerNote(lead.squareCustomerId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public LostReport lostReport(int days) {
        int d = Math.max(1, Math.min(days, 730));
        List<LostRow> byReason = jdbc.query("""
                SELECT lost_reason, count(*) FROM lead_intake
                WHERE business_id = ? AND status = 'LOST' AND lost_reason IS NOT NULL AND lost_at >= now() - make_interval(days => ?)
                GROUP BY lost_reason ORDER BY count(*) DESC
                """, (rs, i) -> new LostRow(rs.getString(1), null, rs.getInt(2)), business.id(), d);
        List<LostRow> bySource = jdbc.query("""
                SELECT lost_reason, source, count(*) FROM lead_intake
                WHERE business_id = ? AND status = 'LOST' AND lost_reason IS NOT NULL AND lost_at >= now() - make_interval(days => ?)
                GROUP BY lost_reason, source ORDER BY count(*) DESC, source
                """, (rs, i) -> new LostRow(rs.getString(1), rs.getString(2), rs.getInt(3)), business.id(), d);
        return new LostReport(d, byReason.stream().mapToInt(LostRow::count).sum(), byReason, bySource);
    }

    public void addManualEvent(long id, EventRequest req, String by) {
        if (req.type() == null || !MANUAL_EVENT_TYPES.contains(req.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown event type");
        }
        if (req.text() == null || req.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text required");
        }
        find(id);
        addEvent(id, req.type(), req.text().trim(), by);
    }

    void addEvent(long leadId, String type, String text, String by) {
        jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by) VALUES (?, ?, ?, ?, ?)",
                leadId, business.id(), type, text, by);
    }

    public boolean isAcademy(long id) {
        return find(id).academy();
    }

    /** True when the business has at least one academy lead, i.e. the Academy sub-section exists. */
    public boolean hasAcademyLeads(long businessId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM lead_intake WHERE business_id = ? AND channel = 'Academy')", Boolean.class, businessId));
    }

    LeadView find(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM (" + leadsSql(business.id(), true) + ") y WHERE id = ?", leadMapper, business.id(), business.id(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such lead");
        }
    }

    private String leadKey(long id) {
        return jdbc.queryForObject("SELECT " + KEY + " FROM lead_intake WHERE id = ?", String.class, id);
    }

    private static final int[] CONSULT_STEPS_DAYS = {1, 3, 7};
    private static final int UNANSWERED_HOURS = 2;

    /**
     * Schedules the next touch for leads nobody scheduled: one day after a consultation without a procedure
     * (then 3 and 7 days), and two hours after a client text that stayed unanswered.
     */
    public void scheduleFollowUps() {
        for (long businessId : intake.enabledBusinesses()) {
            try {
                scheduleFollowUps(businessId);
            } catch (RuntimeException e) {
                log.warn("Follow-up scheduling failed for business {}: {}", businessId, e.toString());
            }
        }
    }

    private static final java.time.ZoneId PT = java.time.ZoneId.of("America/Los_Angeles");
    private static final int WORK_START_HOUR = 9;
    private static final int WORK_END_HOUR = 20;
    private static final int CONSULT_MAX_AGE_DAYS = 30;

    /** Pushes an automatic reminder into working hours (09:00 to 20:00 Pacific) so nobody is pinged at night. */
    static Instant workingTime(Instant t) {
        java.time.ZonedDateTime z = t.atZone(PT);
        if (z.getHour() >= WORK_END_HOUR) {
            z = z.plusDays(1).withHour(WORK_START_HOUR).withMinute(0).withSecond(0).withNano(0);
        } else if (z.getHour() < WORK_START_HOUR) {
            z = z.withHour(WORK_START_HOUR).withMinute(0).withSecond(0).withNano(0);
        }
        return z.toInstant();
    }

    /**
     * Next automatic callback for an open lead, or null to leave it alone. Never overrides a callback the team set
     * after the client's last text, never resurrects a callback the team cleared, and ignores old consultations.
     */
    static Instant followUpTarget(LeadView l, Instant inbound, Instant clearedAt, Instant now) {
        if (!"NEW".equals(l.status()) && !"CONTACTED".equals(l.status())) return null;
        Instant current = l.callbackAt() == null ? null : Instant.parse(l.callbackAt());
        Instant activity = Instant.parse(l.activityAt() != null ? l.activityAt() : l.createdAt());
        if (inbound != null && !inbound.isBefore(activity) && (clearedAt == null || clearedAt.isBefore(inbound))) {
            Instant t = workingTime(inbound.plus(java.time.Duration.ofHours(UNANSWERED_HOURS)));
            if (current == null || current.isAfter(t)) return t;
        }
        if (current == null && ("DONE".equals(l.consultState()) || "NO_SHOW".equals(l.consultState())) && l.consultAt() != null) {
            Instant consult = Instant.parse(l.consultAt());
            if (consult.isBefore(now.minus(java.time.Duration.ofDays(CONSULT_MAX_AGE_DAYS)))) return null;
            if (clearedAt != null && clearedAt.isAfter(consult)) return null;
            int step = CONSULT_STEPS_DAYS[Math.min(l.attempts(), CONSULT_STEPS_DAYS.length - 1)];
            return workingTime(consult.plus(java.time.Duration.ofDays(step)));
        }
        return null;
    }

    private void scheduleFollowUps(long businessId) {
        java.util.Map<String, Instant> unanswered = new java.util.HashMap<>();
        jdbc.query("""
                SELECT p, created_at FROM (
                  SELECT DISTINCT ON (p) m.*, right(regexp_replace(m.phone_number, '\\D', '', 'g'), 10) AS p
                  FROM sms_message m WHERE m.business_id = ? AND m.created_at > now() - interval '14 days'
                  ORDER BY p, m.created_at DESC, m.id DESC) x
                WHERE direction = 'INBOUND' AND reply_flow_id IS NULL AND rating IS NULL
                  AND body !~* '^\\s*(stop|stopall|unsubscribe|cancel|end|quit|help)\\s*$'
                  AND created_at < now() - make_interval(hours => ?)
                """, rs -> {
            unanswered.put(rs.getString("p"), rs.getTimestamp("created_at").toInstant());
        }, businessId, UNANSWERED_HOURS);
        java.util.Map<Long, Instant> cleared = new java.util.HashMap<>();
        jdbc.query("SELECT lead_id, max(created_at) AS at FROM lead_events WHERE business_id = ? AND type = 'CALLBACK_CLEARED' GROUP BY lead_id",
                rs -> {
                    cleared.put(rs.getLong("lead_id"), rs.getTimestamp("at").toInstant());
                }, businessId);
        Instant now = Instant.now();
        for (LeadView l : listFor(businessId, null)) {
            String digits = l.phoneNumber() == null ? "" : l.phoneNumber().replaceAll("\\D", "");
            Instant inbound = digits.length() >= 10 ? unanswered.get(digits.substring(digits.length() - 10)) : null;
            Instant target = followUpTarget(l, inbound, cleared.get(l.id()), now);
            if (target == null) continue;
            boolean stale = target.isBefore(now.minus(java.time.Duration.ofDays(1)));
            jdbc.update("UPDATE lead_intake SET callback_at = ?, callback_reminded = ?, updated_by = 'System' WHERE id = ? AND business_id = ?",
                    Timestamp.from(target), stale, l.id(), businessId);
            jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by) VALUES (?, ?, 'CALLBACK', ?, 'System')",
                    l.id(), businessId, target.toString());
        }
    }

    /** Pings the business Telegram chat about callbacks that came due; each reminder fires once. */
    public void sendDueReminders() {
        var due = jdbc.queryForList("""
                SELECT id, business_id, name, phone_number, email, source
                FROM lead_intake
                WHERE callback_at <= now() AND NOT callback_reminded AND status IN ('NEW', 'CONTACTED')
                ORDER BY callback_at LIMIT 50
                """);
        for (var row : due) {
            long id = ((Number) row.get("id")).longValue();
            long businessId = ((Number) row.get("business_id")).longValue();
            String who = row.get("name") != null ? row.get("name").toString()
                    : row.get("phone_number") != null ? row.get("phone_number").toString()
                    : String.valueOf(row.get("email"));
            StringBuilder text = new StringBuilder("Callback reminder: ").append(who);
            if (row.get("phone_number") != null && row.get("name") != null) text.append(" ").append(row.get("phone_number"));
            text.append("\nSource: ").append(row.get("source"));
            text.append("\nOpen lead: ").append(publicBaseUrl).append("/admin/leads/").append(id)
                    .append("?business=").append(businessId);
            if (telegram.sendPlainText(businessId, text.toString())) {
                jdbc.update("UPDATE lead_intake SET callback_reminded = TRUE WHERE id = ?", id);
            } else {
                log.info("Lead {} callback reminder not delivered, will retry", id);
            }
        }
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    /** Fetches a call recording server-side: Ringostat redirects browsers sending a Referer to its login page. */
    public byte[] recordingBytes(long touchId) {
        String payload = jdbc.query("SELECT payload FROM lead_intake WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, touchId);
        java.util.Map<String, String> map = payload == null ? null : parsePayload(payload);
        if (map == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        for (var e : map.entrySet()) {
            String v = e.getValue();
            if (e.getKey().matches("(?i).*(record|audio|mp3).*") && v != null && v.startsWith("https://app.ringostat.com/recordings/")) {
                try {
                    var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(v)).timeout(java.time.Duration.ofSeconds(30)).GET().build();
                    var res = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    if (res.statusCode() == 200) return res.body();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException | RuntimeException ex) {
                    break;
                }
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY);
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
