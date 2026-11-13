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
        List<DayTimecard> cards = c.timecards();
        if (c.source().equals("NONE") || c.source().equals("VOIDED")) {
            if (req.start() == null || req.end() == null) throw bad("No work recorded for this day");
            if (req.note() == null || req.note().isBlank()) throw bad("A note is required for a manually added shift");
            cards = List.of(new DayTimecard(new TimeSpan(Instant.parse(req.start()), Instant.parse(req.end())), List.of()));
        }
        if ("APPROVED".equals(c.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This day is already approved. Ask your manager to reopen it or to adjust the hours");
        }
        if (c.source().equals("SQUARE") && (req.start() != null || req.end() != null)) {
            TimeSpan sq = cards.get(0).span();
            boolean same = (req.start() == null || Instant.parse(req.start()).equals(sq.start()))
                    && (req.end() == null || Instant.parse(req.end()).equals(sq.end()));
            if (!same) throw bad("These hours come from Square. Ask your manager to adjust them");
        }

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

    /** Worker cancels a shift they added by hand (no appointments behind it) that is not approved yet. */
    public DayView withdraw(Long businessId, Long providerId, String dateStr, String reason) {
        requireW2(businessId, providerId);
        LocalDate date = parseDate(dateStr);
        DayCalc c = days.calc(businessId, providerId, date);
        if (c.source().equals("SQUARE")) throw bad("This shift is already in Square. Ask your manager to remove it");
        if (!c.source().equals("DRAFT") || !c.appointments().isEmpty()) {
            throw bad("Only a shift you added by hand can be cancelled. Ask your manager for other days");
        }
        if ("APPROVED".equals(c.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Day already approved");
        voidReview(businessId, providerId, date, "provider:" + providerId, reason, "WORKER_VOID");
        return days.view(businessId, providerId, date, false);
    }

    // ---- approver ----

    /** Approver cancels a day that has no Square timecard: it leaves payroll and reminders but stays in the audit trail. */
    public DayView voidDay(Long businessId, Long userId, String role, Long providerId, LocalDate date, String reason) {
        requireW2(businessId, providerId);
        requireApprover(businessId, userId, role, providerId);
        DayCalc c = days.calc(businessId, providerId, date);
        if (c.source().equals("SQUARE")) throw bad("This shift is a Square timecard. Delete it in Square and it disappears here");
        if (c.source().equals("NONE") || c.source().equals("VOIDED")) throw bad("Nothing to cancel on this day");
        voidReview(businessId, providerId, date, "user:" + userId, reason, "VOID");
        return days.view(businessId, providerId, date, "OWNER".equals(role));
    }

    public DayView restoreDay(Long businessId, Long userId, String role, Long providerId, LocalDate date) {
        requireApprover(businessId, userId, role, providerId);
        DayCalc c = days.calc(businessId, providerId, date);
        if (!"VOIDED".equals(c.status())) throw bad("This day is not cancelled");
        long id = ensureReview(businessId, providerId, date, "DRAFT");
        String before = snapshot(id);
        jdbc.update("UPDATE timecard_review SET status = 'NEEDS_REVIEW', voided_at = NULL, voided_by = NULL, "
                + "void_reason = NULL WHERE id = ? AND business_id = ?", id, businessId);
        audit(businessId, id, "user:" + userId, "RESTORE", before, snapshot(id));
        return days.view(businessId, providerId, date, "OWNER".equals(role));
    }

    private void voidReview(Long businessId, Long providerId, LocalDate date, String actor, String reason, String action) {
        if (reason == null || reason.isBlank()) throw bad("Give a short reason");
        if (reason.length() > 300) throw bad("Reason is too long");
        long id = ensureReview(businessId, providerId, date, "DRAFT");
        String before = snapshot(id);
        jdbc.update("UPDATE timecard_review SET status = 'VOIDED', voided_at = now(), voided_by = ?, void_reason = ? "
                + "WHERE id = ? AND business_id = ?", actor, reason.trim(), id, businessId);
        audit(businessId, id, actor, action, before, snapshot(id));
    }

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

    /**
     * Approver corrects the start/end of a day. A Square day is changed in Square itself (the timecard is the
     * source of truth for pay) and mirrored back; a draft day just changes its proposed hours. The day returns
     * to NEEDS_REVIEW so the approver checks the findings and approves again.
     */
    public DayView adjust(Long businessId, Long userId, String role, Long providerId, LocalDate date,
                          String startIso, String endIso, String note) {
        return adjust(businessId, userId, role, providerId, date, startIso, endIso, note, null);
    }

    /** unpaidBreaks, when not null, replaces every unpaid break of the day (empty list = no meal); paid rests stay. */
    public DayView adjust(Long businessId, Long userId, String role, Long providerId, LocalDate date,
                          String startIso, String endIso, String note, List<TimeSpan> unpaidBreaks) {
        requireW2(businessId, providerId);
        requireApprover(businessId, userId, role, providerId);
        if (date.isAfter(LocalDate.now(TimecardDayService.RULES.zone()))) throw bad("Cannot change a future day");
        if (note == null || note.isBlank()) throw bad("A short reason is required");
        if (note.length() > 300) throw bad("Reason is too long");
        if (startIso == null || endIso == null) throw bad("Start and end are required");
        Instant start;
        Instant end;
        try {
            start = Instant.parse(startIso);
            end = Instant.parse(endIso);
        } catch (java.time.format.DateTimeParseException e) {
            throw bad("Invalid time");
        }
        if (!end.isAfter(start)) throw bad("End must be after start");
        if (java.time.Duration.between(start, end).toHours() > 16) throw bad("A day cannot be longer than 16 hours");
        if (!start.atZone(TimecardDayService.RULES.zone()).toLocalDate().equals(date)) {
            throw bad("Start must be on the selected day");
        }
        if (unpaidBreaks != null) validateBreaks(unpaidBreaks, start, end);
        DayCalc c = days.calc(businessId, providerId, date);
        if (c.source().equals("NONE") || c.source().equals("VOIDED")) throw bad("There is no shift to adjust on this day");
        long id = ensureReview(businessId, providerId, date, c.source().equals("SQUARE") ? "EXTERNAL" : "DRAFT");
        String before = snapshot(id);
        if (c.source().equals("SQUARE")) {
            if (c.timecards().size() != 1) throw bad("This day has several Square timecards. Change it in Square");
            List<Object[]> rows = jdbc.query("SELECT square_timecard_id, square_version, breaks_json, timezone, "
                            + "hourly_rate_cents FROM square_timecard_mirror WHERE business_id = ? AND provider_id = ? "
                            + "AND work_date = ? AND NOT deleted",
                    (rs, i) -> new Object[] {rs.getString(1), rs.getObject(2), rs.getString(3), rs.getString(4), rs.getObject(5)},
                    businessId, providerId, java.sql.Date.valueOf(date));
            if (rows.size() != 1 || rows.get(0)[1] == null) throw bad("Square timecard is not synced yet. Try again in a minute");
            Object[] row = rows.get(0);
            SquareConnection conn = connections.findByBusinessId(businessId)
                    .orElseThrow(() -> bad("Square is not connected"));
            List<String> members = days.memberIds(providerId);
            if (members.isEmpty()) throw bad("Provider is not linked to a Square team member");
            ObjectNode tc = MAPPER.createObjectNode();
            tc.put("location_id", conn.getLocationId());
            tc.put("team_member_id", members.get(0));
            tc.put("start_at", start.toString());
            tc.put("end_at", end.toString());
            tc.put("timezone", (String) row[3]);
            tc.put("version", ((Number) row[1]).intValue());
            if (row[4] != null) {
                ObjectNode wage = tc.putObject("wage");
                wage.put("title", "Nail technician");
                ObjectNode rate = wage.putObject("hourly_rate");
                rate.put("amount", ((Number) row[4]).intValue());
                rate.put("currency", "USD");
            }
            try {
                JsonNode breaks = MAPPER.readTree((String) row[2]);
                if (unpaidBreaks != null && breaks.isArray()) {
                    ArrayNode kept = MAPPER.createArrayNode();
                    for (JsonNode b : breaks) {
                        if (b.path("is_paid").asBoolean(false)) kept.add(b);
                    }
                    if (!unpaidBreaks.isEmpty()) {
                        String unpaidType = breakTypeId(businessId, conn.getLocationId(), UNPAID_BREAK, 30, false);
                        for (TimeSpan u : unpaidBreaks) {
                            ObjectNode bn = kept.addObject();
                            bn.put("start_at", u.start().toString());
                            bn.put("end_at", u.end().toString());
                            bn.put("break_type_id", unpaidType);
                            bn.put("name", UNPAID_BREAK);
                            bn.put("expected_duration", "PT" + u.minutes() + "M");
                            bn.put("is_paid", false);
                        }
                    }
                    breaks = kept;
                }
                tc.set("breaks", breaks);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw bad("Square timecard breaks could not be read");
            }
            JsonNode updated;
            try {
                updated = labor.updateTimecard(businessId, (String) row[0], tc);
            } catch (RuntimeException e) {
                log.warn("Square timecard update failed for provider {} on {}: {}", providerId, date, e.toString());
                throw bad("Square did not accept the change. Check that breaks fit inside the hours, or change it in Square");
            }
            sync.upsert(businessId, providerId, updated);
        } else {
            List<DayTimecard> cards = applyEdits(c.timecards(), new SubmitRequest(date.toString(), start.toString(),
                    end.toString(), null, null, null, null, null));
            if (unpaidBreaks != null) {
                DayTimecard only = cards.get(0);
                List<TimecardBreak> merged = new ArrayList<>(only.breaks().stream().filter(b -> b.kind() != BreakKind.UNPAID).toList());
                for (TimeSpan u : unpaidBreaks) merged.add(new TimecardBreak(u, BreakKind.UNPAID));
                merged.sort(Comparator.comparing(b -> b.span().start()));
                cards = List.of(new DayTimecard(only.span(), merged));
            }
            jdbc.update("UPDATE timecard_review SET proposed_json = ? WHERE id = ? AND business_id = ?",
                    TimecardDayService.proposedJson(cards), id, businessId);
        }
        jdbc.update("UPDATE timecard_review SET status = 'NEEDS_REVIEW', approved_at = NULL, approved_by_user_id = NULL, "
                + "note = ? WHERE id = ? AND business_id = ?", note.trim(), id, businessId);
        audit(businessId, id, "user:" + userId, "ADJUST", before, snapshot(id));
        return days.view(businessId, providerId, date, "OWNER".equals(role));
    }

    static void validateBreaks(List<TimeSpan> breaks, Instant start, Instant end) {
        if (breaks.size() > 8) throw bad("Too many breaks");
        List<TimeSpan> sorted = breaks.stream().sorted(Comparator.comparing(TimeSpan::start)).toList();
        Instant prevEnd = null;
        for (TimeSpan b : sorted) {
            if (b.start().isBefore(start) || b.end().isAfter(end)) throw bad("Breaks must be inside the work day");
            if (prevEnd != null && b.start().isBefore(prevEnd)) throw bad("Breaks must not overlap");
            prevEnd = b.end();
        }
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
                + "rest_confirmed, rest_not_provided, note, square_timecard_id, square_version, void_reason FROM timecard_review "
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
