package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads marketing.abuse_blocks — every booking submission rejected by salonLandings' abuse
 * guard (rate limit, honeypot, timing, failed Turnstile check). Plain JdbcTemplate for the same
 * reason as MarketingDashboardRepository: this table is owned and migrated by salonLandings.
 */
@Repository
public class AbuseBlocksRepository {

    public record RawBlock(String endpoint, String reason, String phoneNumber, String ipAddress, Instant occurredAt) {}

    private final JdbcTemplate jdbcTemplate;

    public AbuseBlocksRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Count of blocks in the last 24h, grouped by reason — the at-a-glance summary. */
    public Map<String, Integer> countByReasonLast24h() {
        String sql = """
                SELECT reason, COUNT(*) AS cnt
                FROM marketing.abuse_blocks
                WHERE occurred_at >= now() - INTERVAL '24 hours'
                GROUP BY reason
                """;
        return jdbcTemplate.query(sql, rs -> {
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            while (rs.next()) counts.put(rs.getString("reason"), rs.getInt("cnt"));
            return counts;
        });
    }

    /** Most recent blocks, newest first, for the detail list. */
    public List<RawBlock> recent(int limit) {
        String sql = """
                SELECT endpoint, reason, phone_number, ip_address, occurred_at
                FROM marketing.abuse_blocks
                ORDER BY occurred_at DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawBlock(
                rs.getString("endpoint"),
                rs.getString("reason"),
                rs.getString("phone_number"),
                rs.getString("ip_address"),
                toInstant(rs.getTimestamp("occurred_at"))
        ), limit);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
