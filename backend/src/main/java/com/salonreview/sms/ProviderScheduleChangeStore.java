package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.ProviderAvailabilityLossDetector.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Atomic, tenant-scoped observation/candidate/outbox persistence. No network calls in transactions. */
@Service
public class ProviderScheduleChangeStore {
    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext context;
    private final ProviderAvailabilityLossDetector detector;
    private final JsonMapper json = JsonMapper.builder().build();

    public ProviderScheduleChangeStore(JdbcTemplate jdbc, CurrentBusinessContext context,
                                      ProviderAvailabilityLossDetector detector) {
        this.jdbc = jdbc;
        this.context = context;
        this.detector = detector;
    }

    public ProviderAvailabilityObservation latest(Long businessId, String teamId) {
        scoped(businessId);
        var rows = jdbc.query("""
                SELECT o.payload::text FROM provider_availability_state s
                JOIN provider_availability_observation o ON o.business_id = s.business_id AND o.id = s.observation_id
                WHERE s.business_id = ? AND s.team_member_id = ?
                """, (rs, ignored) -> decode(rs.getString(1)), businessId, teamId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public void observe(Long businessId, String providerName, ProviderAvailabilityObservation current) {
        scoped(businessId);
        String teamId = current.contract().teamMemberId();
        lockProvider(businessId, teamId);
        ProviderAvailabilityObservation previous = latest(businessId, teamId);
        if (previous != null && !current.capturedAt().isAfter(previous.capturedAt())) {
            return;
        }
        var initial = previous == null ? new ProviderAvailabilityLossDetector.Result("BASELINE", List.of())
                : detector.detect(previous, current, null);
        long observationId = insertObservation(businessId, teamId, current.capturedAt(), "SUCCESS", initial.reason(), json.writeValueAsString(current));
        Set<LocalDate> handled = new HashSet<>();
        for (Candidate candidate : activeCandidates(businessId, teamId)) {
            if (terminalDelivery(businessId, candidate.id())) {
                handled.add(candidate.date());
                continue;
            }
            boolean expired = "PENDING".equals(candidate.status()) && Duration.between(candidate.firstDetectedAt(), current.capturedAt())
                    .compareTo(ProviderAvailabilityLossDetector.MAX_BASELINE_AGE) > 0;
            var result = detector.detect(candidate.baseline(), current, candidate.firstDetectedAt());
            Window window = result.windows().stream().filter(found -> found.date().equals(candidate.date())
                    && !found.lastStart().isBefore(candidate.firstStart()) && !found.firstStart().isAfter(candidate.lastStart()))
                    .findFirst().orElse(null);
            if (expired || window == null) {
                String status = expired ? "EXPIRED" : "RETURNED_AVAILABILITY".equals(result.reason()) ? "RESOLVED" : "SUPPRESSED";
                updateCandidate(businessId, candidate.id(), status, expired ? "STALE_BASELINE" : result.reason(),
                        current.capturedAt(), observationId, null);
                suppressOutbox(businessId, candidate.id());
                continue;
            }
            handled.add(candidate.date());
            if (Duration.between(candidate.firstDetectedAt(), current.capturedAt())
                    .compareTo(ProviderAvailabilityLossDetector.CONFIRMATION_DELAY) < 0) {
                updateCandidate(businessId, candidate.id(), "PENDING", "WAITING_CONFIRMATION", current.capturedAt(), observationId, window);
                continue;
            }
            String status = current.contract().observationOnly() ? "OBSERVED" : "CONFIRMED";
            updateCandidate(businessId, candidate.id(), status, "LARGE_LOSS", current.capturedAt(), observationId, window);
            if (!current.contract().observationOnly()) enqueue(businessId, candidate.id(), providerName, current, window);
        }
        if (previous != null) for (Window window : initial.windows()) {
            if (handled.contains(window.date())) continue;
            jdbc.update("""
                    INSERT INTO provider_schedule_change_event
                        (business_id, team_member_id, team_member_name, affected_date, status, reason,
                         first_detected_at, updated_at, first_start_at, last_start_at, start_count, baseline, latest_observation_id)
                    VALUES (?, ?, ?, ?, 'PENDING', 'WAITING_CONFIRMATION', ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
                    ON CONFLICT (business_id, team_member_id, affected_date) DO UPDATE
                    SET status = 'PENDING', reason = 'WAITING_CONFIRMATION', first_detected_at = EXCLUDED.first_detected_at,
                        confirmed_at = NULL, updated_at = EXCLUDED.updated_at, first_start_at = EXCLUDED.first_start_at,
                        last_start_at = EXCLUDED.last_start_at, start_count = EXCLUDED.start_count,
                        baseline = EXCLUDED.baseline, latest_observation_id = EXCLUDED.latest_observation_id,
                        team_member_name = EXCLUDED.team_member_name
                    """, businessId, teamId, providerName, window.date(), time(current.capturedAt()), time(current.capturedAt()),
                    time(window.firstStart()), time(window.lastStart()), window.startCount(), json.writeValueAsString(previous), observationId);
        }
        jdbc.update("UPDATE provider_availability_state SET observation_id = ? WHERE business_id = ? AND team_member_id = ?",
                observationId, businessId, teamId);
    }

    @Transactional
    public void failure(Long businessId, String teamId, Instant at, String reason) {
        scoped(businessId);
        lockProvider(businessId, teamId);
        long observationId = insertObservation(businessId, teamId, at, "ERROR", reason, null);
        jdbc.update("""
                UPDATE provider_schedule_change_event SET reason = ?, latest_observation_id = ?, updated_at = ?,
                    status = CASE WHEN first_detected_at < ? THEN 'EXPIRED' ELSE status END
                WHERE business_id = ? AND team_member_id = ? AND status = 'PENDING'
                """, reason, observationId, time(at), time(at.minus(ProviderAvailabilityLossDetector.MAX_BASELINE_AGE)), businessId, teamId);
    }

    private void lockProvider(Long businessId, String teamId) {
        jdbc.update("INSERT INTO provider_availability_state(business_id, team_member_id) VALUES (?, ?) ON CONFLICT DO NOTHING", businessId, teamId);
        jdbc.queryForObject("SELECT business_id FROM provider_availability_state WHERE business_id = ? AND team_member_id = ? FOR UPDATE",
                Long.class, businessId, teamId);
    }

    private long insertObservation(Long businessId, String teamId, Instant at, String status, String reason, String payload) {
        return jdbc.queryForObject("""
                INSERT INTO provider_availability_observation(business_id, team_member_id, captured_at, status, reason, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB)) RETURNING id
                """, Long.class, businessId, teamId, time(at), status, reason, payload);
    }

    private record Candidate(long id, LocalDate date, String status, Instant firstDetectedAt,
                             Instant firstStart, Instant lastStart, ProviderAvailabilityObservation baseline) {}

    private List<Candidate> activeCandidates(Long businessId, String teamId) {
        return jdbc.query("""
                SELECT id, affected_date, status, first_detected_at, first_start_at, last_start_at, baseline::text
                FROM provider_schedule_change_event WHERE business_id = ? AND team_member_id = ?
                AND status IN ('PENDING', 'CONFIRMED', 'OBSERVED')
                """, (rs, ignored) -> new Candidate(rs.getLong("id"), rs.getObject("affected_date", LocalDate.class), rs.getString("status"),
                instant(rs, "first_detected_at"), instant(rs, "first_start_at"), instant(rs, "last_start_at"), decode(rs.getString("baseline"))), businessId, teamId);
    }

    private void updateCandidate(Long businessId, long id, String status, String reason, Instant at, long observationId, Window window) {
        jdbc.update("""
                UPDATE provider_schedule_change_event SET status = ?, reason = ?, updated_at = ?, latest_observation_id = ?,
                    confirmed_at = CASE WHEN ? IN ('CONFIRMED', 'OBSERVED') THEN COALESCE(confirmed_at, ?) ELSE confirmed_at END
                WHERE business_id = ? AND id = ?
                """, status, reason, time(at), observationId, status, time(at), businessId, id);
        if (window != null) jdbc.update("""
                UPDATE provider_schedule_change_event SET first_start_at = ?, last_start_at = ?, start_count = ?
                WHERE business_id = ? AND id = ?
                """, time(window.firstStart()), time(window.lastStart()), window.startCount(), businessId, id);
    }

    private void enqueue(Long businessId, long eventId, String name, ProviderAvailabilityObservation observation, Window window) {
        jdbc.update("""
                INSERT INTO provider_schedule_closure_alert
                    (business_id, team_member_id, team_member_name, slot_count, earliest_slot_at, latest_slot_at,
                     sent_at, event_id, affected_date, delivery_status, confirmed_at, notice_threshold_hours, timezone, minimum_loss_window_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                ON CONFLICT (business_id, team_member_id, affected_date) WHERE affected_date IS NOT NULL DO UPDATE
                SET delivery_status = 'PENDING', confirmed_at = EXCLUDED.confirmed_at, slot_count = EXCLUDED.slot_count,
                    earliest_slot_at = EXCLUDED.earliest_slot_at, latest_slot_at = EXCLUDED.latest_slot_at,
                    team_member_name = EXCLUDED.team_member_name, notice_threshold_hours = EXCLUDED.notice_threshold_hours,
                    minimum_loss_window_minutes = EXCLUDED.minimum_loss_window_minutes, timezone = EXCLUDED.timezone
                WHERE provider_schedule_closure_alert.delivery_status IN ('PENDING', 'FAILED', 'SUPPRESSED')
                    AND provider_schedule_closure_alert.attempts < 3
                """, businessId, observation.contract().teamMemberId(), name, window.startCount(), time(window.firstStart()), time(window.lastStart()),
                time(observation.capturedAt()), eventId, window.date(), time(observation.capturedAt()), observation.contract().noticeThresholdHours(),
                observation.contract().timezone(), observation.contract().minimumLossWindowMinutes());
    }

    private boolean terminalDelivery(Long businessId, long eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM provider_schedule_closure_alert WHERE business_id = ? AND event_id = ?
                    AND (delivery_status IN ('SENT', 'UNKNOWN', 'ATTEMPTING') OR attempts >= 3))
                """, Boolean.class, businessId, eventId));
    }

    private void suppressOutbox(Long businessId, long eventId) {
        jdbc.update("UPDATE provider_schedule_closure_alert SET delivery_status = 'SUPPRESSED' WHERE business_id = ? AND event_id = ? AND delivery_status IN ('PENDING', 'FAILED')",
                businessId, eventId);
    }

    public record PendingAlert(long id, String providerName, Instant firstStart, Instant lastStart,
                               int noticeThresholdHours, int minimumLossWindowMinutes, String timezone) {}

    public List<PendingAlert> pendingAlerts(Long businessId, Instant now) {
        scoped(businessId);
        return jdbc.query("""
                SELECT id, team_member_name, earliest_slot_at, latest_slot_at, notice_threshold_hours,
                    minimum_loss_window_minutes, timezone FROM provider_schedule_closure_alert
                WHERE business_id = ? AND delivery_status = 'PENDING' AND confirmed_at >= ? ORDER BY id LIMIT 50
                """, (rs, ignored) -> new PendingAlert(rs.getLong("id"), rs.getString("team_member_name"), instant(rs, "earliest_slot_at"),
                instant(rs, "latest_slot_at"), rs.getInt("notice_threshold_hours"), rs.getInt("minimum_loss_window_minutes"), rs.getString("timezone")),
                businessId, time(now.minus(Duration.ofMinutes(5))));
    }

    @Transactional
    public boolean claim(Long businessId, long alertId, Instant now) {
        scoped(businessId);
        List<String> teams = jdbc.query("""
                SELECT team_member_id FROM provider_schedule_closure_alert
                WHERE business_id = ? AND id = ? AND delivery_status = 'PENDING'
                """, (rs, ignored) -> rs.getString(1), businessId, alertId);
        if (teams.isEmpty()) return false;
        // Use the same state locks as observation/failure writes, before touching the outbox.
        // A concurrent failed read must commit before this claim evaluates its evidence.
        lockProvider(businessId, "business");
        lockProvider(businessId, teams.getFirst());
        return jdbc.update("""
                UPDATE provider_schedule_closure_alert a SET delivery_status = 'ATTEMPTING', attempted_at = ?, attempts = attempts + 1
                WHERE a.business_id = ? AND a.id = ? AND a.delivery_status = 'PENDING' AND a.confirmed_at >= ?
                AND EXISTS(SELECT 1 FROM provider_schedule_change_event e WHERE e.business_id = a.business_id AND e.id = a.event_id AND e.status = 'CONFIRMED'
                    AND e.first_start_at > CAST(? AS TIMESTAMPTZ)
                        + ((e.baseline->'contract'->>'minBookingLeadTimeSeconds')::BIGINT + ?) * INTERVAL '1 second')
                AND EXISTS(SELECT 1 FROM business b WHERE b.id = a.business_id AND b.active AND b.timezone = a.timezone)
                AND EXISTS(SELECT 1 FROM sms_automation s WHERE s.business_id = a.business_id AND s.automation_key = 'provider_schedule_closure_alert' AND s.enabled)
                AND EXISTS(SELECT 1 FROM provider_schedule_closure_alert_config c WHERE c.business_id = a.business_id
                    AND NOT c.observation_only AND c.notice_threshold_hours = a.notice_threshold_hours
                    AND c.minimum_loss_window_minutes = a.minimum_loss_window_minutes)
                AND NOT EXISTS(SELECT 1 FROM provider_availability_observation o WHERE o.business_id = a.business_id
                    AND (o.team_member_id = a.team_member_id OR o.team_member_id = 'business')
                    AND o.status = 'ERROR' AND o.captured_at >= a.confirmed_at)
                """, time(now), businessId, alertId, time(now.minus(Duration.ofMinutes(5))),
                time(now), ProviderAvailabilityLossDetector.CUTOFF_MARGIN.toSeconds()) == 1;
    }

    @Transactional
    public void delivered(Long businessId, long alertId, String status, Long messageId, Instant now) {
        scoped(businessId);
        if (!Set.of("SENT", "FAILED", "UNKNOWN").contains(status)) throw new IllegalArgumentException("Invalid delivery outcome");
        jdbc.update("""
                UPDATE provider_schedule_closure_alert SET delivery_status = ?, telegram_message_id = ?,
                    delivered_at = CASE WHEN ? = 'SENT' THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END
                WHERE business_id = ? AND id = ? AND delivery_status = 'ATTEMPTING'
                """, status, messageId, status, time(now), businessId, alertId);
    }

    @Transactional
    public void recoverAmbiguousAttempts(Long businessId, Instant now) {
        scoped(businessId);
        jdbc.update("""
                UPDATE provider_schedule_closure_alert SET delivery_status = 'UNKNOWN'
                WHERE business_id = ? AND delivery_status = 'ATTEMPTING' AND attempted_at < ?
                """, businessId, time(now.minus(Duration.ofMinutes(2))));
    }

    public record Activity(long id, String providerName, LocalDate date, String status, String reason,
                           Instant firstStart, Instant lastStart, long windowMinutes, Instant detectedAt,
                           Instant confirmedAt, String deliveryStatus, String timezone) {}

    public record Health(String teamMemberId, Instant checkedAt, String status, String reason) {}
    public record History(List<Activity> events, List<Health> providers) {}

    public History history(Long businessId) {
        scoped(businessId);
        List<Activity> events = jdbc.query("""
                SELECT e.*, a.delivery_status FROM provider_schedule_change_event e
                LEFT JOIN provider_schedule_closure_alert a ON a.business_id = e.business_id AND a.event_id = e.id
                WHERE e.business_id = ? ORDER BY e.updated_at DESC, e.id DESC LIMIT 50
                """, (rs, ignored) -> {
                    var baseline = decode(rs.getString("baseline"));
                    return new Activity(rs.getLong("id"), rs.getString("team_member_name"), rs.getObject("affected_date", LocalDate.class),
                            rs.getString("status"), rs.getString("reason"), instant(rs, "first_start_at"), instant(rs, "last_start_at"),
                            Duration.between(instant(rs, "first_start_at"), instant(rs, "last_start_at")).toMinutes(),
                            instant(rs, "first_detected_at"), instant(rs, "confirmed_at"), rs.getString("delivery_status"), baseline.contract().timezone());
                }, businessId);
        List<Health> health = jdbc.query("""
                SELECT DISTINCT ON (team_member_id) team_member_id, captured_at, status, reason
                FROM provider_availability_observation o WHERE business_id = ?
                AND (team_member_id <> 'business' OR NOT EXISTS(
                    SELECT 1 FROM provider_availability_observation recovered WHERE recovered.business_id = o.business_id
                    AND recovered.status = 'SUCCESS' AND recovered.captured_at > o.captured_at))
                ORDER BY team_member_id, captured_at DESC, id DESC
                """, (rs, ignored) -> new Health(rs.getString("team_member_id"), instant(rs, "captured_at"), rs.getString("status"), rs.getString("reason")), businessId);
        return new History(events, health);
    }

    @Transactional
    public void cleanHistory(Long businessId, Instant now) {
        scoped(businessId);
        jdbc.update("DELETE FROM provider_schedule_closure_alert WHERE business_id = ? AND delivery_status <> 'LEGACY' AND sent_at < ?",
                businessId, time(now.minus(Duration.ofDays(90))));
        jdbc.update("""
                DELETE FROM provider_schedule_change_event e WHERE e.business_id = ? AND e.updated_at < ?
                AND NOT EXISTS(SELECT 1 FROM provider_schedule_closure_alert a WHERE a.business_id = e.business_id AND a.event_id = e.id)
                """, businessId, time(now.minus(Duration.ofDays(90))));
        jdbc.update("""
                DELETE FROM provider_availability_observation o WHERE o.business_id = ? AND o.captured_at < ?
                AND NOT EXISTS(SELECT 1 FROM provider_availability_state s WHERE s.business_id = o.business_id AND s.observation_id = o.id)
                AND NOT EXISTS(SELECT 1 FROM provider_schedule_change_event e WHERE e.business_id = o.business_id AND e.latest_observation_id = o.id)
                """, businessId, time(now.minus(Duration.ofDays(14))));
    }

    private ProviderAvailabilityObservation decode(String value) { return json.readValue(value, ProviderAvailabilityObservation.class); }
    private void scoped(Long businessId) {
        if (!businessId.equals(context.id())) throw new IllegalArgumentException("Schedule business context mismatch");
    }
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
