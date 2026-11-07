package com.salonreview.timecards;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClientProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Morning proposal and reminder loop for W2 workers. Drafts yesterday, texts a one-tap link, reminds at 16:00,
 * and after 24 hours tells the approvers. SMS stays off unless both the global flag and the worker's own flag are on.
 */
@Service
public class TimecardLoopService {

    private static final Logger log = LoggerFactory.getLogger(TimecardLoopService.class);

    private record Worker(long providerId, String firstName, String phone, boolean smsEnabled) {}

    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext context;
    private final TimecardDayService days;
    private final TimecardReviewService reviews;
    private final TimecardLinkSigner signer;
    private final TwilioSmsService sms;
    private final SquareClientProvider squares;
    private final boolean smsGloballyEnabled;
    private final String publicBaseUrl;

    public TimecardLoopService(JdbcTemplate jdbc, CurrentBusinessContext context, TimecardDayService days,
                               TimecardReviewService reviews, TimecardLinkSigner signer, TwilioSmsService sms,
                               SquareClientProvider squares,
                               @Value("${timecards.sms.enabled:false}") boolean smsGloballyEnabled,
                               @Value("${app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.context = context;
        this.days = days;
        this.reviews = reviews;
        this.signer = signer;
        this.sms = sms;
        this.squares = squares;
        this.smsGloballyEnabled = smsGloballyEnabled;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Scheduled(cron = "0 5 8-20 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "TimecardLoopService_tick", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void tick() {
        List<Long> businesses = jdbc.queryForList("SELECT DISTINCT business_id FROM providers WHERE worker_type = 'W2' AND active",
                Long.class);
        for (Long b : businesses) {
            try {
                context.runAs(b, () -> runBusiness(b, LocalDateTime.now(TimecardDayService.RULES.zone())));
            } catch (RuntimeException e) {
                log.warn("Timecard loop failed for business {}: {}", b, e.toString());
            }
        }
    }

    void runBusiness(Long businessId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        List<Worker> workers = jdbc.query("""
                SELECT p.id, p.display_name, e.mobile_phone, e.sms_enabled FROM providers p
                JOIN provider_employment e ON e.business_id = p.business_id AND e.provider_id = p.id AND e.effective_to IS NULL
                WHERE p.business_id = ? AND p.worker_type = 'W2' AND p.active AND e.alerts_enabled""",
                (rs, i) -> new Worker(rs.getLong(1), rs.getString(2).split(" ")[0], rs.getString(3), rs.getBoolean(4)),
                businessId);
        for (Worker w : workers) {
            propose(businessId, w, yesterday);
            for (int back = 1; back <= 3; back++) followUp(businessId, w, today.minusDays(back), now);
        }
    }

    private void propose(Long businessId, Worker w, LocalDate date) {
        var c = days.calc(businessId, w.providerId(), date);
        if (!c.source().equals("DRAFT") || c.review() != null) return;
        long id = reviews.ensureReview(businessId, w.providerId(), date, "DRAFT");
        jdbc.update("UPDATE timecard_review SET proposed_json = ? WHERE id = ? AND business_id = ? AND proposed_json IS NULL",
                TimecardDayService.proposedJson(c.timecards()), id, businessId);
    }

    private void followUp(Long businessId, Worker w, LocalDate date, LocalDateTime now) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM timecard_review WHERE business_id = ? AND provider_id = ? "
                + "AND work_date = ? AND status = 'PROPOSED' AND mode = 'DRAFT'", Long.class,
                businessId, w.providerId(), java.sql.Date.valueOf(date));
        if (ids.isEmpty()) return;
        long review = ids.get(0);
        LocalDate today = now.toLocalDate();
        boolean isYesterday = date.equals(today.minusDays(1));
        if (isYesterday && now.getHour() >= 8 && !sent(businessId, review, "INITIAL")) {
            send(businessId, review, "INITIAL", w, date, "Hi " + w.firstName() + ", please confirm your hours for "
                    + label(date) + ": ");
        } else if (isYesterday && now.getHour() >= 16 && sent(businessId, review, "INITIAL") && !sent(businessId, review, "R16")) {
            send(businessId, review, "R16", w, date, "Reminder: please confirm your hours for " + label(date) + ": ");
        } else if (!isYesterday && now.getHour() >= 8 && sent(businessId, review, "INITIAL")
                && !sent(businessId, review, "ESCALATION")) {
            escalate(businessId, review, w, date);
        }
    }

    private boolean sent(Long businessId, long review, String kind) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM timecard_reminder WHERE business_id = ? AND review_id = ? AND kind = ?",
                Integer.class, businessId, review, kind);
        return n != null && n > 0;
    }

    private void record(Long businessId, long review, String kind) {
        jdbc.update("INSERT INTO timecard_reminder (business_id, review_id, kind) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                businessId, review, kind);
    }

    private void send(Long businessId, long review, String kind, Worker w, LocalDate date, String lead) {
        if (!smsGloballyEnabled || !w.smsEnabled() || w.phone() == null) {
            return;
        }
        String token = signer.token(businessId, w.providerId(), date, Instant.now().plusSeconds(36 * 3600).getEpochSecond());
        if (token == null) return;
        var result = sms.sendManual(businessId, w.phone(), lead + publicBaseUrl + "/timecard/" + token);
        if (result.sent()) record(businessId, review, kind);
    }

    private void escalate(Long businessId, long review, Worker w, LocalDate date) {
        if (!smsGloballyEnabled) return;
        List<String> members = jdbc.queryForList("""
                SELECT u.square_team_member_id FROM provider_time_approver a JOIN app_user u ON u.id = a.app_user_id
                WHERE a.business_id = ? AND a.provider_id = ? AND u.square_team_member_id IS NOT NULL""",
                String.class, businessId, w.providerId());
        boolean any = false;
        for (String m : members) {
            try {
                String phone = squares.forBusiness(businessId).teamMemberPhone(m);
                if (phone == null) continue;
                var r = sms.sendManual(businessId, phone, w.firstName() + " has not confirmed her hours for "
                        + label(date) + ". Please check Time approval in the salon app.");
                any |= r.sent();
            } catch (RuntimeException e) {
                log.warn("Escalation SMS failed for provider {}: {}", w.providerId(), e.toString());
            }
        }
        if (any) record(businessId, review, "ESCALATION");
    }

    private static String label(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth();
    }
}
