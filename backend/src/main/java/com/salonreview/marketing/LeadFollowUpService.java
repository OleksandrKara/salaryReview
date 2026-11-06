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
    static final Set<String> STATUSES = Set.of("NEW", "CONTACTED", "BOOKED", "LOST");
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
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public LeadFollowUpService(JdbcTemplate jdbc, CurrentBusinessContext business, TelegramNotificationService telegram, GeoIpService geo,
                               SquareClientProvider squares, LeadIntakeService intake, ConsultationCatalog consultations,
                               @org.springframework.beans.factory.annotation.Value("${app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.business = business;
        this.telegram = telegram;
        this.publicBaseUrl = publicBaseUrl;
        this.geo = geo;
        this.squares = squares;
        this.intake = intake;
        this.consultations = consultations;
    }

    public record LeadView(long id, String channel, String source, String phoneNumber, String email, String name,
                           String detail, String status, String callbackAt, String createdAt, String lastTouchAt,
                           int touches, String squareCustomerId, String squareUrl,
                           String lostReason, String lostComment, boolean sourceEditable,
                           String lastNote, String lastNoteBy, String lastNoteAt, String activityAt,
                           boolean academy, String consultState, String consultAt) {}

    public record Touch(long id, String channel, String source, String detail, String createdAt,
                        java.util.Map<String, String> payload) {}

    public record Origin(String type, String at, String landingPage, String variant, String trafficSource,
                         String utmSource, String utmMedium, String utmCampaign, String utmTerm, String utmContent,
                         boolean googleClick, boolean facebookClick, String referrer, String device, String os,
                         String browser, String ip, String location, String service) {}

    public record Event(long id, String type, String text, String createdBy, String createdAt) {}

    public record Appointment(String id, String status, String startAt, String services, String technician,
                              Integer minutes, java.math.BigDecimal paid) {}

    public record Spend(java.math.BigDecimal total, java.math.BigDecimal tips, int visits, String firstVisit,
                        String lastVisit, String nextAppointment) {}

    public record Sms(long id, String direction, String body, String at, String status) {}

    public record EmailSent(long id, String kind, String sentAt, String openedAt, String clickedAt, boolean hasContent) {}

    public record LeadDetail(LeadView lead, List<Touch> touches, List<Event> events, List<Origin> origins,
                             List<Appointment> appointments, Spend spend, List<EmailSent> emails,
                             List<Sms> sms, String squareNote) {}

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
                rs.getBoolean("academy"), rs.getString("consult_state"), iso(rs.getTimestamp("consult_at")));
    };

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
                   agg.customer AS square_customer_id, f.lost_reason, f.lost_comment,
                   (f.channel = 'Square' OR f.source = 'Unknown') AS source_editable,
                   n.text AS last_note, n.created_by AS last_note_by, n.created_at AS last_note_at,
                   GREATEST(agg.last_touch_at, f.updated_at, n.created_at) AS activity_at
            FROM (SELECT DISTINCT ON (k) * FROM keyed ORDER BY k, created_at, id) f JOIN agg ON agg.k = f.k
              LEFT JOIN square_customer sc ON sc.business_id = f.business_id AND sc.square_customer_id = agg.customer
              LEFT JOIN LATERAL (SELECT e.text, e.created_by, e.created_at FROM lead_events e WHERE e.lead_id = f.id
                                 AND e.type IN ('NOTE', 'CALL', 'AGREEMENT') ORDER BY e.created_at DESC LIMIT 1) n ON TRUE
            """.formatted(KEY);

    /** Leads plus the person's latest consultation: UPCOMING, or DONE when no real procedure was booked after it. */
    private String leadsSql() {
        return "SELECT x.*, c.consult_at, c.consult_state FROM (" + LEADS_SELECT + ") x LEFT JOIN LATERAL ("
                + "SELECT b.start_at AS consult_at, CASE WHEN b.start_at > now() THEN 'UPCOMING' ELSE 'DONE' END AS consult_state "
                + "FROM square_booking b WHERE b.business_id = ? AND b.square_customer_id = x.square_customer_id AND b.status = 'ACCEPTED' "
                + "AND " + ConsultationCatalog.isConsultationSql("b", consultations.ids(business.id()))
                + " AND NOT EXISTS (SELECT 1 FROM square_booking p WHERE p.business_id = b.business_id AND p.square_customer_id = b.square_customer_id "
                + "AND p.status = 'ACCEPTED' AND p.start_at >= b.start_at AND NOT "
                + ConsultationCatalog.isConsultationSql("p", consultations.ids(business.id())) + ") "
                + "ORDER BY b.start_at DESC LIMIT 1) c ON TRUE";
    }

    public List<LeadView> list(String status) {
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("SELECT * FROM (" + leadsSql() + ") y WHERE (?::text IS NULL OR status = ?) "
                + "ORDER BY (status = 'NEW') DESC, activity_at DESC LIMIT 1000", leadMapper, business.id(), business.id(), filter, filter);
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
                sms(lead), squareNote(lead));
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

    /** Appointments of the linked Square customer, read from the webhook-fed mirror (no Square call
     * except cached catalog names for the service labels). */
    private List<Appointment> appointments(LeadView lead) {
        if (lead.squareCustomerId() == null) return List.of();
        List<Object[]> rows = new java.util.ArrayList<>();
        jdbc.query("""
                SELECT square_booking_id, status, start_at, appointment_segments_json::text AS segments
                FROM square_booking WHERE business_id = ? AND square_customer_id = ?
                ORDER BY start_at DESC NULLS LAST LIMIT 20
                """, rs -> {
            rows.add(new Object[]{rs.getString(1), rs.getString(2), iso(rs.getTimestamp(3)), rs.getString(4)});
        }, business.id(), lead.squareCustomerId());
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
        java.util.Map<java.time.LocalDate, java.math.BigDecimal> paidByDay = paidByDay(lead.squareCustomerId());
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
            out.add(new Appointment((String) r[0], (String) r[1], (String) r[2], services.isEmpty() ? null : services, tech, minutes, paid));
        }
        return out;
    }

    private java.util.Map<java.time.LocalDate, java.math.BigDecimal> paidByDay(String customerId) {
        java.util.Map<java.time.LocalDate, java.math.BigDecimal> out = new java.util.HashMap<>();
        if (customerId == null) return out;
        jdbc.query("""
                SELECT (created_at AT TIME ZONE 'America/Los_Angeles')::date AS d, sum(total_money) - COALESCE(sum(tip_money), 0) AS amount
                FROM square_payment WHERE business_id = ? AND square_customer_id = ? AND status = 'COMPLETED'
                GROUP BY 1
                """, rs -> {
            out.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2));
        }, business.id(), customerId);
        return out;
    }

    /** Lifetime money and visits of the linked Square customer, from the payment mirror (tips reported apart). */
    private Spend spend(LeadView lead) {
        java.math.BigDecimal zero = java.math.BigDecimal.ZERO;
        if (lead.squareCustomerId() == null) return new Spend(zero, zero, 0, null, null, null);
        var days = paidByDay(lead.squareCustomerId());
        java.math.BigDecimal total = days.values().stream().reduce(zero, java.math.BigDecimal::add);
        java.math.BigDecimal tips = jdbc.queryForObject("SELECT COALESCE(sum(tip_money), 0) FROM square_payment WHERE business_id = ? "
                + "AND square_customer_id = ? AND status = 'COMPLETED'", java.math.BigDecimal.class, business.id(), lead.squareCustomerId());
        var sorted = days.keySet().stream().sorted().toList();
        String next = jdbc.query("SELECT min(start_at) FROM square_booking WHERE business_id = ? AND square_customer_id = ? "
                        + "AND status = 'ACCEPTED' AND start_at > now()",
                rs -> rs.next() ? iso(rs.getTimestamp(1)) : null, business.id(), lead.squareCustomerId());
        return new Spend(total, tips, days.size(), sorted.isEmpty() ? null : sorted.get(0).toString(),
                sorted.isEmpty() ? null : sorted.get(sorted.size() - 1).toString(), next);
    }

    private List<EmailSent> emails(LeadView lead) {
        return jdbc.query("""
                SELECT id, automation_key, created_at, opened_at, email_clicked_at, content_html IS NOT NULL AS has_content
                FROM winback_email_send WHERE business_id = ? AND state = 'SENT'
                  AND (square_customer_id = ? OR (?::text IS NOT NULL AND lower(email_address) = lower(?::text)))
                ORDER BY created_at DESC LIMIT 100
                """, (rs, i) -> new EmailSent(rs.getLong("id"), rs.getString("automation_key"), iso(rs.getTimestamp("created_at")),
                iso(rs.getTimestamp("opened_at")), iso(rs.getTimestamp("email_clicked_at")), rs.getBoolean("has_content")),
                business.id(), lead.squareCustomerId() == null ? "" : lead.squareCustomerId(), lead.email(), lead.email());
    }

    /** Stored HTML of one email sent to this lead (checked against the lead's customer id or email). */
    public String emailHtml(long leadId, long emailId) {
        LeadView lead = find(leadId);
        List<String> html = jdbc.queryForList("""
                SELECT content_html FROM winback_email_send WHERE id = ? AND business_id = ? AND content_html IS NOT NULL
                  AND (square_customer_id = ? OR (?::text IS NOT NULL AND lower(email_address) = lower(?::text)))
                """, String.class, emailId, business.id(), lead.squareCustomerId() == null ? "" : lead.squareCustomerId(),
                lead.email(), lead.email());
        if (html.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such email");
        return html.get(0);
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
        find(id);
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

    /**
     * Moves leads to BOOKED when their Square customer has an accepted procedure (consultations do not count):
     * made after the lead's last touch, or still upcoming for a lead that is not LOST.
     */
    public int autoBook() {
        int total = 0;
        for (long businessId : intake.enabledBusinesses()) {
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
                    WHERE li.id = f.id AND f.status IN ('NEW', 'CONTACTED', 'LOST') AND agg.customer IS NOT NULL
                      AND EXISTS (SELECT 1 FROM square_booking b WHERE b.business_id = f.business_id
                                  AND b.square_customer_id = agg.customer AND b.status = 'ACCEPTED' AND NOT %s
                                  AND (b.created_at >= agg.last_touch - interval '2 hours'
                                       OR (f.status <> 'LOST' AND b.start_at > now())))
                    RETURNING li.id, li.business_id
                    """.formatted(KEY, ConsultationCatalog.isConsultationSql("b", consultations.ids(businessId))), businessId);
            for (var r : rows) {
                jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by) VALUES (?, ?, 'STATUS', 'BOOKED', 'Square')",
                        ((Number) r.get("id")).longValue(), ((Number) r.get("business_id")).longValue());
            }
            total += rows.size();
        }
        return total;
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

    private void addEvent(long leadId, String type, String text, String by) {
        jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by) VALUES (?, ?, ?, ?, ?)",
                leadId, business.id(), type, text, by);
    }

    private LeadView find(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM (" + leadsSql() + ") y WHERE id = ?", leadMapper, business.id(), business.id(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such lead");
        }
    }

    private String leadKey(long id) {
        return jdbc.queryForObject("SELECT " + KEY + " FROM lead_intake WHERE id = ?", String.class, id);
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
