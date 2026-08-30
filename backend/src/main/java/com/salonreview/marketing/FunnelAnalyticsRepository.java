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
 * <p>Everything here is grouped by {@code variant_id}, not {@code flow_key} — more than one
 * variant can share the same {@code flow_key} (e.g. two differently-designed pages both testing
 * "contact info last"), and the owner needs to see each variant's own numbers, not a pooled
 * total across every variant that happens to use the same flow shape. {@code flow_key} is still
 * read (see {@link RawFunnelStep#flowKey()}) purely as descriptive metadata about which funnel
 * shape a variant uses. Rows with a null {@code variant_id} (recorded before variant attribution
 * existed on this table) are excluded rather than pooled into a meaningless "no variant" bucket.
 */
@Repository
public class FunnelAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public FunnelAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One (variant, step) row — reachedCount is a distinct-session count, so revisiting a step
     * via back-navigation (already deduped client-side, but this is a second line of defense)
     * never inflates it. stepCountTotal uses MAX() across this variant's rows in range as a
     * practical stand-in for "the current step count" — only matters if the owner changes a
     * flow's step count mid-history, a rare edge case not worth a more complex resolution now.
     */
    public record RawFunnelStep(UUID variantId, String flowKey, String stepKey, int stepIndex, int stepCountTotal, long reachedCount) {}

    /** Descriptive info about a variant, joined in separately from the step/count queries above
     * (all keyed by variant_id) rather than repeated on every row — name/key/weight/active come
     * straight from marketing.landing_variants, the same table manage_landing_variants.py edits. */
    public record VariantMeta(String name, String key, int weight, boolean active) {}

    /** sources == {@link TrafficSourceSql#ALL} ("All traffic") runs the exact same query as
     * before this filter existed — see MarketingDashboardRepository.findVariantStats for the full
     * rationale (same classification, shared via {@link TrafficSourceSql}).
     */
    public List<RawFunnelStep> findFunnelSteps(UUID landingPageId, Instant statsSince, Instant periodTo, Set<String> sources) {
        boolean all = sources.equals(TrafficSourceSql.ALL);
        String sql = """
                SELECT variant_id, flow_key, step_key, step_index,
                       MAX(step_count_total) AS step_count_total,
                       COUNT(DISTINCT session_id) AS reached_count
                FROM marketing.funnel_events fe
                WHERE fe.landing_page_id = ? AND fe.variant_id IS NOT NULL
                  AND (?::timestamptz IS NULL OR fe.created_at >= ?)
                  AND (?::timestamptz IS NULL OR fe.created_at < ?)
                  AND %s
                GROUP BY variant_id, flow_key, step_key, step_index
                ORDER BY variant_id, step_index
                """.formatted(all ? "TRUE" : TrafficSourceSql.visitInSources("fe.session_id", sources));
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Timestamp to = periodTo == null ? null : Timestamp.from(periodTo);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawFunnelStep(
                (UUID) rs.getObject("variant_id"),
                rs.getString("flow_key"),
                rs.getString("step_key"),
                rs.getInt("step_index"),
                rs.getInt("step_count_total"),
                rs.getLong("reached_count")
        ), landingPageId, cutoff, cutoff, to, to);
    }

    /** name/key/weight/active for every variant this landing page has, regardless of whether it
     * has any recorded funnel activity — a variant with a matching entry above always has an
     * entry here too, since both come from the same landing_page_id. */
    public Map<UUID, VariantMeta> findVariantMeta(UUID landingPageId) {
        Map<UUID, VariantMeta> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, name, key, weight, active FROM marketing.landing_variants WHERE landing_page_id = ?",
                rs -> {
                    result.put((UUID) rs.getObject("id"),
                            new VariantMeta(rs.getString("name"), rs.getString("key"), rs.getInt("weight"), rs.getBoolean("active")));
                },
                landingPageId);
        return result;
    }

    /** Most recent event per variant this landing page has ever recorded, regardless of the
     * owner's currently-selected period filter — used to tell a still-live variant (still in the
     * random-traffic pool, or still reachable via its own ?v= campaign link) from a retired one
     * (weight zeroed / deactivated a while ago) purely from recorded activity, with no need to
     * duplicate manage_landing_variants.py's own weight/active semantics here. Self-maintaining:
     * works the same way for any future variant, not just today's.
     */
    public Map<UUID, Instant> findLastActivityByVariant(UUID landingPageId) {
        Map<UUID, Instant> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT variant_id, MAX(created_at) AS last_activity FROM marketing.funnel_events "
                        + "WHERE landing_page_id = ? AND variant_id IS NOT NULL GROUP BY variant_id",
                rs -> { result.put((UUID) rs.getObject("variant_id"), rs.getTimestamp("last_activity").toInstant()); },
                landingPageId);
        return result;
    }

    /** Per-variant page_view counts — same underlying event stream the main marketing dashboard's
     * page-level total is built from (marketing.events), just grouped by variant_id here instead
     * of summed across the whole page, so each variant's own funnel gets its own denominator.
     */
    public Map<UUID, Long> countPageViewsByVariant(UUID landingPageId, Instant statsSince, Instant periodTo, Set<String> sources) {
        boolean all = sources.equals(TrafficSourceSql.ALL);
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Timestamp to = periodTo == null ? null : Timestamp.from(periodTo);
        Map<UUID, Long> result = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT variant_id, COUNT(*) AS visits FROM marketing.events e
                WHERE e.landing_page_id = ? AND e.event_type = 'page_view' AND e.variant_id IS NOT NULL
                  AND (?::timestamptz IS NULL OR e.created_at >= ?)
                  AND (?::timestamptz IS NULL OR e.created_at < ?)
                  AND %s
                GROUP BY variant_id
                """.formatted(all ? "TRUE" : TrafficSourceSql.visitInSources("e.session_id", sources)),
                rs -> { result.put((UUID) rs.getObject("variant_id"), rs.getLong("visits")); },
                landingPageId, cutoff, cutoff, to, to);
        return result;
    }

    /** Per-variant completed-booking counts — sourced from marketing.attribution (reconciled via
     * Square sync), the same authoritative source the main marketing dashboard's "Bookings" column
     * uses, not a best-effort client-side "done" beacon. Same attribution-has-no-session_id caveat
     * as MarketingDashboardRepository.findVariantStats: a filtered-sources count here is a
     * best-effort floor via a LEFT JOIN to marketing.contacts by booking_id, not a
     * guaranteed-exact count. Unlike the old page-level countBookingsCompleted, this is no longer
     * "shared across every flow shown" — attribution already carries variant_id, so each variant
     * gets its own real count.
     */
    public Map<UUID, Long> countBookingsCompletedByVariant(UUID landingPageId, Instant statsSince, Instant periodTo, Set<String> sources) {
        boolean all = sources.equals(TrafficSourceSql.ALL);
        String filter = all ? "TRUE" : "EXISTS (SELECT 1 FROM marketing.contacts c2 WHERE c2.square_booking_id = a.booking_id AND %s)"
                .formatted(TrafficSourceSql.contactInSources("c2", sources));
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        Timestamp to = periodTo == null ? null : Timestamp.from(periodTo);
        Map<UUID, Long> result = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT variant_id, COUNT(*) AS completed FROM marketing.attribution a
                WHERE a.landing_page_id = ? AND a.variant_id IS NOT NULL
                  AND (?::timestamptz IS NULL OR a.created_at >= ?)
                  AND (?::timestamptz IS NULL OR a.created_at < ?)
                  AND %s
                GROUP BY variant_id
                """.formatted(filter),
                rs -> { result.put((UUID) rs.getObject("variant_id"), rs.getLong("completed")); },
                landingPageId, cutoff, cutoff, to, to);
        return result;
    }
}
