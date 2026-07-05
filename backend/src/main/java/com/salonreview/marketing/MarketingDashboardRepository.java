package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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
            long bookingsCompleted,
            String key
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

    public Optional<Instant> findStatsSince(UUID landingPageId) {
        List<Timestamp> rows = jdbcTemplate.query(
                "SELECT stats_since FROM marketing.landing_pages WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp("stats_since"),
                landingPageId);
        return rows.stream().findFirst().filter(java.util.Objects::nonNull).map(Timestamp::toInstant);
    }

    public void updateStatsSince(UUID landingPageId, Instant statsSince) {
        jdbcTemplate.update(
                "UPDATE marketing.landing_pages SET stats_since = ? WHERE id = ?",
                statsSince == null ? null : Timestamp.from(statsSince),
                landingPageId);
    }

    /** statsSince null means "all time" — no filtering applied. */
    public List<RawVariantStat> findVariantStats(UUID landingPageId, Instant statsSince) {
        String sql = """
                SELECT v.id AS variant_id, v.name AS name, v.weight AS weight, v.active AS active,
                       v.key AS key,
                       COALESCE(pv.page_views, 0) AS page_views,
                       COALESCE(bk.bookings_completed, 0) AS bookings_completed
                FROM marketing.landing_variants v
                LEFT JOIN (
                    SELECT variant_id, COUNT(*) AS page_views
                    FROM marketing.events
                    WHERE event_type = 'page_view' AND (?::timestamptz IS NULL OR created_at >= ?)
                    GROUP BY variant_id
                ) pv ON pv.variant_id = v.id
                LEFT JOIN (
                    SELECT variant_id, COUNT(*) AS bookings_completed
                    FROM marketing.attribution
                    WHERE (?::timestamptz IS NULL OR created_at >= ?)
                    GROUP BY variant_id
                ) bk ON bk.variant_id = v.id
                WHERE v.landing_page_id = ?
                ORDER BY v.created_at ASC
                """;
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawVariantStat(
                rs.getObject("variant_id", UUID.class).toString(),
                rs.getString("name"),
                rs.getInt("weight"),
                rs.getBoolean("active"),
                rs.getLong("page_views"),
                rs.getLong("bookings_completed"),
                rs.getString("key")
        ), cutoff, cutoff, cutoff, cutoff, landingPageId);
    }

    public Optional<UUID> findVariantLandingPageId(UUID variantId) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT landing_page_id FROM marketing.landing_variants WHERE id = ?",
                (rs, rowNum) -> (UUID) rs.getObject("landing_page_id"),
                variantId);
        return ids.stream().findFirst();
    }

    public void renameVariant(UUID variantId, String newName, String newKey) {
        jdbcTemplate.update(
                "UPDATE marketing.landing_variants SET name = ?, key = ? WHERE id = ?",
                newName, newKey, variantId);
    }

    public void setVariantActive(UUID variantId, boolean active) {
        jdbcTemplate.update("UPDATE marketing.landing_variants SET active = ? WHERE id = ?", active, variantId);
    }

    /** Throws org.springframework.dao.DataIntegrityViolationException if the variant has
     * recorded events/attribution — the service layer translates that into a friendly error.
     */
    public void deleteVariant(UUID variantId) {
        jdbcTemplate.update("DELETE FROM marketing.landing_variants WHERE id = ?", variantId);
    }

    public Optional<VariantSource> findVariantSource(UUID variantId) {
        List<VariantSource> rows = jdbcTemplate.query(
                "SELECT landing_page_id, weight, content FROM marketing.landing_variants WHERE id = ?",
                (rs, rowNum) -> new VariantSource(
                        (UUID) rs.getObject("landing_page_id"),
                        rs.getInt("weight"),
                        rs.getString("content")),
                variantId);
        return rows.stream().findFirst();
    }

    public record VariantSource(UUID landingPageId, int weight, String contentJson) {}

    public UUID duplicateVariant(VariantSource source, String newName, String newKey) {
        List<UUID> ids = jdbcTemplate.query(
                """
                INSERT INTO marketing.landing_variants (landing_page_id, name, weight, content, active, key)
                VALUES (?, ?, ?, ?::jsonb, true, ?)
                RETURNING id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                source.landingPageId(), newName, source.weight(), source.contentJson(), newKey);
        return ids.get(0);
    }
}
