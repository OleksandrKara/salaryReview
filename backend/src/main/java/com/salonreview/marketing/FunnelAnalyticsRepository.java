package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** sources == {@link TrafficSourceSql#ALL} ("All traffic") runs the exact same query as
     * before this filter existed — see MarketingDashboardRepository.findVariantStats for the full
     * rationale (same classification, shared via {@link TrafficSourceSql}).
     */
    public List<RawFunnelStep> findFunnelSteps(UUID landingPageId, Instant statsSince, Instant periodTo, Set<String> sources) {
        boolean all = sources.equals(TrafficSourceSql.ALL);
        String sql = """
                SELECT flow_key, step_key, step_index,
                       MAX(step_count_total) AS step_count_total,
                       COUNT(DISTINCT session_id) AS reached_count
                FROM marketing.funnel_events fe
                WHERE fe.landing_page_id = ? AND (?::timestamptz IS NULL OR fe.created_at >= ?)
                  AND (?::timestamptz IS NULL OR fe.created_at < ?)
                  AND %s
                GROUP BY flow_key, step_key, step_index
                ORDER BY flow_key, step_index
                """.formatted(all ? "TRUE" : TrafficSourceSql.visitInSources("fe.session_id", sources));
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Timestamp to = periodTo == null ? null : Timestamp.from(periodTo);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawFunnelStep(
                rs.getString("flow_key"),
                rs.getString("step_key"),
                rs.getInt("step_index"),
                rs.getInt("step_count_total"),
                rs.getLong("reached_count")
        ), landingPageId, cutoff, cutoff, to, to);
    }

    /** Most recent event per {@code flow_key} this landing page has ever recorded, regardless of
     * the owner's currently-selected period filter — used to tell a still-live flow (a variant
     * still in the random-traffic pool) from a retired one (every variant using it has since had
     * its weight zeroed/deactivated) purely from recorded activity, with no need to know anything
     * about {@code marketing.landing_variants}' own weight/active columns or their mapping to a
     * flow (see {@code funnelFlow.ts}'s own {@code contactStepPosition}-keyed {@code BOOKING_FLOWS}
     * — replicating that mapping here would just be a second, driftable copy of the same logic).
     * Self-maintaining: works the same way for any future flow redesign, not just this one.
     */
    public Map<String, Instant> findLastActivityByFlow(UUID landingPageId) {
        Map<String, Instant> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT flow_key, MAX(created_at) AS last_activity FROM marketing.funnel_events "
                        + "WHERE landing_page_id = ? GROUP BY flow_key",
                rs -> {
                    result.put(rs.getString("flow_key"), rs.getTimestamp("last_activity").toInstant());
                },
                landingPageId);
        return result;
    }

    /** Top-of-funnel denominator — same page_view count the main marketing dashboard shows, so
     * the two dashboards never disagree on "how many people saw this page".
     */
    public long countPageViews(UUID landingPageId, Instant statsSince, Instant periodTo, Set<String> sources) {
        boolean all = sources.equals(TrafficSourceSql.ALL);
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Timestamp to = periodTo == null ? null : Timestamp.from(periodTo);
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM marketing.events e
                WHERE e.landing_page_id = ? AND e.event_type = 'page_view'
                  AND (?::timestamptz IS NULL OR e.created_at >= ?)
                  AND (?::timestamptz IS NULL OR e.created_at < ?)
                  AND %s
                """.formatted(all ? "TRUE" : TrafficSourceSql.visitInSources("e.session_id", sources)),
                Long.class, landingPageId, cutoff, cutoff, to, to);
        return count == null ? 0 : count;
    }

    /** Bottom-of-funnel signal — sourced from marketing.attribution (reconciled via Square sync),
     * the same authoritative source the main marketing dashboard's "Bookings" column uses, not a
     * best-effort client-side "done" beacon. Same attribution-has-no-session_id caveat as
     * MarketingDashboardRepository.findVariantStats: a filtered-sources count here is a
     * best-effort floor via a LEFT JOIN to marketing.contacts by booking_id, not a
     * guaranteed-exact count.
     */
    public long countBookingsCompleted(UUID landingPageId, Instant statsSince, Instant periodTo, Set<String> sources) {
        boolean all = sources.equals(TrafficSourceSql.ALL);
        String filter = all ? "TRUE" : "EXISTS (SELECT 1 FROM marketing.contacts c2 WHERE c2.square_booking_id = a.booking_id AND %s)"
                .formatted(TrafficSourceSql.contactInSources("c2", sources));
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Timestamp to = periodTo == null ? null : Timestamp.from(periodTo);
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM marketing.attribution a
                WHERE a.landing_page_id = ? AND (?::timestamptz IS NULL OR a.created_at >= ?)
                  AND (?::timestamptz IS NULL OR a.created_at < ?)
                  AND %s
                """.formatted(filter),
                Long.class, landingPageId, cutoff, cutoff, to, to);
        return count == null ? 0 : count;
    }
}
