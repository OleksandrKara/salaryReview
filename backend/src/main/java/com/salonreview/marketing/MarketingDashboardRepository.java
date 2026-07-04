package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the `marketing` Postgres schema — owned and migrated by the separate `salonLandings`
 * FastAPI service, not by this app's Flyway migrations. Plain JdbcTemplate, deliberately not a
 * JPA {@code @Entity}: this app's {@code ddl-auto: validate} must never be coupled to a schema it
 * doesn't own (see openspec/changes/marketing-experiments-dashboard/design.md, D1). Every method
 * here executes lazily per-request; any schema/table mismatch surfaces as a
 * {@link org.springframework.dao.DataAccessException}, handled by {@link MarketingDashboardService}.
 */
@Repository
public class MarketingDashboardRepository {

    /** Raw per-variant counts — conversionRate is computed in the service layer, not stored here. */
    public record RawVariantStat(
            String variantId,
            String name,
            int weight,
            boolean active,
            long pageViews,
            long bookingsCompleted
    ) {}

    private final JdbcTemplate jdbcTemplate;

    public MarketingDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UUID> findLandingPageId(String slug) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM marketing.landing_pages WHERE slug = ?",
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                slug);
        return ids.stream().findFirst();
    }

    /** The single active experiment's status for this page, or empty when none is running. */
    public Optional<String> findExperimentStatus(UUID landingPageId) {
        List<String> statuses = jdbcTemplate.query(
                "SELECT status FROM marketing.experiments WHERE landing_page_id = ? AND status = 'active'",
                (rs, rowNum) -> rs.getString("status"),
                landingPageId);
        return statuses.stream().findFirst();
    }

    public List<RawVariantStat> findVariantStats(UUID landingPageId) {
        String sql = """
                SELECT v.id AS variant_id, v.name AS name, v.weight AS weight, v.active AS active,
                       COALESCE(pv.page_views, 0) AS page_views,
                       COALESCE(bk.bookings_completed, 0) AS bookings_completed
                FROM marketing.landing_variants v
                LEFT JOIN (
                    SELECT variant_id, COUNT(*) AS page_views
                    FROM marketing.events
                    WHERE event_type = 'page_view'
                    GROUP BY variant_id
                ) pv ON pv.variant_id = v.id
                LEFT JOIN (
                    SELECT variant_id, COUNT(*) AS bookings_completed
                    FROM marketing.attribution
                    GROUP BY variant_id
                ) bk ON bk.variant_id = v.id
                WHERE v.landing_page_id = ?
                ORDER BY v.created_at ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawVariantStat(
                rs.getObject("variant_id", UUID.class).toString(),
                rs.getString("name"),
                rs.getInt("weight"),
                rs.getBoolean("active"),
                rs.getLong("page_views"),
                rs.getLong("bookings_completed")
        ), landingPageId);
    }
}
