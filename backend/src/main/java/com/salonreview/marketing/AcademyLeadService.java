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
    private final HubSpotContactClient hubspot;

    public AcademyLeadService(JdbcTemplate jdbc, TelegramNotificationService telegram, HubSpotContactClient hubspot) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.hubspot = hubspot;
    }

    public record AcademyLead(String name, String phone, String email, String courseSlug, String courseName,
                              String message, boolean smsConsent, boolean emailConsent, String sourcePageUrl,
                              String utmSource, String utmMedium, String utmCampaign, String gclid) {
    }

    public record Outcome(long id, boolean telegramSent, String hubspotContactId, String hubspotError) {
    }

    public Outcome submit(long businessId, AcademyLead l) {
        Long id = jdbc.queryForObject("""
                INSERT INTO academy_lead (business_id, name, phone, email, course_slug, course_name, message,
                    sms_consent, email_consent, source_page_url, utm_source, utm_medium, utm_campaign, gclid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, businessId, l.name(), l.phone(), l.email(), l.courseSlug(), l.courseName(),
                l.message(), l.smsConsent(), l.emailConsent(), l.sourcePageUrl(), l.utmSource(), l.utmMedium(),
                l.utmCampaign(), l.gclid());

        boolean sent = telegram.sendPlainAlert(businessId, formatAlert(l));

        HubSpotContactClient.Result hs = hubspot.upsertByEmail(l.email(), hubspotProperties(l));
        if (hs.error() != null && !"not_configured".equals(hs.error())) {
            log.warn("Academy lead {}: HubSpot upsert failed: {}", id, hs.error());
        }
        jdbc.update("UPDATE academy_lead SET telegram_sent = ?, hubspot_contact_id = ?, hubspot_error = ? WHERE id = ?",
                sent, hs.contactId(), hs.error(), id);
        return new Outcome(id, sent, hs.contactId(), hs.error());
    }

    static String formatAlert(AcademyLead l) {
        StringBuilder sb = new StringBuilder("🎓 Academy course request\n");
        sb.append("Course: ").append(blankTo(l.courseName(), "not chosen")).append('\n');
        sb.append("Name: ").append(l.name()).append('\n');
        if (notBlank(l.phone())) sb.append("Phone: ").append(l.phone()).append('\n');
        if (notBlank(l.email())) sb.append("Email: ").append(l.email()).append('\n');
        if (notBlank(l.message())) sb.append("Message: ").append(l.message()).append('\n');
        sb.append("OK to text: ").append(l.smsConsent() ? "yes" : "no")
                .append(", OK to email: ").append(l.emailConsent() ? "yes" : "no").append('\n');
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
