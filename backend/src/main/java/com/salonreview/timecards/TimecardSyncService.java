package com.salonreview.timecards;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import com.salonreview.square.SquareLaborClient;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps {@code square_timecard_mirror} equal to Square's Labor timecards for the rolling window. */
@Service
public class TimecardSyncService {

    private static final Logger log = LoggerFactory.getLogger(TimecardSyncService.class);
    static final int WINDOW_DAYS = 45;
    private static final String DEFAULT_ZONE = "America/Los_Angeles";

    private final SquareLaborClient labor;
    private final SquareConnectionRepository connections;
    private final JdbcTemplate jdbc;

    public TimecardSyncService(SquareLaborClient labor, SquareConnectionRepository connections, JdbcTemplate jdbc) {
        this.labor = labor;
        this.connections = connections;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "TimecardSyncService_nightly", lockAtLeastFor = "PT30S", lockAtMostFor = "PT15M")
    public void nightly() {
        List<Long> businesses = jdbc.queryForList(
                "SELECT DISTINCT business_id FROM providers WHERE worker_type = 'W2' AND active", Long.class);
        for (Long businessId : businesses) {
            try {
                int n = sync(businessId);
                log.info("Timecard sync for business {}: {} timecards", businessId, n);
            } catch (RuntimeException e) {
                log.warn("Timecard sync failed for business {}: {}", businessId, e.toString());
            }
        }
    }

    /** Returns the number of timecards upserted. */
    public int sync(Long businessId) {
        SquareConnection connection = connections.findByBusinessId(businessId).orElse(null);
        if (connection == null) return 0;
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant from = to.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        List<JsonNode> cards = labor.searchTimecards(businessId, connection.getLocationId(), from, to);

        Map<String, Long> providerByMember = new java.util.HashMap<>();
        jdbc.query("SELECT m.square_team_member_id, m.provider_id FROM provider_square_member m "
                        + "JOIN providers p ON p.id = m.provider_id WHERE p.business_id = ?",
                rs -> { providerByMember.put(rs.getString(1), rs.getLong(2)); }, businessId);

        Set<String> seen = new HashSet<>();
        int upserted = 0;
        for (JsonNode c : cards) {
            Long providerId = providerByMember.get(c.path("team_member_id").asText(""));
            if (providerId == null) continue;
            String id = c.path("id").asText();
            seen.add(id);
            upsert(businessId, providerId, c);
            upserted++;
        }
        markMissingDeleted(businessId, from, to, seen);
        return upserted;
    }

    private void upsert(Long businessId, Long providerId, JsonNode c) {
        String zone = c.path("timezone").asText(DEFAULT_ZONE);
        Instant start = Instant.parse(c.path("start_at").asText());
        Instant end = c.hasNonNull("end_at") ? Instant.parse(c.get("end_at").asText()) : null;
        java.time.LocalDate workDate = start.atZone(ZoneId.of(zone)).toLocalDate();
        Integer rate = c.path("wage").path("hourly_rate").hasNonNull("amount")
                ? c.path("wage").path("hourly_rate").get("amount").asInt() : null;
        String breaks = c.path("breaks").isMissingNode() ? "[]" : c.get("breaks").toString();
        jdbc.update("""
                INSERT INTO square_timecard_mirror (business_id, square_timecard_id, provider_id, work_date, start_at,
                    end_at, timezone, hourly_rate_cents, breaks_json, status, square_version, deleted, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, now())
                ON CONFLICT (business_id, square_timecard_id) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id, work_date = EXCLUDED.work_date, start_at = EXCLUDED.start_at,
                    end_at = EXCLUDED.end_at, timezone = EXCLUDED.timezone,
                    hourly_rate_cents = EXCLUDED.hourly_rate_cents, breaks_json = EXCLUDED.breaks_json,
                    status = EXCLUDED.status, square_version = EXCLUDED.square_version,
                    deleted = FALSE, synced_at = now()
                """,
                businessId, c.path("id").asText(), providerId, java.sql.Date.valueOf(workDate),
                java.sql.Timestamp.from(start), end == null ? null : java.sql.Timestamp.from(end), zone, rate,
                breaks, c.path("status").asText("CLOSED"),
                c.hasNonNull("version") ? c.get("version").asInt() : null);
    }

    private void markMissingDeleted(Long businessId, Instant from, Instant to, Set<String> seen) {
        List<String> inWindow = jdbc.queryForList(
                "SELECT square_timecard_id FROM square_timecard_mirror WHERE business_id = ? AND NOT deleted "
                        + "AND start_at >= ? AND start_at < ?",
                String.class, businessId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        for (String id : inWindow) {
            if (!seen.contains(id)) {
                jdbc.update("UPDATE square_timecard_mirror SET deleted = TRUE, synced_at = now() "
                        + "WHERE business_id = ? AND square_timecard_id = ?", businessId, id);
            }
        }
    }
}
