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
    static final Set<String> MANUAL_EVENT_TYPES = Set.of("NOTE", "CALL", "AGREEMENT");
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
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public LeadFollowUpService(JdbcTemplate jdbc, CurrentBusinessContext business, TelegramNotificationService telegram, GeoIpService geo,
                               SquareClientProvider squares,
                               @org.springframework.beans.factory.annotation.Value("${app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.business = business;
        this.telegram = telegram;
        this.publicBaseUrl = publicBaseUrl;
        this.geo = geo;
        this.squares = squares;
    }

    public record LeadView(long id, String channel, String source, String phoneNumber, String email, String name,
                           String detail, String status, String callbackAt, String createdAt, String lastTouchAt,
                           int touches, String squareCustomerId, String squareUrl) {}

    public record Touch(long id, String channel, String source, String detail, String createdAt,
                        java.util.Map<String, String> payload) {}

    public record Origin(String type, String at, String landingPage, String variant, String trafficSource,
                         String utmSource, String utmMedium, String utmCampaign, String utmTerm, String utmContent,
                         boolean googleClick, boolean facebookClick, String referrer, String device, String os,
                         String browser, String ip, String location, String service) {}

    public record Event(long id, String type, String text, String createdBy, String createdAt) {}

    public record Appointment(String id, String status, String startAt, String services) {}

    public record LeadDetail(LeadView lead, List<Touch> touches, List<Event> events, List<Origin> origins,
                             List<Appointment> appointments) {}

    public record UpdateRequest(String status, String callbackAt, Boolean clearCallback) {}

    public record EventRequest(String type, String text) {}

    private final RowMapper<LeadView> leadMapper = (rs, i) -> {
        String customer = rs.getString("square_customer_id");
        return new LeadView(rs.getLong("id"), rs.getString("channel"), rs.getString("source"),
                rs.getString("phone_number"), rs.getString("email"), rs.getString("name"), rs.getString("detail"),
                rs.getString("status"), iso(rs.getTimestamp("callback_at")), iso(rs.getTimestamp("created_at")),
                iso(rs.getTimestamp("last_touch_at")), rs.getInt("touches"), customer,
                customer == null ? null : SQUARE_CUSTOMER_URL + customer);
    };

    private static final String LEADS_SELECT = """
            WITH keyed AS (
              SELECT l.*, %s AS k FROM lead_intake l
              WHERE business_id = ? AND outcome <> 'DUPLICATE'
            ), agg AS (
              SELECT k, count(*) AS touches, max(created_at) AS last_touch_at,
                     (array_agg(square_customer_id ORDER BY (square_customer_id IS NULL), created_at))[1] AS customer,
                     (array_agg(name ORDER BY (name IS NULL), created_at))[1] AS any_name
              FROM keyed GROUP BY k
            )
            SELECT f.id, f.channel, f.source, COALESCE(sc.phone_number, f.phone_number) AS phone_number,
                   COALESCE(sc.email_address, f.email) AS email,
                   COALESCE(NULLIF(trim(concat_ws(' ', sc.given_name, sc.family_name)), ''), f.name, agg.any_name) AS name,
                   f.detail, f.status, f.callback_at, f.created_at, agg.last_touch_at, agg.touches::int AS touches,
                   agg.customer AS square_customer_id
            FROM (SELECT DISTINCT ON (k) * FROM keyed ORDER BY k, created_at, id) f JOIN agg ON agg.k = f.k
              LEFT JOIN square_customer sc ON sc.business_id = f.business_id AND sc.square_customer_id = agg.customer
            """.formatted(KEY);

    public List<LeadView> list(String status) {
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("SELECT * FROM (" + LEADS_SELECT + ") x WHERE (?::text IS NULL OR status = ?) "
                + "ORDER BY (status = 'NEW') DESC, last_touch_at DESC LIMIT 300", leadMapper, business.id(), filter, filter);
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
        return new LeadDetail(lead, touches, events, origins(lead), appointments(lead));
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
                ORDER BY start_at DESC NULLS LAST LIMIT 10
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
        List<Appointment> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            java.util.Map<String, String> n = names;
            String services = perRow.get(i).stream().map(n::get).filter(java.util.Objects::nonNull).distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
            out.add(new Appointment((String) r[0], (String) r[1], (String) r[2], services.isEmpty() ? null : services));
        }
        return out;
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
                  callback_at = CASE WHEN ? THEN NULL WHEN ?::timestamptz IS NOT NULL THEN ?::timestamptz ELSE callback_at END,
                  callback_reminded = CASE WHEN ? OR ?::timestamptz IS NOT NULL THEN FALSE ELSE callback_reminded END,
                  updated_by = ?, updated_at = now()
                WHERE id = ? AND business_id = ?
                """, req.status(), clear, callback, callback, clear, callback, by, id, business.id());
        if (req.status() != null) addEvent(id, "STATUS", req.status(), by);
        if (callback != null) addEvent(id, "CALLBACK", callback.toInstant().toString(), by);
        if (clear) addEvent(id, "CALLBACK_CLEARED", null, by);
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
            return jdbc.queryForObject("SELECT * FROM (" + LEADS_SELECT + ") x WHERE id = ?", leadMapper, business.id(), id);
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
}
