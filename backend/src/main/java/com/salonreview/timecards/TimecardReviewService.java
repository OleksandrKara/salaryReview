package com.salonreview.timecards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import com.salonreview.square.SquareLaborClient;
import com.salonreview.timecards.TimecardDayService.DayCalc;
import com.salonreview.timecards.TimecardDtos.*;
import com.salonreview.timecards.TimecardTypes.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Worker confirmation, approver sign-off, Square write and the audit trail. */
@Service
public class TimecardReviewService {

    private static final Logger log = LoggerFactory.getLogger(TimecardReviewService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String UNPAID_BREAK = "Unpaid break";
    static final String PAID_REST = "Paid rest 10 min";

    private final JdbcTemplate jdbc;
    private final TimecardDayService days;
    private final SquareLaborClient labor;
    private final SquareConnectionRepository connections;
    private final TimecardSyncService sync;

    public TimecardReviewService(JdbcTemplate jdbc, TimecardDayService days, SquareLaborClient labor,
                                 SquareConnectionRepository connections, TimecardSyncService sync) {
        this.jdbc = jdbc;
        this.days = days;
        this.labor = labor;
        this.connections = connections;
        this.sync = sync;
    }

    // ---- worker ----

    public DayView submit(Long businessId, Long providerId, SubmitRequest req) {
        requireW2(businessId, providerId);
        LocalDate date = parseDate(req.date());
        if (date.isAfter(LocalDate.now(TimecardDayService.RULES.zone()))) {
            throw bad("Cannot confirm a future day");
        }
        DayCalc c = days.calc(businessId, providerId, date);
        if (c.source().equals("NONE")) throw bad("No work recorded for this day");
        if ("APPROVED".equals(c.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Day already approved");

        List<DayTimecard> cards = c.timecards();
        String mode = c.source().equals("SQUARE") ? "EXTERNAL" : "DRAFT";
        String proposed = null;
        if (mode.equals("DRAFT")) {
            cards = applyEdits(cards, req);
            proposed = TimecardDayService.proposedJson(cards);
        }
        long paid = days.paidMinutesOf(cards, date, c.rateCents());
        boolean waiver = Boolean.TRUE.equals(req.mealWaiver());
        if (waiver) {
            if (!days.activeWaiver(businessId, providerId)) throw bad("Sign the meal waiver first");
            if (paid > TimecardDayService.RULES.mealWaiverMaxMinutes()) throw bad("A meal waiver is only allowed on days up to 6 hours");
        }
        boolean rests = Boolean.TRUE.equals(req.restsTaken());
        long id = ensureReview(businessId, providerId, date, mode);
        String before = snapshot(id);
        jdbc.update("UPDATE timecard_review SET mode = ?, status = 'WORKER_CONFIRMED', proposed_json = ?, meal_waiver = ?, "
                        + "rest_confirmed = ?, note = ?, worker_confirmed_at = now() WHERE id = ? AND business_id = ?",
                mode, proposed, waiver, rests, req.note(), id, businessId);
        audit(businessId, id, "provider:" + providerId, "WORKER_CONFIRM", before, snapshot(id));
        return days.view(businessId, providerId, date, false);
    }

    List<DayTimecard> applyEdits(List<DayTimecard> cards, SubmitRequest req) {
        if (cards.isEmpty()) return cards;
        DayTimecard first = cards.get(0);
        Instant start = req.start() == null ? first.span().start() : Instant.parse(req.start());
        Instant end = req.end() == null ? first.span().end() : Instant.parse(req.end());
        if (!end.isAfter(start)) throw bad("End must be after start");
        if (java.time.Duration.between(start, end).toHours() > 16) throw bad("A day cannot be longer than 16 hours");
        TimeSpan span = new TimeSpan(start, end);
        List<TimecardBreak> breaks = new ArrayList<>();
        for (TimecardBreak b : first.breaks()) {
            if (!b.span().start().isBefore(start) && !b.span().end().isAfter(end)) breaks.add(b);
        }
        if (req.mealStart() != null && req.mealEnd() != null) {
            Instant ms = Instant.parse(req.mealStart());
            Instant me = Instant.parse(req.mealEnd());
            if (!me.isAfter(ms) || ms.isBefore(start) || me.isAfter(end)) throw bad("Meal must be inside the work day");
            TimeSpan meal = new TimeSpan(ms, me);
            breaks.removeIf(b -> b.kind() == BreakKind.UNPAID && b.span().overlaps(meal));
            breaks.add(new TimecardBreak(meal, BreakKind.UNPAID));
        }
        breaks.sort(Comparator.comparing(b -> b.span().start()));
        return List.of(new DayTimecard(span, breaks));
    }

    // ---- approver ----

    public DayView approve(Long businessId, Long userId, String role, Long providerId, LocalDate date, boolean onBehalf) {
        requireW2(businessId, providerId);
        requireApprover(businessId, userId, role, providerId);
        DayCalc c = days.calc(businessId, providerId, date);
        if (c.source().equals("NONE")) throw bad("No work recorded for this day");
        if (c.rateCents() == null) throw bad("No hourly rate set for this day");
        String status = c.status();
        if (c.source().equals("DRAFT") && !"WORKER_CONFIRMED".equals(status) && !"CONFIRMED_PENDING_WRITE".equals(status)
                && !onBehalf) {
            throw bad("The worker has not confirmed this day yet");
        }
        long id = ensureReview(businessId, providerId, date, c.source().equals("SQUARE") ? "EXTERNAL" : "DRAFT");
        String before = snapshot(id);
        String squareId = null;
        Integer version = null;
        boolean writeFailed = false;
        if (c.source().equals("DRAFT")) {
            try {
                JsonNode created = writeToSquare(businessId, providerId, c);
                squareId = created.path("id").asText(null);
                version = created.hasNonNull("version") ? created.get("version").asInt() : null;
                sync.upsert(businessId, providerId, created);
            } catch (RuntimeException e) {
                log.warn("Square timecard write failed for provider {} on {}: {}", providerId, date, e.toString());
                writeFailed = true;
            }
        } else {
            List<Object[]> v = jdbc.query("SELECT square_timecard_id, square_version FROM square_timecard_mirror "
                            + "WHERE business_id = ? AND provider_id = ? AND work_date = ? AND NOT deleted ORDER BY start_at",
                    (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)},
                    businessId, providerId, java.sql.Date.valueOf(date));
            if (!v.isEmpty()) {
                squareId = (String) v.get(0)[0];
                version = v.stream().mapToInt(o -> (Integer) o[1]).max().orElse(0);
            }
        }
        if (writeFailed) {
            jdbc.update("UPDATE timecard_review SET status = 'CONFIRMED_PENDING_WRITE', approved_by_user_id = ?, approved_at = now() "
                    + "WHERE id = ? AND business_id = ?", userId, id, businessId);
        } else {
            jdbc.update("UPDATE timecard_review SET status = 'APPROVED', approved_by_user_id = ?, approved_at = now(), "
                            + "square_timecard_id = ?, square_version = ? WHERE id = ? AND business_id = ?",
                    userId, squareId, version, id, businessId);
        }
        audit(businessId, id, "user:" + userId, onBehalf ? "APPROVE_ON_BEHALF" : "APPROVE", before, snapshot(id));
        return days.view(businessId, providerId, date, "OWNER".equals(role));
    }

    public DayView reopen(Long businessId, Long userId, String role, Long providerId, LocalDate date) {
        requireApprover(businessId, userId, role, providerId);
        long id = ensureReview(businessId, providerId, date, "DRAFT");
        String before = snapshot(id);
        jdbc.update("UPDATE timecard_review SET status = 'NEEDS_REVIEW' WHERE id = ? AND business_id = ?", id, businessId);
        audit(businessId, id, "user:" + userId, "REOPEN", before, snapshot(id));
        return days.view(businessId, providerId, date, "OWNER".equals(role));
    }

    /** Approver records that the required paid rests were not provided (premium owed) or clears it. */
    public DayView markRestNotProvided(Long businessId, Long userId, String role, Long providerId, LocalDate date,
                                       boolean notProvided) {
        requireApprover(businessId, userId, role, providerId);
        long id = ensureReview(businessId, providerId, date, "DRAFT");
        String before = snapshot(id);
        jdbc.update("UPDATE timecard_review SET rest_not_provided = ? WHERE id = ? AND business_id = ?",
                notProvided, id, businessId);
        audit(businessId, id, "user:" + userId, "REST_NOT_PROVIDED_" + notProvided, before, snapshot(id));
        return days.view(businessId, providerId, date, "OWNER".equals(role));
    }

    boolean canApprove(Long businessId, Long userId, String role, Long providerId) {
        if ("OWNER".equals(role)) return true;
        if (!"MANAGER".equals(role)) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM provider_time_approver WHERE business_id = ? "
                + "AND provider_id = ? AND app_user_id = ?", Integer.class, businessId, providerId, userId);
        return n != null && n > 0;
    }

    private void requireApprover(Long businessId, Long userId, String role, Long providerId) {
        if (!canApprove(businessId, userId, role, providerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an approver for this worker");
        }
    }

    // ---- waiver ----

    public WaiverStatus waiver(Long businessId, Long providerId) {
        String name = employerName(businessId);
        List<WaiverStatus> r = jdbc.query("SELECT signed_at, signed_name, text_version, text_body FROM meal_waiver_agreement "
                        + "WHERE business_id = ? AND provider_id = ? AND revoked_at IS NULL ORDER BY signed_at DESC LIMIT 1",
                (rs, i) -> new WaiverStatus(true, rs.getTimestamp(1).toInstant().toString(), rs.getString(2),
                        rs.getString(3), rs.getString(4)),
                businessId, providerId);
        if (!r.isEmpty()) return r.get(0);
        return new WaiverStatus(false, null, null, MealWaiverText.VERSION, MealWaiverText.body(name));
    }

    public WaiverStatus signWaiver(Long businessId, Long providerId, String signedName, String ip) {
        requireW2(businessId, providerId);
        if (signedName == null || signedName.trim().length() < 3) throw bad("Type your full name to sign");
        if (waiver(businessId, providerId).signed()) return waiver(businessId, providerId);
        jdbc.update("INSERT INTO meal_waiver_agreement (business_id, provider_id, text_version, text_body, signed_name, "
                        + "signed_via, signed_ip) VALUES (?, ?, ?, ?, ?, 'WEB', ?)",
                businessId, providerId, MealWaiverText.VERSION, MealWaiverText.body(employerName(businessId)),
                signedName.trim(), ip);
        return waiver(businessId, providerId);
    }

    public WaiverStatus revokeWaiver(Long businessId, Long providerId, String note) {
        jdbc.update("UPDATE meal_waiver_agreement SET revoked_at = now(), revoked_note = ? WHERE business_id = ? "
                + "AND provider_id = ? AND revoked_at IS NULL", note, businessId, providerId);
        return waiver(businessId, providerId);
    }

    private String employerName(Long businessId) {
        List<String> n = jdbc.queryForList("SELECT name FROM business WHERE id = ?", String.class, businessId);
        return n.isEmpty() ? "the salon" : n.get(0);
    }

    // ---- Square write ----

    JsonNode writeToSquare(Long businessId, Long providerId, DayCalc c) {
        SquareConnection conn = connections.findByBusinessId(businessId)
                .orElseThrow(() -> bad("Square is not connected"));
        List<String> members = days.memberIds(providerId);
        if (members.isEmpty()) throw bad("Provider is not linked to a Square team member");
        String location = conn.getLocationId();
        String unpaidType = breakTypeId(businessId, location, UNPAID_BREAK, 30, false);
        DayTimecard card = c.timecards().get(0);
        ObjectNode tc = MAPPER.createObjectNode();
        tc.put("location_id", location);
        tc.put("team_member_id", members.get(0));
        tc.put("start_at", card.span().start().toString());
        tc.put("end_at", card.span().end().toString());
        tc.put("timezone", TimecardDayService.RULES.zone().getId());
        ObjectNode wage = tc.putObject("wage");
        wage.put("title", "Nail technician");
        ObjectNode rate = wage.putObject("hourly_rate");
        rate.put("amount", c.rateCents());
        rate.put("currency", "USD");
        ArrayNode bs = tc.putArray("breaks");
        for (TimecardBreak b : card.breaks()) {
            if (b.kind() != BreakKind.UNPAID) continue;
            ObjectNode bn = bs.addObject();
            bn.put("start_at", b.span().start().toString());
            bn.put("end_at", b.span().end().toString());
            bn.put("break_type_id", unpaidType);
            bn.put("name", UNPAID_BREAK);
            bn.put("expected_duration", "PT" + b.span().minutes() + "M");
            bn.put("is_paid", false);
        }
        return labor.createTimecard(businessId, tc);
    }

    private String breakTypeId(Long businessId, String location, String name, int minutes, boolean paid) {
        for (JsonNode bt : labor.listBreakTypes(businessId, location)) {
            if (name.equals(bt.path("break_name").asText())) return bt.path("id").asText();
        }
        return labor.createBreakType(businessId, location, name, minutes, paid).path("id").asText();
    }

    // ---- helpers ----

    private void requireW2(Long businessId, Long providerId) {
        List<String> t = jdbc.queryForList("SELECT worker_type FROM providers WHERE id = ? AND business_id = ?",
                String.class, providerId, businessId);
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found");
        if (!"W2".equals(t.get(0))) throw bad("This provider is not a W2 employee");
    }

    long ensureReview(Long businessId, Long providerId, LocalDate date, String mode) {
        jdbc.update("INSERT INTO timecard_review (business_id, provider_id, work_date, mode, status) "
                        + "VALUES (?, ?, ?, ?, 'PROPOSED') ON CONFLICT (business_id, provider_id, work_date) DO NOTHING",
                businessId, providerId, java.sql.Date.valueOf(date), mode);
        return jdbc.queryForObject("SELECT id FROM timecard_review WHERE business_id = ? AND provider_id = ? AND work_date = ?",
                Long.class, businessId, providerId, java.sql.Date.valueOf(date));
    }

    private String snapshot(long reviewId) {
        List<String> r = jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT mode, status, proposed_json, meal_waiver, "
                + "rest_confirmed, rest_not_provided, note, square_timecard_id, square_version FROM timecard_review "
                + "WHERE id = ?) t", String.class, reviewId);
        return r.isEmpty() ? null : r.get(0);
    }

    private void audit(Long businessId, long reviewId, String actor, String action, String before, String after) {
        jdbc.update("INSERT INTO timecard_audit (business_id, review_id, actor, action, before_json, after_json) "
                + "VALUES (?, ?, ?, ?, ?, ?)", businessId, reviewId, actor, action, before, after);
    }

    static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw bad("Invalid date");
        }
    }

    static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
