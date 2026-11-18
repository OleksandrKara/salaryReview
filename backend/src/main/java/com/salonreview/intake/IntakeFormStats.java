package com.salonreview.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** Automations page funnel for the first-visit intake form: counts only, never answers. */
@Component
public class IntakeFormStats {

    /** {@code signed} is forms the client filled in; {@code signedOnPaper} is marked received by staff.
     * {@code medianMinutesToSign} covers client-signed forms only and is null when there are none. */
    public record Stats(long sent, long opened, long signed, long signedOnPaper, long unsigned,
                        long remindersSent, long signedAfterReminder, Long medianMinutesToSign) {}

    /** Critical-answer alerts, last 30 days: texts to managers, last time one went out, failures, and the
     * Square appointment banners written or still failing. Counts only, never answers. */
    public record CriticalStats(long alertsSent, long criticalSent, Instant lastAlertAt, long alertsFailed,
                                long markersWritten, long markersFailing) {}

    private final JdbcTemplate jdbc;

    public IntakeFormStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Stats since(long businessId, Instant since) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS sent,
                       count(*) FILTER (WHERE opened_at IS NOT NULL OR status = 'SIGNED') AS opened,
                       count(*) FILTER (WHERE status = 'SIGNED' AND signed_via = 'CLIENT_FORM') AS signed,
                       count(*) FILTER (WHERE status = 'SIGNED' AND signed_via = 'STAFF_PAPER') AS paper,
                       count(*) FILTER (WHERE status <> 'SIGNED') AS unsigned,
                       count(*) FILTER (WHERE reminder_sent_at IS NOT NULL) AS reminders,
                       count(*) FILTER (WHERE signed_via = 'CLIENT_FORM' AND reminder_sent_at IS NOT NULL
                                          AND signed_at > reminder_sent_at) AS after_reminder,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM signed_at - sent_at) / 60)
                           FILTER (WHERE signed_via = 'CLIENT_FORM' AND signed_at >= sent_at) AS median_minutes
                FROM intake_form WHERE business_id = ? AND sent_at > ?
                """, businessId, Timestamp.from(since));
        Number median = (Number) r.get("median_minutes");
        return new Stats(num(r.get("sent")), num(r.get("opened")), num(r.get("signed")), num(r.get("paper")),
                num(r.get("unsigned")), num(r.get("reminders")), num(r.get("after_reminder")),
                median == null ? null : Math.round(median.doubleValue()));
    }

    public CriticalStats critical(long businessId, Instant since) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE critical_alert_state = 'SENT') AS sent,
                       count(*) FILTER (WHERE critical_alert_state = 'SENT' AND attention_level = 'CRITICAL') AS crit,
                       max(critical_alerted_at) AS last_at,
                       count(*) FILTER (WHERE critical_alert_state = 'FAILED') AS failed
                FROM intake_form WHERE business_id = ? AND signed_at > ?
                """, businessId, Timestamp.from(since));
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE synced_at IS NOT NULL) AS written,
                       count(*) FILTER (WHERE synced_at IS NULL AND attempts > 0) AS failing
                FROM intake_booking_flag_sync WHERE business_id = ? AND updated_at > ?
                """, businessId, Timestamp.from(since));
        Timestamp last = (Timestamp) r.get("last_at");
        return new CriticalStats(num(r.get("sent")), num(r.get("crit")), last == null ? null : last.toInstant(),
                num(r.get("failed")), num(m.get("written")), num(m.get("failing")));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
