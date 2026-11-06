package com.salonreview.marketing;

import com.salonreview.square.SquareClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Square service variations whose catalog name contains "consultation", per business (cached). */
@Component
final class ConsultationCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConsultationCatalog.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private record Entry(Set<String> ids, Instant at) {}

    private final JdbcTemplate jdbc;
    private final SquareClientProvider squares;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    ConsultationCatalog(JdbcTemplate jdbc, SquareClientProvider squares) {
        this.jdbc = jdbc;
        this.squares = squares;
    }

    Set<String> ids(long businessId) {
        Entry e = cache.get(businessId);
        if (e != null && e.at().plus(TTL).isAfter(Instant.now())) return e.ids();
        Set<String> out = new LinkedHashSet<>();
        try {
            Set<String> variations = new LinkedHashSet<>(jdbc.queryForList("""
                    SELECT DISTINCT s->>'serviceVariationId' FROM square_booking b,
                           jsonb_array_elements(COALESCE(b.appointment_segments_json, '[]'::jsonb)) s
                    WHERE b.business_id = ? AND s->>'serviceVariationId' ~ '^[A-Za-z0-9_-]+$'
                    """, String.class, businessId));
            if (!variations.isEmpty()) {
                squares.forBusiness(businessId).catalogNames(variations).forEach((id, name) -> {
                    if (name != null && name.toLowerCase(Locale.ROOT).contains("consultation")) out.add(id);
                });
            }
        } catch (Exception ex) {
            log.warn("Consultation catalog unavailable for business {}: {}", businessId, ex.toString());
            return e == null ? Set.of() : e.ids();
        }
        cache.put(businessId, new Entry(out, Instant.now()));
        return out;
    }

    /** SQL boolean: every segment of booking alias {@code b} is a consultation. Ids come from ids(), already restricted to [A-Za-z0-9_-]. */
    static String isConsultationSql(String b, Set<String> ids) {
        String list = ids.isEmpty() ? "ARRAY[]::text[]"
                : "ARRAY[" + String.join(",", ids.stream().map(i -> "'" + i + "'").toList()) + "]::text[]";
        return "(jsonb_array_length(COALESCE(" + b + ".appointment_segments_json, '[]'::jsonb)) > 0 AND NOT EXISTS ("
                + "SELECT 1 FROM jsonb_array_elements(" + b + ".appointment_segments_json) cs "
                + "WHERE NOT (cs->>'serviceVariationId' = ANY(" + list + "))))";
    }
}
