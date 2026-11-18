package com.salonreview.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Records every Telegram automation delivery so the Automations tab shows real numbers, never guesses. */
@Component
public class TelegramAutomationLog {

    private static final Logger log = LoggerFactory.getLogger(TelegramAutomationLog.class);

    public static final String NAILS = "nails";
    public static final String PMU = "pmu";
    public static final String ACADEMY = "academy";

    /** One line of a card: a message kind in one direction (either may be empty). */
    public record Row(String label, String direction, long sent7, long sent30, long failed30, Instant lastSentAt, Instant lastFailedAt) {}

    private final JdbcTemplate jdbc;

    public TelegramAutomationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Never throws: a bookkeeping problem must not break the alert itself. */
    public void record(long businessId, String automationKey, String label, String direction, boolean ok) {
        try {
            jdbc.update("INSERT INTO telegram_automation_log (business_id, automation_key, label, direction, ok) VALUES (?, ?, ?, ?, ?)",
                    businessId, automationKey, label == null ? "" : label, direction == null ? "" : direction, ok);
        } catch (RuntimeException e) {
            log.warn("Could not log Telegram automation {} for business {}: {}", automationKey, businessId, e.toString());
        }
    }

    public List<Row> rows(long businessId, String automationKey, Instant now) {
        Timestamp d7 = Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        Timestamp d30 = Timestamp.from(now.minus(30, ChronoUnit.DAYS));
        return jdbc.query("""
                SELECT label, direction,
                       count(*) FILTER (WHERE ok AND created_at >= ?) AS sent7,
                       count(*) FILTER (WHERE ok) AS sent30,
                       count(*) FILTER (WHERE NOT ok) AS failed30,
                       max(created_at) FILTER (WHERE ok) AS last_sent,
                       max(created_at) FILTER (WHERE NOT ok) AS last_failed
                FROM telegram_automation_log WHERE business_id = ? AND automation_key = ? AND created_at >= ?
                GROUP BY label, direction ORDER BY label, direction
                """, (rs, i) -> new Row(rs.getString("label"), rs.getString("direction"), rs.getLong("sent7"), rs.getLong("sent30"),
                rs.getLong("failed30"), instant(rs.getTimestamp("last_sent")), instant(rs.getTimestamp("last_failed"))),
                d7, businessId, automationKey, d30);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
