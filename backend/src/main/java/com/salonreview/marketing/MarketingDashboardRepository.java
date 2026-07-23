package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
            long pageViews,
            long bookingsCompleted,
            long contactsCreated,
            /** Clicks on anything that opens the booking form (step 1) — event_type='click',
             * metadata->>'target'='book_now'. Both akluxnails-home and mani fire this same
             * target string from their one shared "open the booking modal" call site. */
            long bookNowClicks,
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
     *
     * <p>sources == {@link TrafficSourceSql#ALL} ("All traffic") counts every marketing.attribution
     * row directly — a plain, exact COUNT(*), no join to marketing.contacts at all. Bookings only
     * need a source *classification* when sources is actually filtered down to a subset, and
     * attribution has no session_id of its own to classify by, so the filtered case (only) joins to
     * marketing.contacts by booking_id to borrow its classification — in the current data only
     * ~57% of attribution rows have a matching contacts row, so a filtered-sources "Bookings" count
     * is a best-effort floor there, not a guaranteed-exact count. (A previous version of this query
     * required that same contacts-row match unconditionally, even in All-traffic mode where no
     * classification is needed at all — silently dropping any real, tracked booking whose contact
     * row's square_booking_id didn't happen to match, e.g. a client who re-booked after an earlier
     * attempt left a stale value there. Confirmed against a real case: a self-booked "home" page
     * client with a genuine attribution row who nonetheless showed zero bookings anywhere on the
     * Overview tab.)
     */
    public List<RawVariantStat> findVariantStats(UUID landingPageId, String landingPageSlug, Instant statsSince, Set<String> sources) {
        String pageViewFilter = sourceFilter(() -> TrafficSourceSql.visitInSources("e.session_id", sources), sources);
        String contactFilterC2 = sourceFilter(() -> TrafficSourceSql.contactInSources("c2", sources), sources);
        String contactFilterC = sourceFilter(() -> TrafficSourceSql.contactInSources("c", sources), sources);
        boolean allTraffic = sources.equals(TrafficSourceSql.ALL);
        String bookingsSubquery = allTraffic
                ? """
                  SELECT a.variant_id, COUNT(*) AS bookings_completed
                  FROM marketing.attribution a
                  WHERE (?::timestamptz IS NULL OR a.created_at >= ?)
                  GROUP BY a.variant_id
                  """
                : """
                  SELECT a.variant_id, COUNT(*) AS bookings_completed
                  FROM marketing.attribution a
                  WHERE (?::timestamptz IS NULL OR a.created_at >= ?)
                    AND EXISTS (
                        SELECT 1 FROM marketing.contacts c2
                        WHERE c2.square_booking_id = a.booking_id AND %s
                    )
                  GROUP BY a.variant_id
                  """.formatted(contactFilterC2);
        String sql = """
                SELECT v.id AS variant_id, v.name AS name, v.weight AS weight,
                       v.key AS key, v.description AS description,
                       COALESCE(pv.page_views, 0) AS page_views,
                       COALESCE(bk.bookings_completed, 0) AS bookings_completed,
                       COALESCE(ct.contacts_created, 0) AS contacts_created,
                       COALESCE(bc.book_now_clicks, 0) AS book_now_clicks
                FROM marketing.landing_variants v
                LEFT JOIN (
                    SELECT e.variant_id, COUNT(*) AS page_views
                    FROM marketing.events e
                    WHERE e.event_type = 'page_view' AND (?::timestamptz IS NULL OR e.created_at >= ?)
                      AND %1$s
                    GROUP BY e.variant_id
                ) pv ON pv.variant_id = v.id
                LEFT JOIN (
                    %2$s
                ) bk ON bk.variant_id = v.id
                LEFT JOIN (
                    SELECT c.variant_name, COUNT(*) AS contacts_created
                    FROM marketing.contacts c
                    WHERE c.landing_page_slug = ? AND (?::timestamptz IS NULL OR c.created_at >= ?)
                      AND %3$s
                    GROUP BY c.variant_name
                ) ct ON ct.variant_name = v.name
                LEFT JOIN (
                    SELECT e.variant_id, COUNT(*) AS book_now_clicks
                    FROM marketing.events e
                    WHERE e.event_type = 'click' AND e.metadata->>'target' = 'book_now'
                      AND (?::timestamptz IS NULL OR e.created_at >= ?)
                      AND %1$s
                    GROUP BY e.variant_id
                ) bc ON bc.variant_id = v.id
                WHERE v.landing_page_id = ?
                ORDER BY v.created_at ASC
                """.formatted(pageViewFilter, bookingsSubquery, contactFilterC);
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RawVariantStat(
                rs.getObject("variant_id", UUID.class).toString(),
                rs.getString("name"),
                rs.getInt("weight"),
                rs.getLong("page_views"),
                rs.getLong("bookings_completed"),
                rs.getLong("contacts_created"),
                rs.getLong("book_now_clicks"),
                rs.getString("key"),
                rs.getString("description")
        ), cutoff, cutoff,
           cutoff, cutoff,
           landingPageSlug, cutoff, cutoff,
           cutoff, cutoff,
           landingPageId);
    }

    /** "All traffic" short-circuits to a literal TRUE (byte-for-byte the pre-filter query);
     * otherwise builds the real classification check. Kept as a tiny helper so each of the four
     * subqueries above reads the same way regardless of which case applies. */
    private static String sourceFilter(java.util.function.Supplier<String> classifiedCheck, Set<String> sources) {
        return sources.equals(TrafficSourceSql.ALL) ? "TRUE" : classifiedCheck.get();
    }

    /** Every booking_id already reflected in marketing.attribution for this landing page (same
     * statsSince cutoff as findVariantStats' bookings_completed subquery) — used to tell whether a
     * contact's currently-real Square appointment is one the tracked flow already counted, or one
     * only Square knows about (e.g. a manager follow-up booked directly, or the tracked request got
     * cancelled and a different booking replaced it).
     */
    public Set<String> findAttributedBookingIds(UUID landingPageId, Instant statsSince) {
        Timestamp cutoff = statsSince == null ? null : Timestamp.from(statsSince);
        List<String> ids = jdbcTemplate.query(
                "SELECT booking_id FROM marketing.attribution WHERE landing_page_id = ? AND (?::timestamptz IS NULL OR created_at >= ?)",
                (rs, rowNum) -> rs.getString("booking_id"),
                landingPageId, cutoff, cutoff);
        return new java.util.HashSet<>(ids);
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
