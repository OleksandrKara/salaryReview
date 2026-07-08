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
            long contactsCreated,
            String key,
            String description
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

    public record LandingPageSummary(String slug, String name) {}

    /** Every landing page this schema knows about, oldest first — feeds the owner dashboard's
     * page selector so a newly-added page (see akluxnails-home) shows up with no frontend change.
     */
    public List<LandingPageSummary> listLandingPages() {
        return jdbcTemplate.query(
                "SELECT slug, name FROM marketing.landing_pages ORDER BY created_at ASC",
                (rs, rowNum) -> new LandingPageSummary(rs.getString("slug"), rs.getString("name")));
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
        return firstNonNullInstant(rows);
    }

    /** Package-private so it's independently unit-testable without a database: filter() must
     * run before findFirst(), since findFirst() itself does Optional.of(element) internally,
     * which throws NPE the instant it reaches a null Timestamp — the default state for every
     * landing page until a cutoff is explicitly set.
     */
    static Optional<Instant> firstNonNullInstant(List<Timestamp> rows) {
        return rows.stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    public void updateStatsSince(UUID landingPageId, Instant statsSince) {
        jdbcTemplate.update(
                "UPDATE marketing.landing_pages SET stats_since = ? WHERE id = ?",
                statsSince == null ? null : Timestamp.from(statsSince),
                landingPageId);
    }

    /** statsSince null means "all time" — no filtering applied. Contacts are matched to a variant
     * by name, not id — marketing.contacts.variant_name is a denormalized snapshot of whatever the
     * variant was called at capture time (see salonLandings; a later rename doesn't change past
     * rows), the same tradeoff already accepted for that column. Also scoped by landing page slug
     * so two different landing pages can't have their variant-name contact counts collide.
     */
    public List<RawVariantStat> findVariantStats(UUID landingPageId, String landingPageSlug, Instant statsSince) {
        String sql = """
                SELECT v.id AS variant_id, v.name AS name, v.weight AS weight, v.active AS active,
                       v.key AS key, v.description AS description,
                       COALESCE(pv.page_views, 0) AS page_views,
                       COALESCE(bk.bookings_completed, 0) AS bookings_completed,
                       COALESCE(ct.contacts_created, 0) AS contacts_created
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
                LEFT JOIN (
                    SELECT variant_name, COUNT(*) AS contacts_created
                    FROM marketing.contacts
                    WHERE landing_page_slug = ? AND (?::timestamptz IS NULL OR created_at >= ?)
                    GROUP BY variant_name
                ) ct ON ct.variant_name = v.name
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
                rs.getLong("contacts_created"),
                rs.getString("key"),
                rs.getString("description")
        ), cutoff, cutoff, cutoff, cutoff, landingPageSlug, cutoff, cutoff, landingPageId);
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

    public void updateVariantDescription(UUID variantId, String description) {
        jdbcTemplate.update("UPDATE marketing.landing_variants SET description = ? WHERE id = ?", description, variantId);
    }

    /** Throws org.springframework.dao.DataIntegrityViolationException if the variant has
     * recorded events/attribution — the service layer translates that into a friendly error.
     */
    public void deleteVariant(UUID variantId) {
        jdbcTemplate.update("DELETE FROM marketing.landing_variants WHERE id = ?", variantId);
    }

    public Optional<VariantSource> findVariantSource(UUID variantId) {
        List<VariantSource> rows = jdbcTemplate.query(
                "SELECT landing_page_id, weight, content, description FROM marketing.landing_variants WHERE id = ?",
                (rs, rowNum) -> new VariantSource(
                        (UUID) rs.getObject("landing_page_id"),
                        rs.getInt("weight"),
                        rs.getString("content"),
                        rs.getString("description")),
                variantId);
        return rows.stream().findFirst();
    }

    public record VariantSource(UUID landingPageId, int weight, String contentJson, String description) {}

    public UUID duplicateVariant(VariantSource source, String newName, String newKey) {
        List<UUID> ids = jdbcTemplate.query(
                """
                INSERT INTO marketing.landing_variants (landing_page_id, name, weight, content, active, key, description)
                VALUES (?, ?, ?, ?::jsonb, true, ?, ?)
                RETURNING id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                source.landingPageId(), newName, source.weight(), source.contentJson(), newKey, source.description());
        return ids.get(0);
    }
}
