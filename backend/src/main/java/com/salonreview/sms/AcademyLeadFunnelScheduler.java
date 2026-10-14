package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.marketing.MarketingContactsRepository;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follow-ups for PMU Academy course requests from pmu-annakara.com/academy (owner 2026-10-14). The
 * academy sells no course online, so a request is a lead and staff call it; this keeps the lead warm
 * until they do. Steps, counted from the request:
 * <ol>
 *   <li>{@code confirm_email} right away: the chosen course(s), what happens next, prices and payment plans.</li>
 *   <li>{@code confirm_sms} right away, to anyone who left a phone (it answers their own request, so
 *       transactional), held until 9:00 PT when the request comes in at night.</li>
 *   <li>{@code reviews_email} after 1 day: real Google reviews of the academy.</li>
 *   <li>{@code checkin} after 3 days: a text when the person ticked "Text me exclusive offers"
 *       ({@code sms_consent}), an email otherwise.</li>
 *   <li>{@code payment_email} after 7 days: the $1,000 deposit and payment plans.</li>
 * </ol>
 * The funnel stops for good when staff tap "Stop follow-ups" in the Telegram alert (status
 * {@code contacted}) or the person texts the studio number (status {@code replied}). Every step is
 * claimed in {@code academy_lead_touch} before it is sent, so it goes out at most once, and a step
 * whose window has passed (scheduler down for days) is skipped rather than sent late. Signed
 * "Anastasia and Anna": requests go to Anastasia; calls are taken by the managers.
 */
@Component
public class AcademyLeadFunnelScheduler {

    private static final Logger log = LoggerFactory.getLogger(AcademyLeadFunnelScheduler.class);

    static final String AUTOMATION_KEY = "academy_lead_funnel";
    static final long BUSINESS_ID = 2L;
    static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    /** Texts only between 9:00 and 20:00 salon time. */
    static final LocalTime SMS_FROM = LocalTime.of(9, 0);
    static final LocalTime SMS_UNTIL = LocalTime.of(20, 0);
    /** Oldest lead still in the funnel: the last step plus its window. */
    static final Duration MAX_LEAD_AGE = Duration.ofDays(10);

    enum Channel { EMAIL, SMS, CHECKIN }

    record Step(String key, Duration due, Duration window, Channel channel, String template, String subject, String preview) {
    }

    static final List<Step> STEPS = List.of(
            new Step("confirm_email", Duration.ZERO, Duration.ofDays(1), Channel.EMAIL, "academy_request_confirmation",
                    "We got your request, {{FNAME}}", "Here's what happens next, plus prices and payment plans."),
            new Step("confirm_sms", Duration.ZERO, Duration.ofDays(1), Channel.SMS, "academy_request_confirmation", null, null),
            new Step("reviews_email", Duration.ofDays(1), Duration.ofDays(2), Channel.EMAIL, "academy_reviews",
                    "What our students say", "Rated 4.9 on Google by our students."),
            new Step("checkin", Duration.ofDays(3), Duration.ofDays(2), Channel.CHECKIN, "academy_checkin",
                    "Any questions, {{FNAME}}?", "We're happy to help you pick dates or a payment plan."),
            new Step("payment_email", Duration.ofDays(7), Duration.ofDays(2), Channel.EMAIL, "academy_payment_plans",
                    "Secure your spot with $1,000", "Payment plans with Klarna, Affirm and PayPal.")
    );

    record Lead(long id, String name, String phone, String email, String courseName, boolean smsConsent, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final SmsAutomationService automationService;
    private final TwilioSmsService smsService;
    private final MailchimpConfigRepository mailchimpConfigs;
    private final MailchimpEmailService mailchimp;
    private final MailchimpEmailTemplateService templates;
    private final MarketingContactsRepository contacts;
    private final Clock clock;

    @Autowired
    public AcademyLeadFunnelScheduler(JdbcTemplate jdbc, SmsAutomationService automationService, TwilioSmsService smsService,
                                      MailchimpConfigRepository mailchimpConfigs, MailchimpEmailService mailchimp,
                                      MailchimpEmailTemplateService templates, MarketingContactsRepository contacts) {
        this(jdbc, automationService, smsService, mailchimpConfigs, mailchimp, templates, contacts, Clock.systemUTC());
    }

    AcademyLeadFunnelScheduler(JdbcTemplate jdbc, SmsAutomationService automationService, TwilioSmsService smsService,
                               MailchimpConfigRepository mailchimpConfigs, MailchimpEmailService mailchimp,
                               MailchimpEmailTemplateService templates, MarketingContactsRepository contacts, Clock clock) {
        this.jdbc = jdbc;
        this.automationService = automationService;
        this.smsService = smsService;
        this.mailchimpConfigs = mailchimpConfigs;
        this.mailchimp = mailchimp;
        this.templates = templates;
        this.contacts = contacts;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    @SchedulerLock(name = "AcademyLeadFunnelScheduler_run", lockAtLeastFor = "PT20S", lockAtMostFor = "PT5M")
    public void run() {
        if (!automationService.isEnabled(BUSINESS_ID, AUTOMATION_KEY)) {
            return;
        }
        Instant now = clock.instant();
        markDuplicates(now);
        MailchimpConfig mc = mailchimpConfigs.findByBusinessId(BUSINESS_ID).filter(MailchimpConfig::isConfigured).orElse(null);
        for (Lead lead : openLeads(now)) {
            try {
                advance(lead, mc, now);
            } catch (RuntimeException e) {
                log.warn("Academy funnel: lead {} failed this run (retried next run): {}", lead.id(), e.getMessage(), e);
            }
        }
    }

    /** A second request from the same email or phone within 14 days (double submit, or another
     * course added later) doesn't start a second funnel: staff see it in Telegram anyway. */
    private void markDuplicates(Instant now) {
        jdbc.update("""
                UPDATE academy_lead l SET status = 'duplicate', stopped_at = now()
                WHERE l.business_id = ? AND l.kind = 'course_request' AND l.status = 'new' AND l.created_at > ?
                  AND NOT EXISTS (SELECT 1 FROM academy_lead_touch t WHERE t.lead_id = l.id)
                  AND EXISTS (SELECT 1 FROM academy_lead o
                              WHERE o.business_id = l.business_id AND o.kind = 'course_request' AND o.id < l.id
                                AND o.created_at > l.created_at - interval '14 days'
                                AND (lower(o.email) = lower(l.email) OR o.phone = l.phone))
                """, BUSINESS_ID, java.sql.Timestamp.from(now.minus(MAX_LEAD_AGE)));
    }

    private List<Lead> openLeads(Instant now) {
        return jdbc.query("""
                SELECT id, name, phone, email, course_name, sms_consent, created_at FROM academy_lead
                WHERE business_id = ? AND kind = 'course_request' AND status = 'new' AND created_at > ?
                  AND (SELECT count(*) FROM academy_lead_touch t WHERE t.lead_id = academy_lead.id) < ?
                ORDER BY id
                """, (rs, i) -> new Lead(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("email"),
                        rs.getString("course_name"), rs.getBoolean("sms_consent"), rs.getTimestamp("created_at").toInstant()),
                BUSINESS_ID, java.sql.Timestamp.from(now.minus(MAX_LEAD_AGE)), STEPS.size());
    }

    void advance(Lead lead, MailchimpConfig mc, Instant now) {
        Set<String> done = Set.copyOf(jdbc.queryForList("SELECT step FROM academy_lead_touch WHERE lead_id = ?", String.class, lead.id()));
        for (Step step : STEPS) {
            if (done.contains(step.key())) continue;
            Instant due = lead.createdAt().plus(step.due());
            if (now.isBefore(due)) return; // later steps are later still
            if (now.isAfter(due.plus(step.window()))) {
                touch(lead, step, "-", "skipped_late", null);
                continue;
            }
            // Texted the studio since the request: a person is talking to them now.
            if (lead.phone() != null && contacts.textedUsSince(lead.phone(), BUSINESS_ID, lead.createdAt())) {
                jdbc.update("UPDATE academy_lead SET status = 'replied', stopped_at = now() WHERE id = ? AND status = 'new'", lead.id());
                return;
            }
            boolean bySms = step.channel() == Channel.SMS || (step.channel() == Channel.CHECKIN && lead.smsConsent() && lead.phone() != null);
            if (bySms) {
                if (lead.phone() == null) {
                    touch(lead, step, "sms", "skipped_no_phone", null);
                    continue;
                }
                if (!inSmsHours(now)) return; // the morning run sends it
                sendSms(lead, step);
            } else {
                sendEmail(lead, step, mc);
            }
            return; // one message per lead per run
        }
    }

    static boolean inSmsHours(Instant now) {
        LocalTime t = now.atZone(PACIFIC).toLocalTime();
        return !t.isBefore(SMS_FROM) && t.isBefore(SMS_UNTIL);
    }

    private void sendSms(Lead lead, Step step) {
        if (!touch(lead, step, "sms", "sending", null)) return;
        String key = step.channel() == Channel.SMS ? "academy_request_confirmation" : "academy_request_checkin";
        var vars = Map.of("name", firstName(lead.name()), "courses", coursesText(lead.courseName()));
        var result = smsService.sendTemplated(BUSINESS_ID, key, lead.phone(), vars);
        finish(lead, step, result.sent() ? "sent" : "not_sent", result.sent() ? null : result.reason());
    }

    private void sendEmail(Lead lead, Step step, MailchimpConfig mc) {
        if (lead.email() == null || lead.email().isBlank()) {
            touch(lead, step, "email", "skipped_no_email", null);
            return;
        }
        if (mc == null) {
            touch(lead, step, "email", "skipped_not_configured", null);
            return;
        }
        String first = firstName(lead.name());
        Map<String, String> vars = new HashMap<>();
        vars.put("FNAME", HtmlUtils.htmlEscape(first));
        vars.put("COURSES_TEXT", HtmlUtils.htmlEscape(coursesText(lead.courseName())));
        vars.put("COURSES_HTML", coursesHtml(lead.courseName()));
        var html = templates.render(BUSINESS_ID, step.template(), vars);
        if (html.isEmpty()) {
            touch(lead, step, "email", "skipped_no_template", null);
            return;
        }
        if (!touch(lead, step, "email", "sending", null)) return;
        String subject = subject(step, first);
        try {
            mailchimp.sendWinbackEmail(mc, lead.email(), subject, step.preview(),
                    "academy_lead_funnel " + step.key() + " lead " + lead.id(), html.get());
            finish(lead, step, "sent", null);
        } catch (Exception e) {
            log.warn("Academy funnel: {} email for lead {} failed (not retried): {}", step.key(), lead.id(), e.getMessage());
            finish(lead, step, "send_failed", e.getMessage());
        }
    }

    /** Claims the step; false when another run already did (unique lead_id + step). */
    private boolean touch(Lead lead, Step step, String channel, String state, String detail) {
        return jdbc.update("""
                INSERT INTO academy_lead_touch (lead_id, step, channel, state, detail) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (lead_id, step) DO NOTHING
                """, lead.id(), step.key(), channel, state, detail) == 1;
    }

    private void finish(Lead lead, Step step, String state, String detail) {
        jdbc.update("UPDATE academy_lead_touch SET state = ?, detail = ? WHERE lead_id = ? AND step = ?",
                state, detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)), lead.id(), step.key());
    }

    /** "We got your request, Jane"; without a usable name the ", {{FNAME}}" part is dropped. */
    static String subject(Step step, String first) {
        return "there".equals(first) ? step.subject().replace(", {{FNAME}}", "") : step.subject().replace("{{FNAME}}", first);
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) return "there";
        String first = Names.capitalizeFirst(name.trim().split("\\s+")[0]);
        return first == null || first.isBlank() ? "there" : first;
    }

    static List<String> courses(String courseName) {
        List<String> out = new ArrayList<>();
        if (courseName == null) return out;
        for (String c : courseName.split(";")) {
            if (!c.isBlank()) out.add(c.trim());
        }
        return out;
    }

    /** "Brows Basic Course", "Brows Basic Course and Lips Basic Course", "A, B and C", or a generic
     * phrase when no course was picked. */
    static String coursesText(String courseName) {
        List<String> c = courses(courseName);
        if (c.isEmpty()) return "PMU training";
        if (c.size() == 1) return c.get(0);
        return String.join(", ", c.subList(0, c.size() - 1)) + " and " + c.get(c.size() - 1);
    }

    static String coursesHtml(String courseName) {
        List<String> c = courses(courseName);
        if (c.isEmpty()) return "PMU training (no course picked yet, we'll help you choose)";
        StringBuilder sb = new StringBuilder();
        for (String name : c) {
            if (!sb.isEmpty()) sb.append("<br>");
            sb.append("&#10003;&nbsp; <b>").append(HtmlUtils.htmlEscape(name)).append("</b>");
        }
        return sb.toString();
    }
}
