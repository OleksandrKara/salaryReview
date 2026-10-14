package com.salonreview.sms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Automations page numbers for the academy request follow-up (academy_lead and
 * academy_lead_touch rather than sms_message/winback_email_send, where the other automations keep
 * theirs): one row per funnel step with how many went out, opened and clicked, plus how many leads
 * texted back and how many staff marked as talked to. */
@Component
public class AcademyLeadFunnelStats {

    /** One funnel step on the card. {@code opened}/{@code clicked} stay 0 for the text step. */
    public record StepStat(String key, String label, String channel, long sent, long opened, long clicked) {
    }

    public record Totals(long requests, long replied, long contacted, long emailSent, long emailOpened, long emailClicked,
                         List<StepStat> steps) {
    }

    private static final Map<String, String> LABELS = Map.of(
            "confirm_email", "Now · details",
            "confirm_sms", "Now · when to call?",
            "reviews_email", "Day 1 · reviews",
            "checkin", "Day 3 · questions?",
            "payment_email", "Day 7 · deposit");

    private final JdbcTemplate jdbc;

    public AcademyLeadFunnelStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Totals since(long businessId, Instant since) {
        Timestamp ts = Timestamp.from(since);
        Map<String, Object> leads = jdbc.queryForMap("""
                SELECT count(*) AS requests,
                       count(*) FILTER (WHERE status = 'replied') AS replied,
                       count(*) FILTER (WHERE status = 'contacted') AS contacted
                FROM academy_lead WHERE business_id = ? AND kind = 'course_request' AND status <> 'duplicate' AND created_at > ?
                """, businessId, ts);
        List<StepStat> steps = AcademyLeadFunnelScheduler.STEPS.stream().map(step -> {
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT count(*) AS sent, count(t.opened_at) AS opened, count(t.clicked_at) AS clicked
                    FROM academy_lead_touch t JOIN academy_lead l ON l.id = t.lead_id
                    WHERE l.business_id = ? AND t.step = ? AND t.state = 'sent' AND t.created_at > ?
                    """, businessId, step.key(), ts);
            return new StepStat(step.key(), LABELS.getOrDefault(step.key(), step.key()),
                    step.channel() == AcademyLeadFunnelScheduler.Channel.SMS ? "sms" : "email",
                    num(r.get("sent")), num(r.get("opened")), num(r.get("clicked")));
        }).toList();
        long emailSent = 0, emailOpened = 0, emailClicked = 0;
        for (StepStat s : steps) {
            if ("email".equals(s.channel())) {
                emailSent += s.sent();
                emailOpened += s.opened();
                emailClicked += s.clicked();
            }
        }
        return new Totals(num(leads.get("requests")), num(leads.get("replied")), num(leads.get("contacted")),
                emailSent, emailOpened, emailClicked, steps);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
