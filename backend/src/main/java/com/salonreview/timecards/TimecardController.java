package com.salonreview.timecards;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.timecards.TimecardAdminService.ProviderSetup;
import com.salonreview.timecards.TimecardAdminService.UpdateRequest;
import com.salonreview.timecards.TimecardDtos.*;
import com.salonreview.timecards.PayPeriodSummaryService.ProviderSummary;
import com.salonreview.timecards.PayPeriodSummaryService.Summary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** W2 time tracking API: owner setup, approver queue, provider self-service, payroll summary. */
@RestController
@RequestMapping("/api/timecards")
public class TimecardController {

    public record ApproveRequest(String date, Long providerId, Boolean onBehalf) {}
    public record ReopenRequest(String date, Long providerId) {}
    public record RestRequest(String date, Long providerId, boolean notProvided) {}
    public record SignRequest(String signedName) {}
    public record Earnings(String from, String to, ProviderSummary timeBased, Integer commissionComparisonCents) {}
    public record Profile(boolean w2, String name, boolean waiverSigned) {}

    private final CurrentBusinessContext business;
    private final TimecardDayService days;
    private final TimecardReviewService reviews;
    private final TimecardAdminService admin;
    private final PayPeriodSummaryService summaries;
    private final JdbcTemplate jdbc;

    public TimecardController(CurrentBusinessContext business, TimecardDayService days, TimecardReviewService reviews,
                              TimecardAdminService admin, PayPeriodSummaryService summaries, JdbcTemplate jdbc) {
        this.business = business;
        this.days = days;
        this.reviews = reviews;
        this.admin = admin;
        this.summaries = summaries;
        this.jdbc = jdbc;
    }

    // ---- owner setup ----

    @GetMapping("/admin/providers")
    public List<ProviderSetup> providers() {
        return admin.list(business.id());
    }

    @GetMapping("/admin/approver-candidates")
    public List<TimecardAdminService.Approver> candidates() {
        return admin.approverCandidates(business.id());
    }

    @PutMapping("/admin/providers/{id}")
    public ProviderSetup update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return admin.update(business.id(), id, req);
    }

    @PostMapping("/admin/providers/{id}/import-phone")
    public ProviderSetup importPhone(@PathVariable Long id) {
        return admin.importPhoneFromSquare(business.id(), id);
    }

    @PostMapping("/admin/providers/{id}/waiver/revoke")
    public WaiverStatus revoke(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return reviews.revokeWaiver(business.id(), id, body == null ? null : body.get("note"));
    }

    @GetMapping("/admin/summary")
    public Summary summary(@RequestParam int year, @RequestParam int month, @RequestParam int half) {
        return summaries.summary(business.id(), year, month, checkHalf(half), true);
    }

    @GetMapping("/admin/summary.csv")
    public ResponseEntity<byte[]> summaryCsv(@RequestParam int year, @RequestParam int month, @RequestParam int half) {
        Summary s = summaries.summary(business.id(), year, month, checkHalf(half), false);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payroll-" + s.from() + "_" + s.to() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(summaries.csv(s).getBytes(StandardCharsets.UTF_8));
    }

    // ---- approver (owner + manager) ----

    @GetMapping("/queue")
    public List<DayView> queue(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam(defaultValue = "14") int days) {
        Long b = business.id();
        boolean owner = me.getRole().name().equals("OWNER");
        List<Long> ids = jdbc.queryForList("SELECT id FROM providers WHERE business_id = ? AND worker_type = 'W2' AND active "
                + "ORDER BY display_name", Long.class, b);
        LocalDate today = LocalDate.now(TimecardDayService.RULES.zone());
        List<DayView> out = new ArrayList<>();
        for (Long pid : ids) {
            if (!reviews.canApprove(b, me.getUserId(), me.getRole().name(), pid)) continue;
            for (int i = 1; i <= Math.min(days, 45); i++) {
                DayView v = this.days.view(b, pid, today.minusDays(i), owner);
                if (!v.source().equals("NONE") && !v.status().equals("APPROVED")) out.add(v);
            }
        }
        return out;
    }

    @GetMapping("/day")
    public DayView day(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam Long providerId, @RequestParam String date) {
        requireApprover(me, providerId);
        return days.view(business.id(), providerId, TimecardReviewService.parseDate(date), me.getRole().name().equals("OWNER"));
    }

    @PostMapping("/approve")
    public DayView approve(@AuthenticationPrincipal AppUserPrincipal me, @RequestBody ApproveRequest r) {
        return reviews.approve(business.id(), me.getUserId(), me.getRole().name(), r.providerId(),
                TimecardReviewService.parseDate(r.date()), Boolean.TRUE.equals(r.onBehalf()));
    }

    @PostMapping("/reopen")
    public DayView reopen(@AuthenticationPrincipal AppUserPrincipal me, @RequestBody ReopenRequest r) {
        return reviews.reopen(business.id(), me.getUserId(), me.getRole().name(), r.providerId(),
                TimecardReviewService.parseDate(r.date()));
    }

    @PostMapping("/rest-not-provided")
    public DayView restNotProvided(@AuthenticationPrincipal AppUserPrincipal me, @RequestBody RestRequest r) {
        return reviews.markRestNotProvided(business.id(), me.getUserId(), me.getRole().name(), r.providerId(),
                TimecardReviewService.parseDate(r.date()), r.notProvided());
    }

    private void requireApprover(AppUserPrincipal me, Long providerId) {
        if (!reviews.canApprove(business.id(), me.getUserId(), me.getRole().name(), providerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an approver for this worker");
        }
    }

    // ---- provider self-service ----

    @GetMapping("/me/profile")
    public Profile profile(@AuthenticationPrincipal AppUserPrincipal me) {
        Long pid = providerId(me);
        ProviderSetup s = admin.get(business.id(), pid);
        return new Profile("W2".equals(s.workerType()), s.name(), reviews.waiver(business.id(), pid).signed());
    }

    @GetMapping("/me/days")
    public List<DayView> myDays(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam(defaultValue = "14") int days) {
        Long pid = w2Provider(me);
        LocalDate today = LocalDate.now(TimecardDayService.RULES.zone());
        List<DayView> out = new ArrayList<>();
        for (int i = 0; i <= Math.min(days, 45); i++) {
            DayView v = this.days.view(business.id(), pid, today.minusDays(i), true);
            if (!v.source().equals("NONE")) out.add(v);
        }
        return out;
    }

    @GetMapping("/me/range")
    public List<DayView> myRange(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam String from, @RequestParam String to) {
        Long pid = w2Provider(me);
        LocalDate start = TimecardReviewService.parseDate(from);
        LocalDate end = TimecardReviewService.parseDate(to);
        LocalDate today = LocalDate.now(TimecardDayService.RULES.zone());
        if (end.isAfter(today)) end = today;
        if (end.isBefore(start) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 35) {
            throw TimecardReviewService.bad("Invalid date range");
        }
        List<DayView> out = new ArrayList<>();
        for (LocalDate d = end; !d.isBefore(start); d = d.minusDays(1)) {
            DayView v = this.days.view(business.id(), pid, d, true);
            if (!v.source().equals("NONE")) out.add(v);
        }
        return out;
    }

    @GetMapping("/me/day")
    public DayView myDay(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam String date) {
        return days.view(business.id(), w2Provider(me), TimecardReviewService.parseDate(date), true);
    }

    @PostMapping("/me/submit")
    public DayView submit(@AuthenticationPrincipal AppUserPrincipal me, @RequestBody SubmitRequest r) {
        return reviews.submit(business.id(), w2Provider(me), r);
    }

    @GetMapping("/me/waiver")
    public WaiverStatus myWaiver(@AuthenticationPrincipal AppUserPrincipal me) {
        return reviews.waiver(business.id(), w2Provider(me));
    }

    @PostMapping("/me/waiver/sign")
    public WaiverStatus sign(@AuthenticationPrincipal AppUserPrincipal me, @RequestBody SignRequest r, HttpServletRequest http) {
        return reviews.signWaiver(business.id(), w2Provider(me), r.signedName(), http.getRemoteAddr());
    }

    @PostMapping("/me/waiver/revoke")
    public WaiverStatus myRevoke(@AuthenticationPrincipal AppUserPrincipal me) {
        return reviews.revokeWaiver(business.id(), w2Provider(me), "Revoked by worker");
    }

    @GetMapping("/me/earnings")
    public Earnings earnings(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam int year, @RequestParam int month,
                             @RequestParam int half) {
        Long pid = w2Provider(me);
        boolean showComparison = admin.get(business.id(), pid).showCommissionComparisonToProvider();
        LocalDate[] p = PayPeriodSummaryService.period(year, month, checkHalf(half));
        ProviderSummary s = summaries.forProvider(business.id(), pid, p[0], p[1], year, month, half, showComparison);
        return new Earnings(p[0].toString(), p[1].toString(), s, showComparison ? s.commissionComparisonCents() : null);
    }

    private Long providerId(AppUserPrincipal me) {
        if (me.getProviderId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a provider account");
        return me.getProviderId();
    }

    private Long w2Provider(AppUserPrincipal me) {
        Long pid = providerId(me);
        if (!"W2".equals(admin.get(business.id(), pid).workerType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Time tracking is not enabled for this account");
        }
        return pid;
    }

    private static int checkHalf(int half) {
        if (half != 1 && half != 2) throw TimecardReviewService.bad("half must be 1 or 2");
        return half;
    }
}
