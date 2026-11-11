package com.salonreview.marketing;

import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PMU Academy course requests from pmu-annakara.com/academy (2026-10-13). The academy takes no
 * online payment (it never sold a course online), so a request is a lead: it is saved in
 * {@code academy_lead}, staff get a Telegram alert, and the person is added to HubSpot. The row is
 * saved first; the Telegram and HubSpot legs are fail-open and their outcome is written back.
 */
@Service
public class AcademyLeadService {

    private static final Logger log = LoggerFactory.getLogger(AcademyLeadService.class);

    private final JdbcTemplate jdbc;
    private final TelegramNotificationService telegram;
    private final HubSpotFormClient hubspot;
    private final AcademyLeadLinks links;
    private final LeadIntakeService leadIntake;

    public AcademyLeadService(JdbcTemplate jdbc, TelegramNotificationService telegram, HubSpotFormClient hubspot,
                              AcademyLeadLinks links, LeadIntakeService leadIntake) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.hubspot = hubspot;
        this.links = links;
        this.leadIntake = leadIntake;
    }

    /** {@code kind}: "subscribe" for the email-only "Subscribe now" band, anything else is a course
     * request. Sets the alert wording and hashtags, and only course requests get the follow-up funnel. */
    /** Where the visitor came from. A site that sends the first-touch referrer (null means an older
     * site build that does not) gets the same labels as website-form contacts, so Direct, Google
     * organic and referrals are told apart instead of landing as Unknown. */
    static String trafficSource(AcademyLead l) {
        if (l.referrer() == null) {
            return notBlank(l.gclid()) ? "Google Ads" : notBlank(l.utmSource()) ? l.utmSource() : null;
        }
        String ref = l.referrer().isBlank() || l.referrer().toLowerCase(java.util.Locale.ROOT).contains("pmu-annakara.com") ? null : l.referrer();
        return com.salonreview.landing.LandingTracking.classify(new com.salonreview.landing.LandingTracking(null, null, ref,
                blankToNull(l.utmSource()), blankToNull(l.utmMedium()), blankToNull(l.utmCampaign()), blankToNull(l.utmTerm()),
                blankToNull(l.utmContent()), blankToNull(l.fbclid()), blankToNull(l.gclid()), null, null));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record AcademyLead(String kind, String name, String phone, String email, String courseSlug, String courseName,
                              String message, boolean smsConsent, boolean emailConsent, String sourcePageUrl,
                              String utmSource, String utmMedium, String utmCampaign, String gclid,
                              String location, String utmTerm, String utmContent, String referrer, String fbclid,
                              String visitorId, String pagePath) {
        public boolean isSubscription() {
            return "subscribe".equals(kind);
        }
    }

    static final String FUNNEL_NOTE = "Auto follow-ups are on: email + text now (asks when to call), emails on "
            + "day 1, 3 and 7. They stop if the person texts back. Talked to them? Tap the button.\n"
            + "Автоворонка включена: письмо и SMS сейчас (спрашиваем, когда удобно созвониться), письма на 1, 3 "
            + "и 7 день. Остановится сама, если человек ответит SMS. Уже поговорили? Нажмите кнопку.";

    public record Outcome(long id, boolean telegramSent, boolean hubspotSubmitted, String hubspotError) {
    }

    public Outcome submit(long businessId, AcademyLead l) {
        Long id = jdbc.queryForObject("""
                INSERT INTO academy_lead (business_id, kind, name, phone, email, course_slug, course_name, message,
                    sms_consent, email_consent, source_page_url, utm_source, utm_medium, utm_campaign, gclid, utm_term, utm_content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, businessId, l.isSubscription() ? "subscribe" : "course_request", l.name(), l.phone(), l.email(), l.courseSlug(), l.courseName(),
                l.message(), l.smsConsent(), l.emailConsent(), l.sourcePageUrl(), l.utmSource(), l.utmMedium(),
                l.utmCampaign(), l.gclid(), l.utmTerm(), l.utmContent());

        recordSubmission(businessId, l, id);

        // Course requests get the automatic follow-up funnel (AcademyLeadFunnelScheduler); the button
        // stops it once staff have talked to the person.
        var ctx = telegram.academyRequestContext(businessId);
        String owner = ctx == null || ctx.owners().isEmpty() ? null : "Owner: " + String.join(", ", ctx.owners());
        String alert = com.salonreview.telegram.LeadAlertFormat.insertAfterFirstLine(formatAlert(l), owner);
        String openLead = notBlank(l.phone()) ? telegram.leadUrl(ctx, l.phone(), businessId) : null;
        boolean sent = l.isSubscription()
                ? telegram.sendPlainAlert(businessId, alert, "🧲 Open lead", openLead)
                : telegram.sendPlainAlert(businessId, alert + "\n\n" + FUNNEL_NOTE,
                        "🛑 Stop follow-ups / Остановить рассылку", links.stopUrl(id), "🧲 Open lead", openLead);

        HubSpotFormClient.Result hs = hubspot.submit(l.email(), hubspotProperties(l), l.sourcePageUrl(),
                "PMU Academy: " + (notBlank(l.courseName()) ? l.courseName() : "course request"));
        if (hs.error() != null && !"not_configured".equals(hs.error())) {
            log.warn("Academy lead {}: HubSpot form submission failed: {}", id, hs.error());
        }
        if (!l.isSubscription()) {
            try {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("courses", l.courseName());
                payload.put("message", l.message());
                payload.put("pageUrl", l.sourcePageUrl());
                payload.put("utmSource", l.utmSource());
                payload.put("utmCampaign", l.utmCampaign());
                payload.put("location", l.location());
                payload.put("smsConsent", String.valueOf(l.smsConsent()));
                payload.put("emailConsent", String.valueOf(l.emailConsent()));
                payload.values().removeIf(v -> v == null || v.isBlank());
                String traffic = trafficSource(l);
                leadIntake.capture(new LeadIntakeService.Lead(businessId, "Academy", traffic, l.phone(), l.email(), l.name(),
                        l.courseName(), "academy:" + id, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload)));
                linkVisitor(businessId, l, id);
            } catch (Exception e) {
                log.warn("Academy lead {}: CRM capture failed: {}", id, e.toString());
            }
        }
        jdbc.update("UPDATE academy_lead SET telegram_sent = ?, hubspot_submitted = ?, hubspot_error = ? WHERE id = ?",
                sent, hs.submitted(), hs.error(), id);
        return new Outcome(id, sent, hs.submitted(), hs.error());
    }

    /** One marketing.submissions row per form, tied to the browser (visitor id) and carrying the first-touch
     * source, so the lead's Website tab shows where each form came from and which visits led to it. */
    private void recordSubmission(long businessId, AcademyLead l, Long id) {
        try {
            String visitor = l.visitorId() != null && l.visitorId().matches("[0-9a-fA-F-]{36}") ? l.visitorId() : null;
            String path = notBlank(l.sourcePageUrl()) ? l.sourcePageUrl().replaceFirst("^https?://[^/]+", "") : null;
            String phone = notBlank(l.phone()) ? l.phone() : null;
            jdbc.update("""
                    INSERT INTO marketing.submissions (business_id, visitor_id, submission_type, customer_email, customer_phone,
                        landing_path, referrer, utm_source, utm_medium, utm_campaign, utm_term, utm_content, fbclid, gclid,
                        landing_page_slug, traffic_source, page_path)
                    VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'academy', ?, ?)
                    """, businessId, visitor, l.isSubscription() ? "academy_subscribe" : "academy_request",
                    notBlank(l.email()) ? l.email().trim().toLowerCase() : null, phone, path, blankToNull(l.referrer()),
                    blankToNull(l.utmSource()), blankToNull(l.utmMedium()), blankToNull(l.utmCampaign()), blankToNull(l.utmTerm()),
                    blankToNull(l.utmContent()), blankToNull(l.fbclid()), blankToNull(l.gclid()),
                    trafficSource(l) == null ? "Direct / No referrer" : trafficSource(l), blankToNull(l.pagePath()));
        } catch (RuntimeException e) {
            log.warn("Academy lead {}: submission row failed: {}", id, e.toString());
        }
    }

    private void linkVisitor(long businessId, AcademyLead l, Long id) {
        String visitor = l.visitorId() != null && l.visitorId().matches("[0-9a-fA-F-]{36}") ? l.visitorId() : null;
        if (visitor == null) return;
        try {
            jdbc.update("UPDATE lead_intake SET visitor_id = ?::uuid WHERE business_id = ? AND external_id = ? AND visitor_id IS NULL",
                    visitor, businessId, "academy:" + id);
        } catch (RuntimeException e) {
            log.warn("Academy lead {}: visitor link failed: {}", id, e.toString());
        }
    }

    /** Hashtags first, so staff can find every academy lead in Telegram search: #academy for all,
     * #academy_lead or #academy_subscriber by type, #course_<slug> for the chosen course. */
    static String formatAlert(AcademyLead l) {
        StringBuilder sb = new StringBuilder();
        if (l.isSubscription()) {
            sb.append("📩 Academy newsletter subscriber\n#academy #academy_subscriber\n");
        } else {
            sb.append("🎓 Academy course request\n#academy #academy_lead");
            // Several courses can be picked (comma-separated slugs, names joined with "; ").
            if (notBlank(l.courseSlug())) {
                for (String slug : l.courseSlug().split(",")) {
                    if (!slug.isBlank()) sb.append(" #course_").append(slug.trim().replaceAll("[^A-Za-z0-9]+", "_"));
                }
            }
            sb.append('\n');
            boolean several = notBlank(l.courseName()) && l.courseName().contains("; ");
            sb.append(several ? "Courses: " : "Course: ").append(blankTo(l.courseName(), "not chosen")).append('\n');
            sb.append("Name: ").append(l.name()).append('\n');
        }
        if (notBlank(l.phone())) sb.append("Phone: ").append(l.phone()).append('\n');
        if (notBlank(l.email())) sb.append("Email: ").append(l.email()).append('\n');
        if (notBlank(l.message())) sb.append("Message: ").append(l.message()).append('\n');
        sb.append("OK to text: ").append(l.smsConsent() ? "yes" : "no")
                .append(", OK to email: ").append(l.emailConsent() ? "yes" : "no").append('\n');
        if (notBlank(l.location())) sb.append("🌍 From: ").append(l.location()).append('\n');
        if (notBlank(l.sourcePageUrl())) sb.append("📄 Page: ").append(l.sourcePageUrl()).append('\n');
        if (notBlank(l.utmCampaign())) sb.append("📣 Ad: ").append(l.utmCampaign()).append('\n');
        else if (notBlank(l.utmSource())) sb.append("Source: ").append(l.utmSource()).append('\n');
        return sb.toString().trim();
    }

    static Map<String, String> hubspotProperties(AcademyLead l) {
        Map<String, String> p = new LinkedHashMap<>();
        String name = l.name() == null ? "" : l.name().trim();
        int space = name.indexOf(' ');
        p.put("firstname", space > 0 ? name.substring(0, space) : name);
        if (space > 0) p.put("lastname", name.substring(space + 1).trim());
        p.put("phone", l.phone());
        StringBuilder msg = new StringBuilder("PMU Academy request: ").append(blankTo(l.courseName(), "course not chosen"));
        if (notBlank(l.message())) msg.append("\n").append(l.message());
        msg.append("\nOK to text: ").append(l.smsConsent() ? "yes" : "no")
                .append(", OK to email: ").append(l.emailConsent() ? "yes" : "no");
        p.put("message", msg.toString());
        return p;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankTo(String s, String fallback) {
        return notBlank(s) ? s : fallback;
    }
}
