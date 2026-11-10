package com.salonreview.timecards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salonreview.timecards.TimecardDtos.*;
import com.salonreview.timecards.TimecardTypes.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds the day picture for one provider: source timecards or a draft, review state, policy findings. */
@Service
public class TimecardDayService {

    static final CaWageRules RULES = CaWageRules.defaults();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final CaWagePolicy policy;
    private final TimecardDraftCalculator draftCalculator = new TimecardDraftCalculator(RULES);

    public TimecardDayService(JdbcTemplate jdbc,
                              @Value("${timecards.split-shift.skip-premium:false}") boolean skipSplitShiftPremium) {
        this.jdbc = jdbc;
        this.policy = new CaWagePolicy(RULES.withSkipSplitShiftPremium(skipSplitShiftPremium));
    }

    public record ReviewRow(long id, String mode, String status, String proposedJson, boolean mealWaiver,
                            boolean restConfirmed, boolean restNotProvided, String note, Integer squareVersion,
                            String voidReason) {}

    /** Everything the policy and the summary need for one day. */
    public record DayCalc(LocalDate date, Long providerId, String source, List<DayTimecard> timecards,
                          List<DraftAppointment> appointments, DayAnalysis analysis, Integer rateCents,
                          ReviewRow review, String status, boolean changedInSquare, boolean waiverOnFile) {
        public long paidMinutes() {
            return analysis == null ? 0 : analysis.paidMinutes();
        }
    }

    public DayCalc calc(Long businessId, Long providerId, LocalDate date) {
        ReviewRow review = review(businessId, providerId, date).orElse(null);
        Integer rate = rateOn(businessId, providerId, date);
        List<String> members = memberIds(providerId);
        List<DraftAppointment> appts = appointments(businessId, members, date);

        List<DayTimecard> cards = squareCards(businessId, providerId, date);
        String source;
        if (!cards.isEmpty()) {
            source = "SQUARE";
        } else if (review != null && "VOIDED".equals(review.status())) {
            source = "VOIDED";
        } else if (review != null && review.proposedJson() != null) {
            cards = parseProposed(review.proposedJson());
            source = "DRAFT";
        } else {
            Optional<TimecardDraft> draft = draftCalculator.derive(date, appts);
            if (draft.isPresent()) {
                cards = List.of(draft.get().timecard());
                source = "DRAFT";
            } else {
                source = "NONE";
            }
        }

        boolean mealWaiver = review != null && review.mealWaiver();
        boolean restNotProvided = review != null && review.restNotProvided();
        DayAnalysis analysis = null;
        if (!cards.isEmpty()) {
            int r = rate == null ? 0 : rate;
            LocalDate ack = freeGapAckSignedAt(providerId);
            analysis = policy.analyzeDay(new DayInput(date, cards, mealWaiver, restNotProvided, 0, ack), r);
            if (review != null && review.restConfirmed() && analysis.restsRequired() > analysis.restsEvidenced()) {
                analysis = policy.analyzeDay(new DayInput(date, cards, mealWaiver, restNotProvided,
                        analysis.restsRequired() - analysis.restsEvidenced(), ack), r);
            }
        }

        boolean changed = false;
        String status = review == null ? (source.equals("NONE") ? "NONE" : "PROPOSED") : review.status();
        if ("VOIDED".equals(status) && "SQUARE".equals(source)) {
            status = "NEEDS_REVIEW";
        }
        if (review != null && "APPROVED".equals(status) && review.squareVersion() != null && "SQUARE".equals(source)) {
            Integer maxV = jdbc.queryForObject("SELECT COALESCE(MAX(square_version), 0) FROM square_timecard_mirror "
                    + "WHERE business_id = ? AND provider_id = ? AND work_date = ? AND NOT deleted",
                    Integer.class, businessId, providerId, java.sql.Date.valueOf(date));
            if (maxV != null && !maxV.equals(review.squareVersion())) {
                changed = true;
                status = "NEEDS_REVIEW";
            }
        }
        return new DayCalc(date, providerId, source, cards, appts, analysis, rate, review, status, changed,
                activeWaiver(businessId, providerId));
    }

    public DayView view(Long businessId, Long providerId, LocalDate date, boolean money) {
        DayCalc c = calc(businessId, providerId, date);
        String name = jdbc.queryForObject("SELECT display_name FROM providers WHERE id = ? AND business_id = ?",
                String.class, providerId, businessId);
        DayAnalysis a = c.analysis();
        long paid = c.paidMinutes();
        DayPay pay = policy.dailyPay(date, paid);
        List<FindingDto> findings = new ArrayList<>();
        int premium = 0;
        if (a != null) {
            for (Finding f : a.findings()) {
                findings.add(new FindingDto(f.code().name(), f.code().severity().name(), f.minutes(),
                        money ? f.message() : f.messageNoMoney()));
            }
            int rate = c.rateCents() == null ? 0 : c.rateCents();
            if (a.mealPremium()) premium += CaWagePolicy.mealPremiumCents(rate);
            if (a.restPremium()) premium += CaWagePolicy.mealPremiumCents(rate);
            for (Finding f : a.findings()) {
                if (f.code() == FindingCode.SPLIT_SHIFT_PREMIUM_OWED) premium += (int) f.minutes();
            }
        }
        ReviewRow rv = c.review();
        Integer payCents = c.rateCents() == null ? null : CaWagePolicy.cents(pay, c.rateCents());
        return new DayView(date.toString(), providerId, name, c.source(), c.status(), c.changedInSquare(),
                cardDtos(c.timecards()), apptDtos(c.appointments()), paid, pay.regularMinutes(),
                pay.overtimeMinutes(), pay.doubleTimeMinutes(),
                rv != null && rv.mealWaiver(), rv != null && rv.restConfirmed(), rv != null && rv.restNotProvided(),
                c.waiverOnFile(), rv == null ? null : rv.note(), findings,
                money ? c.rateCents() : null, money ? payCents : null, money ? premium : null,
                rv == null ? null : rv.voidReason());
    }

    // ---- loading ----

    public Optional<ReviewRow> review(Long businessId, Long providerId, LocalDate date) {
        List<ReviewRow> rows = jdbc.query("SELECT id, mode, status, proposed_json, meal_waiver, rest_confirmed, "
                        + "rest_not_provided, note, square_version, void_reason FROM timecard_review "
                        + "WHERE business_id = ? AND provider_id = ? AND work_date = ?",
                (rs, i) -> new ReviewRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getString(8),
                        (Integer) rs.getObject(9), rs.getString(10)),
                businessId, providerId, java.sql.Date.valueOf(date));
        return rows.stream().findFirst();
    }

    public Integer rateOn(Long businessId, Long providerId, LocalDate date) {
        List<Integer> r = jdbc.queryForList("SELECT hourly_rate_cents FROM provider_employment "
                        + "WHERE business_id = ? AND provider_id = ? AND effective_from <= ? "
                        + "AND (effective_to IS NULL OR effective_to >= ?) ORDER BY effective_from DESC LIMIT 1",
                Integer.class, businessId, providerId, java.sql.Date.valueOf(date), java.sql.Date.valueOf(date));
        return r.isEmpty() ? null : r.get(0);
    }

    /** Paid minutes of a candidate set of timecards (used to validate worker edits). */
    public long paidMinutesOf(List<DayTimecard> cards, LocalDate date, Integer rateCents) {
        return policy.analyzeDay(DayInput.of(date, cards), rateCents == null ? 0 : rateCents).paidMinutes();
    }

    public LocalDate freeGapAckSignedAt(Long providerId) {
        List<java.sql.Date> r = jdbc.queryForList("SELECT free_gap_ack_signed_at FROM providers WHERE id = ?",
                java.sql.Date.class, providerId);
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toLocalDate();
    }

    public boolean activeWaiver(Long businessId, Long providerId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM meal_waiver_agreement WHERE business_id = ? "
                + "AND provider_id = ? AND revoked_at IS NULL", Integer.class, businessId, providerId);
        return n != null && n > 0;
    }

    List<String> memberIds(Long providerId) {
        return jdbc.queryForList("SELECT square_team_member_id FROM provider_square_member WHERE provider_id = ?",
                String.class, providerId);
    }

    private List<DayTimecard> squareCards(Long businessId, Long providerId, LocalDate date) {
        List<DayTimecard> out = new ArrayList<>();
        jdbc.query("SELECT start_at, end_at, breaks_json FROM square_timecard_mirror WHERE business_id = ? "
                        + "AND provider_id = ? AND work_date = ? AND NOT deleted AND end_at IS NOT NULL ORDER BY start_at",
                rs -> {
                    Instant s = rs.getTimestamp(1).toInstant();
                    Instant e = rs.getTimestamp(2).toInstant();
                    if (e.isBefore(s)) return;
                    List<TimecardBreak> breaks = new ArrayList<>();
                    try {
                        for (JsonNode b : MAPPER.readTree(rs.getString(3))) {
                            if (!b.hasNonNull("start_at") || !b.hasNonNull("end_at")) continue;
                            Instant bs = Instant.parse(b.get("start_at").asText());
                            Instant be = Instant.parse(b.get("end_at").asText());
                            if (be.isBefore(bs)) continue;
                            breaks.add(new TimecardBreak(new TimeSpan(bs, be),
                                    b.path("is_paid").asBoolean(false) ? BreakKind.PAID_REST : BreakKind.UNPAID));
                        }
                    } catch (Exception ignored) {
                        // a malformed breaks blob is treated as no breaks; the day then surfaces meal findings
                    }
                    out.add(new DayTimecard(new TimeSpan(s, e), breaks));
                }, businessId, providerId, java.sql.Date.valueOf(date));
        return out;
    }

    List<DraftAppointment> appointments(Long businessId, List<String> memberIds, LocalDate date) {
        if (memberIds.isEmpty()) return List.of();
        ZoneId zone = RULES.zone();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT start_at, status, appointment_segments_json::text AS seg "
                + "FROM square_booking WHERE business_id = ? AND start_at >= ? AND start_at < ? ORDER BY start_at",
                businessId, Timestamp.from(from), Timestamp.from(to));
        List<DraftAppointment> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r.get("seg") == null) continue;
            Instant start = ((Timestamp) r.get("start_at")).toInstant();
            String st = String.valueOf(r.get("status"));
            AppointmentStatus status = "ACCEPTED".equals(st) ? AppointmentStatus.ACCEPTED
                    : "NO_SHOW".equals(st) ? AppointmentStatus.NO_SHOW : AppointmentStatus.CANCELLED;
            try {
                long offset = 0;
                for (JsonNode seg : MAPPER.readTree((String) r.get("seg"))) {
                    long dur = seg.path("durationMinutes").asLong(0);
                    if (memberIds.contains(seg.path("teamMemberId").asText())) {
                        Instant s = start.plusSeconds(offset * 60);
                        out.add(new DraftAppointment(new TimeSpan(s, s.plusSeconds(dur * 60)), status));
                    }
                    offset += dur;
                }
            } catch (Exception ignored) {
                // skip a booking with unreadable segments
            }
        }
        out.sort(Comparator.comparing(a -> a.span().start()));
        return out;
    }

    // ---- proposed json ----

    static String proposedJson(List<DayTimecard> cards) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("timecards");
        for (DayTimecard c : cards) {
            ObjectNode n = arr.addObject();
            n.put("start", c.span().start().toString());
            n.put("end", c.span().end().toString());
            ArrayNode bs = n.putArray("breaks");
            for (TimecardBreak b : c.breaks()) {
                ObjectNode bn = bs.addObject();
                bn.put("start", b.span().start().toString());
                bn.put("end", b.span().end().toString());
                bn.put("kind", b.kind().name());
            }
        }
        return root.toString();
    }

    static List<DayTimecard> parseProposed(String json) {
        List<DayTimecard> out = new ArrayList<>();
        try {
            for (JsonNode n : MAPPER.readTree(json).path("timecards")) {
                List<TimecardBreak> bs = new ArrayList<>();
                for (JsonNode b : n.path("breaks")) {
                    bs.add(new TimecardBreak(new TimeSpan(Instant.parse(b.get("start").asText()),
                            Instant.parse(b.get("end").asText())), BreakKind.valueOf(b.get("kind").asText())));
                }
                out.add(new DayTimecard(new TimeSpan(Instant.parse(n.get("start").asText()),
                        Instant.parse(n.get("end").asText())), bs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable proposed timecard", e);
        }
        return out;
    }

    static List<CardDto> cardDtos(List<DayTimecard> cards) {
        List<CardDto> out = new ArrayList<>();
        for (DayTimecard c : cards) {
            List<BreakDto> bs = new ArrayList<>();
            for (TimecardBreak b : c.breaks()) {
                bs.add(new BreakDto(b.span().start().toString(), b.span().end().toString(), b.kind().name()));
            }
            out.add(new CardDto(c.span().start().toString(), c.span().end().toString(), bs));
        }
        return out;
    }

    static List<ApptDto> apptDtos(List<DraftAppointment> appts) {
        List<ApptDto> out = new ArrayList<>();
        for (DraftAppointment a : appts) {
            out.add(new ApptDto(a.span().start().toString(), a.span().end().toString(), a.status().name()));
        }
        return out;
    }
}
