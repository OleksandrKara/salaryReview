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

    /** adsOnly=false ("All traffic") runs the exact same query as before this filter existed —
     * see MarketingDashboardRepository.findVariantStats for the full rationale (same guard
     * pattern, same fbclid/gclid-via-marketing.visits join, shared via {@link AdsTrafficSql}).
     */
    public List<RawFunnelStep> findFunnelSteps(UUID landingPageId, Instant statsSince, boolean adsOnly) {
        String sql = """
                SELECT flow_key, step_key, step_index,
                       MAX(step_count_total) AS step_count_total,
                       COUNT(DISTINCT session_id) AS reached_count
                FROM marketing.funnel_events fe
                WHERE fe.landing_page_id = ? AND (?::timestamptz IS NULL OR fe.created_at >= ?)
                  AND (?::boolean = false OR %s)
                GROUP BY flow_key, step_key, step_index
                ORDER BY flow_key, step_index
                """.formatted(AdsTrafficSql.VISIT_EXISTS.formatted("fe.session_id"));
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawFunnelStep(
                rs.getString("flow_key"),
                rs.getString("step_key"),
                rs.getInt("step_index"),
                rs.getInt("step_count_total"),
                rs.getLong("reached_count")
        ), landingPageId, cutoff, cutoff, adsOnly);
    }

    /** Top-of-funnel denominator — same page_view count the main marketing dashboard shows, so
     * the two dashboards never disagree on "how many people saw this page".
     */
    public long countPageViews(UUID landingPageId, Instant statsSince, boolean adsOnly) {
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM marketing.events e
                WHERE e.landing_page_id = ? AND e.event_type = 'page_view'
                  AND (?::timestamptz IS NULL OR e.created_at >= ?)
                  AND (?::boolean = false OR %s)
                """.formatted(AdsTrafficSql.VISIT_EXISTS.formatted("e.session_id")),
                Long.class, landingPageId, cutoff, cutoff, adsOnly);
        return count == null ? 0 : count;
    }

    /** Bottom-of-funnel signal — sourced from marketing.attribution (reconciled via Square sync),
     * the same authoritative source the main marketing dashboard's "Bookings" column uses, not a
     * best-effort client-side "done" beacon. Same attribution-has-no-session_id caveat as
     * MarketingDashboardRepository.findVariantStats: adsOnly here is a best-effort floor via a
     * LEFT JOIN to marketing.contacts by booking_id, not a guaranteed-exact count.
     */
    public long countBookingsCompleted(UUID landingPageId, Instant statsSince, boolean adsOnly) {
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM marketing.attribution a
                WHERE a.landing_page_id = ? AND (?::timestamptz IS NULL OR a.created_at >= ?)
                  AND (?::boolean = false OR EXISTS (
                      SELECT 1 FROM marketing.contacts c2
                      WHERE c2.square_booking_id = a.booking_id AND %s
                  ))
                """.formatted(AdsTrafficSql.CONTACT_CONDITION.formatted("c2")),
                Long.class, landingPageId, cutoff, cutoff, adsOnly);
        return count == null ? 0 : count;
    }
}
