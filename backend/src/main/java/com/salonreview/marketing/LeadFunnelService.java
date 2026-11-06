package com.salonreview.marketing;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.sms.AcademyLeadFunnelScheduler;
import com.salonreview.sms.SmsAutomationService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the automatic SMS and email funnels are doing for one lead: active, paused, stopped, finished or
 * never applied. Read-only view over the funnels' own tables, plus two staff actions: start the Academy
 * funnel for an academy lead that never got it, and stop a running funnel.
 */
@Service
public class LeadFunnelService {

    public static final String ACADEMY = "academy";
    public static final String CONSULT = "consult";
    public static final String SITE = "site";

    private static final Duration CONSULT_MAX_AGE = Duration.ofDays(60);
    private static final Duration SITE_MAX_AGE = Duration.ofDays(5);
    private static final Duration ACADEMY_REAPPLY_COOLDOWN = Duration.ofDays(14);
    private static final long ACADEMY_BUSINESS = 2L;
    private static final List<String> SITE_STEPS = List.of("sms", "email", "sms_final");
    private static final List<String> CONSULT_STEPS = List.of("thanks_sms", "info_email", "checkin_sms", "offer");

    public record Step(String key, String state, String at) {}

    /** state: ACTIVE, PAUSED, STOPPED, FINISHED, NOT_APPLIED. reason: machine code the UI translates. */
    public record Funnel(String key, String state, String reason, List<Step> steps, boolean canApply, boolean canStop,
                         String startedAt) {}

    record AcademyRow(long id, String phone, String email, String status, Instant createdAt) {}
    record ConsultRow(long id, String phone, String stopReason, Instant start, List<String> states) {}
    record SiteRow(String phone, String state, String emailState, String smsState, Instant createdAt) {}

    record Data(Map<String, List<AcademyRow>> academy, Map<Long, Map<String, String[]>> touches,
                        Map<String, List<ConsultRow>> consult, Map<String, List<SiteRow>> site) {}

    private final JdbcTemplate jdbc;
    private final SmsAutomationService automations;
    private final LeadFollowUpService leads;

    public LeadFunnelService(JdbcTemplate jdbc, SmsAutomationService automations, LeadFollowUpService leads) {
        this.jdbc = jdbc;
        this.automations = automations;
        this.leads = leads;
    }

    static String phoneKey(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        return d.length() < 10 ? null : d.substring(d.length() - 10);
    }

    static String emailKey(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    public Map<Long, List<Funnel>> forLeads(long businessId, List<LeadView> views) {
        Data data = load(businessId);
        Instant now = Instant.now();
        Map<Long, List<Funnel>> out = new HashMap<>();
        for (LeadView l : views) out.put(l.id(), evaluate(businessId, l, data, now));
        return out;
    }

    public List<Funnel> forLead(long businessId, LeadView lead) {
        return forLeads(businessId, List.of(lead)).get(lead.id());
    }

    private Data load(long businessId) {
        Map<String, List<AcademyRow>> academy = new HashMap<>();
        Map<Long, Map<String, String[]>> touches = new HashMap<>();
        Map<String, List<ConsultRow>> consult = new HashMap<>();
        Map<String, List<SiteRow>> site = new HashMap<>();
        if (businessId == ACADEMY_BUSINESS) {
            jdbc.query("""
                    SELECT id, phone, email, status, created_at FROM academy_lead
                    WHERE business_id = ? AND kind = 'course_request' ORDER BY id
                    """, rs -> {
                AcademyRow r = new AcademyRow(rs.getLong("id"), rs.getString("phone"), rs.getString("email"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant());
                index(academy, phoneKey(r.phone()), r);
                index(academy, emailKey(r.email()), r);
            }, businessId);
            jdbc.query("""
                    SELECT t.lead_id, t.step, t.state, t.created_at FROM academy_lead_touch t
                    JOIN academy_lead l ON l.id = t.lead_id WHERE l.business_id = ?
                    """, rs -> {
                touches.computeIfAbsent(rs.getLong("lead_id"), k -> new HashMap<>())
                        .put(rs.getString("step"), new String[] {rs.getString("state"), rs.getTimestamp("created_at").toInstant().toString()});
            }, businessId);
            jdbc.query("""
                    SELECT id, phone_number, stop_reason, consultation_start_at, thanks_sms_state, info_email_state,
                           checkin_sms_state, offer_state FROM consultation_follow_up
                    WHERE business_id = ? ORDER BY consultation_start_at
                    """, rs -> {
                ConsultRow r = new ConsultRow(rs.getLong("id"), rs.getString("phone_number"), rs.getString("stop_reason"),
                        rs.getTimestamp("consultation_start_at").toInstant(),
                        List.of(nz(rs.getString("thanks_sms_state")), nz(rs.getString("info_email_state")),
                                nz(rs.getString("checkin_sms_state")), nz(rs.getString("offer_state"))));
                index(consult, phoneKey(r.phone()), r);
            }, businessId);
        }
        jdbc.query("""
                SELECT phone_number, state, email_followup_state, sms_followup_state, created_at FROM lead_followup_send
                WHERE business_id = ? AND created_at > now() - interval '120 days' ORDER BY created_at
                """, rs -> {
            SiteRow r = new SiteRow(rs.getString("phone_number"), rs.getString("state"), rs.getString("email_followup_state"),
                    rs.getString("sms_followup_state"), rs.getTimestamp("created_at").toInstant());
            index(site, phoneKey(r.phone()), r);
        }, businessId);
        return new Data(academy, touches, consult, site);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> void index(Map<String, List<T>> map, String key, T row) {
        if (key != null) map.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    private static <T> T last(List<T> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    List<Funnel> evaluate(long businessId, LeadView l, Data d, Instant now) {
        List<Funnel> out = new ArrayList<>();
        String pk = phoneKey(l.phoneNumber());
        String ek = emailKey(l.email());
        boolean open = "NEW".equals(l.status()) || "CONTACTED".equals(l.status());

        if (businessId == ACADEMY_BUSINESS && (l.academy() || has(d.academy(), pk, ek))) {
            AcademyRow row = null;
            for (String k : new String[] {pk, ek}) {
                AcademyRow r = last(d.academy().get(k));
                if (r != null && (row == null || r.id() > row.id())) row = r;
            }
            out.add(academy(row, l, d, now, open));
        }
        if (businessId == ACADEMY_BUSINESS && (l.consultState() != null || pk != null && d.consult().containsKey(pk))) {
            out.add(consult(pk == null ? null : last(d.consult().get(pk)), l, now));
        }
        SiteRow site = pk == null ? null : last(d.site().get(pk));
        if (site != null || pk != null && open) out.add(site(site, businessId, now));
        return out;
    }

    private static boolean has(Map<String, ?> m, String a, String b) {
        return a != null && m.containsKey(a) || b != null && m.containsKey(b);
    }

    private Funnel academy(AcademyRow row, LeadView l, Data d, Instant now, boolean open) {
        boolean on = automations.isEnabled(ACADEMY_BUSINESS, "academy_lead_funnel");
        boolean contactable = l.phoneNumber() != null || l.email() != null;
        if (row == null) {
            return new Funnel(ACADEMY, "NOT_APPLIED", null, List.of(), l.academy() && open && contactable, false, null);
        }
        Map<String, String[]> t = d.touches().getOrDefault(row.id(), Map.of());
        List<Step> steps = new ArrayList<>();
        int decided = 0;
        for (String key : AcademyLeadFunnelScheduler.STEP_KEYS) {
            String[] s = t.get(key);
            if (s != null) decided++;
            steps.add(new Step(key, s == null ? "PENDING" : touchState(s[0]), s == null ? null : s[1]));
        }
        String state;
        String reason = null;
        switch (row.status()) {
            case "contacted" -> { state = "STOPPED"; reason = "STAFF"; }
            case "replied" -> { state = "STOPPED"; reason = "REPLIED"; }
            case "duplicate" -> { state = "STOPPED"; reason = "DUPLICATE"; }
            default -> {
                if (decided >= AcademyLeadFunnelScheduler.STEP_KEYS.size()
                        || row.createdAt().isBefore(now.minus(AcademyLeadFunnelScheduler.MAX_LEAD_AGE))) {
                    state = "FINISHED";
                } else if (!on) { state = "PAUSED"; reason = "AUTOMATION_OFF"; }
                else state = "ACTIVE";
            }
        }
        boolean cooled = row.createdAt().isBefore(now.minus(ACADEMY_REAPPLY_COOLDOWN));
        boolean canApply = open && contactable && l.academy() && ("FINISHED".equals(state) || "STOPPED".equals(state)) && cooled;
        return new Funnel(ACADEMY, state, reason, steps, canApply, "ACTIVE".equals(state) || "PAUSED".equals(state),
                row.createdAt().toString());
    }

    private static String touchState(String s) {
        if (s == null) return "PENDING";
        if (s.startsWith("skipped")) return "SKIPPED";
        if (s.equals("not_sent") || s.equals("send_failed")) return "FAILED";
        return "DONE";
    }

    private Funnel consult(ConsultRow row, LeadView l, Instant now) {
        if (row == null) return new Funnel(CONSULT, "NOT_APPLIED", null, List.of(), false, false, null);
        List<Step> steps = new ArrayList<>();
        int decided = 0;
        for (int i = 0; i < CONSULT_STEPS.size(); i++) {
            String s = row.states().get(i);
            if (!s.isEmpty()) decided++;
            steps.add(new Step(CONSULT_STEPS.get(i), s.isEmpty() ? "PENDING" : s.equals("SENT") ? "DONE" : s.equals("SEND_FAILED") ? "FAILED" : "SKIPPED", null));
        }
        String state;
        String reason = row.stopReason();
        if (reason != null) state = "REENGAGE".equals(reason) ? "FINISHED" : "STOPPED";
        else if (decided >= CONSULT_STEPS.size() || row.start().isBefore(now.minus(CONSULT_MAX_AGE))) state = "FINISHED";
        else if (!automations.isEnabled(ACADEMY_BUSINESS, "consultation_follow_up")) { state = "PAUSED"; reason = "AUTOMATION_OFF"; }
        else state = "ACTIVE";
        return new Funnel(CONSULT, state, reason, steps, false, "ACTIVE".equals(state) || "PAUSED".equals(state), row.start().toString());
    }

    private Funnel site(SiteRow row, long businessId, Instant now) {
        if (row == null) return new Funnel(SITE, "NOT_APPLIED", null, List.of(), false, false, null);
        List<String> st = List.of(row.state(), nz(row.emailState()), nz(row.smsState()));
        List<Step> steps = new ArrayList<>();
        String reason = null;
        for (int i = 0; i < SITE_STEPS.size(); i++) {
            String s = st.get(i);
            String code = s.startsWith("SKIPPED_") ? s.substring(8) : null;
            if (code != null && reason == null) reason = code;
            steps.add(new Step(SITE_STEPS.get(i), s.isEmpty() ? "PENDING" : s.equals("SENT") ? "DONE" : s.equals("SEND_FAILED") ? "FAILED" : "SKIPPED", null));
        }
        boolean allDecided = st.stream().noneMatch(String::isEmpty);
        boolean aged = row.createdAt().isBefore(now.minus(SITE_MAX_AGE));
        String state;
        if (allDecided || aged) state = reason != null && !"SENT".equals(row.state()) && !aged ? "STOPPED" : "FINISHED";
        else if (reason != null) state = "STOPPED";
        else if (!automations.isEnabled(businessId, "lead_follow_up")) { state = "PAUSED"; reason = "AUTOMATION_OFF"; }
        else state = "ACTIVE";
        return new Funnel(SITE, state, "FINISHED".equals(state) ? null : reason, steps, false, false, row.createdAt().toString());
    }

    public void applyAcademy(long businessId, long leadId, String by) {
        if (businessId != ACADEMY_BUSINESS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no academy funnel for this business");
        LeadView l = leads.find(leadId);
        Funnel f = forLead(businessId, l).stream().filter(x -> ACADEMY.equals(x.key())).findFirst().orElse(null);
        if (f == null || !f.canApply()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "funnel cannot be applied to this lead");
        }
        String name = l.name() == null || l.name().isBlank() ? "there" : l.name().trim();
        jdbc.update("""
                INSERT INTO academy_lead (business_id, kind, name, phone, email, course_name, message, sms_consent, email_consent, source_page_url)
                VALUES (?, 'course_request', ?, ?, ?, ?, ?, ?, ?, 'crm')
                """, businessId, name, l.phoneNumber(), l.email(), l.detail(), "Started from the Leads CRM by " + by,
                false, false);
        leads.addEvent(leadId, "NOTE", "Academy auto-funnel started (SMS + emails)", by);
    }

    public void stop(long businessId, long leadId, String key, String by) {
        LeadView l = leads.find(leadId);
        String pk = phoneKey(l.phoneNumber());
        String ek = emailKey(l.email());
        int n = 0;
        if (ACADEMY.equals(key) && businessId == ACADEMY_BUSINESS) {
            n = jdbc.update("""
                    UPDATE academy_lead SET status = 'contacted', stopped_at = now()
                    WHERE business_id = ? AND status = 'new' AND kind = 'course_request'
                      AND (right(regexp_replace(coalesce(phone, ''), '\\D', '', 'g'), 10) = ? OR lower(email) = ?)
                    """, businessId, pk, ek);
        } else if (CONSULT.equals(key) && businessId == ACADEMY_BUSINESS && pk != null) {
            n = jdbc.update("""
                    UPDATE consultation_follow_up SET stop_reason = 'STAFF', stopped_at = now()
                    WHERE business_id = ? AND stop_reason IS NULL
                      AND right(regexp_replace(coalesce(phone_number, ''), '\\D', '', 'g'), 10) = ?
                    """, businessId, pk);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "this funnel cannot be stopped manually");
        }
        if (n > 0) leads.addEvent(leadId, "NOTE", "Auto-funnel stopped by staff (" + key + ")", by);
    }

}
