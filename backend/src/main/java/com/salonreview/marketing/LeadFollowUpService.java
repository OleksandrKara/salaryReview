package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class LeadFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(LeadFollowUpService.class);
    static final Set<String> STATUSES = Set.of("NEW", "CONTACTED", "BOOKED", "LOST");

    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext business;
    private final TwilioSmsService sms;
    private final TelegramNotificationService telegram;

    public LeadFollowUpService(JdbcTemplate jdbc, CurrentBusinessContext business, TwilioSmsService sms,
                               TelegramNotificationService telegram) {
        this.jdbc = jdbc;
        this.business = business;
        this.sms = sms;
        this.telegram = telegram;
    }

    public record LeadView(long id, String channel, String source, String trafficSource, String phoneNumber,
                           String email, String name, String detail, String outcome, String status,
                           String callbackAt, String notes, String updatedBy, String createdAt) {}

    public record UpdateRequest(String status, String callbackAt, Boolean clearCallback, String notes) {}

    public record SmsRequest(String body) {}

    public List<LeadView> list(String status) {
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("""
                SELECT id, channel, source, traffic_source, phone_number, email, name, detail, outcome, status,
                       callback_at, notes, updated_by, created_at
                FROM lead_intake
                WHERE business_id = ? AND outcome <> 'DUPLICATE' AND (?::text IS NULL OR status = ?)
                ORDER BY (status = 'NEW') DESC, created_at DESC
                LIMIT 300
                """, (rs, i) -> new LeadView(rs.getLong("id"), rs.getString("channel"), rs.getString("source"),
                rs.getString("traffic_source"), rs.getString("phone_number"), rs.getString("email"),
                rs.getString("name"), rs.getString("detail"), rs.getString("outcome"), rs.getString("status"),
                iso(rs.getTimestamp("callback_at")), rs.getString("notes"), rs.getString("updated_by"),
                iso(rs.getTimestamp("created_at"))), business.id(), filter, filter);
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
        int n = jdbc.update("""
                UPDATE lead_intake SET
                  status = COALESCE(?, status),
                  callback_at = CASE WHEN ? THEN NULL WHEN ?::timestamptz IS NOT NULL THEN ?::timestamptz ELSE callback_at END,
                  callback_reminded = CASE WHEN ? OR ?::timestamptz IS NOT NULL THEN FALSE ELSE callback_reminded END,
                  notes = COALESCE(?, notes),
                  updated_by = ?, updated_at = now()
                WHERE id = ? AND business_id = ?
                """, req.status(), clear, callback, callback, clear, callback,
                req.notes() == null ? null : req.notes().trim(), by, id, business.id());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such lead");
    }

    public void sendSms(long id, String body, String by) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message body required");
        }
        String phone;
        try {
            phone = jdbc.queryForObject("SELECT phone_number FROM lead_intake WHERE id = ? AND business_id = ?",
                    String.class, id, business.id());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such lead");
        }
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lead has no phone number");
        }
        TwilioSmsService.SmsSendResult res = sms.sendManual(business.id(), phone, body.trim());
        if (!res.sent()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "sms not sent: " + res.reason());
        }
        jdbc.update("UPDATE lead_intake SET status = 'CONTACTED', updated_by = ?, updated_at = now() "
                + "WHERE id = ? AND business_id = ? AND status = 'NEW'", by, id, business.id());
    }

    /** Pings the business Telegram chat about callbacks that came due; each reminder fires once. */
    public void sendDueReminders() {
        var due = jdbc.queryForList("""
                SELECT id, business_id, name, phone_number, email, source, notes
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
            if (row.get("notes") != null) text.append("\nNote: ").append(row.get("notes"));
            boolean sent = telegram.sendPlainText(businessId, text.toString());
            if (sent) {
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
