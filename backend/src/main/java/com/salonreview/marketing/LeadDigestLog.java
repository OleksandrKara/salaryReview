package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

@Component
public class LeadDigestLog {

    public record Entry(String status, int attempts) {}

    private final JdbcTemplate jdbc;

    public LeadDigestLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Entry find(long businessId, String kind, LocalDate day) {
        return jdbc.query("SELECT status, attempts FROM lead_digest_send WHERE business_id = ? AND kind = ? AND day = ?",
                rs -> rs.next() ? new Entry(rs.getString(1), rs.getInt(2)) : null,
                businessId, kind, java.sql.Date.valueOf(day));
    }

    public void record(long businessId, String kind, LocalDate day, String status, int attempts, String text) {
        jdbc.update("""
                INSERT INTO lead_digest_send (business_id, kind, day, status, attempts, text, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'SENT' THEN now() END)
                ON CONFLICT (business_id, kind, day) DO UPDATE SET status = EXCLUDED.status, attempts = EXCLUDED.attempts,
                  text = EXCLUDED.text, sent_at = EXCLUDED.sent_at, updated_at = now()
                """, businessId, kind, java.sql.Date.valueOf(day), status, attempts, text, status);
    }

    public long countSent(long businessId, Instant since) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM lead_digest_send WHERE business_id = ? AND status = 'SENT' AND sent_at >= ?",
                Long.class, businessId, Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
