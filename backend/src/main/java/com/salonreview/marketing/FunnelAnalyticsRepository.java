package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads {@code marketing.funnel_events} — a table added specifically for booking-funnel
 * tracking, decoupled from {@code marketing.events} and its {@code event_type} CHECK constraint
 * (see the akluxnails-home/salonLandings PRs that write to this table). Same
 * "never own the marketing schema" posture as {@link MarketingDashboardRepository}: plain
 * JdbcTemplate, not a JPA entity.
 *
 * <p>Genericity note: a landing page's booking flow is identified by {@code flow_key}, and each
 * flow reports its own step vocabulary/order — this repository does not assume any particular
 * step names or count, only that {@code step_index} is 0-based within whatever {@code flow_key}
 * produced the row. A landing page could in principle have more than one {@code flow_key} in its
 * history (e.g. after a flow redesign); every method here groups by {@code flow_key} so callers
 * see each as its own funnel rather than a nonsensical merge of two different step vocabularies.
 */
@Repository
public class FunnelAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public FunnelAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One (flow_key, step) row — reachedCount is a distinct-session count, so revisiting a step
     * via back-navigation (already deduped client-side, but this is a second line of defense)
     * never inflates it. stepCountTotal uses MAX() across this flow_key's rows in range as a
     * practical stand-in for "the current step count" — only matters if the owner changes a
     * flow's step count mid-history, a rare edge case not worth a more complex resolution now.
     */
    public record RawFunnelStep(String flowKey, String stepKey, int stepIndex, int stepCountTotal, long reachedCount) {}

    public List<RawFunnelStep> findFunnelSteps(UUID landingPageId, Instant statsSince) {
        String sql = """
                SELECT flow_key, step_key, step_index,
                       MAX(step_count_total) AS step_count_total,
                       COUNT(DISTINCT session_id) AS reached_count
                FROM marketing.funnel_events
                WHERE landing_page_id = ? AND (?::timestamptz IS NULL OR created_at >= ?)
                GROUP BY flow_key, step_key, step_index
                ORDER BY flow_key, step_index
                """;
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawFunnelStep(
                rs.getString("flow_key"),
                rs.getString("step_key"),
                rs.getInt("step_index"),
                rs.getInt("step_count_total"),
                rs.getLong("reached_count")
        ), landingPageId, cutoff, cutoff);
    }

    /** Top-of-funnel denominator — same page_view count the main marketing dashboard shows, so
     * the two dashboards never disagree on "how many people saw this page".
     */
    public long countPageViews(UUID landingPageId, Instant statsSince) {
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM marketing.events
                WHERE landing_page_id = ? AND event_type = 'page_view'
                  AND (?::timestamptz IS NULL OR created_at >= ?)
                """,
                Long.class, landingPageId, cutoff, cutoff);
        return count == null ? 0 : count;
    }

    /** Bottom-of-funnel signal — sourced from marketing.attribution (reconciled via Square sync),
     * the same authoritative source the main marketing dashboard's "Bookings" column uses, not a
     * best-effort client-side "done" beacon.
     */
    public long countBookingsCompleted(UUID landingPageId, Instant statsSince) {
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM marketing.attribution
                WHERE landing_page_id = ? AND (?::timestamptz IS NULL OR created_at >= ?)
                """,
                Long.class, landingPageId, cutoff, cutoff);
        return count == null ? 0 : count;
    }
}
