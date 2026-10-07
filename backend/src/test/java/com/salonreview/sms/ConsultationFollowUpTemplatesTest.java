package com.salonreview.sms;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The consultation follow-up email templates render with every token the scheduler supplies, and
 * the four SMS templates exist with the right message class (the offer must be MARKETING so it only
 * reaches clients with marketing consent). */
class ConsultationFollowUpTemplatesTest {

    @Test
    void emailTemplatesRenderCompletely() {
        MailchimpEmailTemplateService service = new MailchimpEmailTemplateService();
        Map<String, String> vars = Map.of("FNAME", "Sarah", "ARTIST", "Anastasiia", "ARTIST_PHOTO_URL", "https://x/p.jpg",
                "EXPIRES", "Tuesday, October 13", "BOOK_URL", "https://pmu-annakara.com/?book=procedure&amp;offer=1.x");
        for (String key : new String[] {"consultation_follow_up_info", "consultation_follow_up_offer"}) {
            String html = service.render(2L, key, vars).orElseThrow();
            assertThat(html).as(key).doesNotContain("{{").contains("*|UNSUB|*").doesNotContain("—");
        }
        assertThat(service.render(2L, "consultation_follow_up_offer", vars).orElseThrow()).contains("$75 OFF").contains("Tuesday, October 13");
    }

    @Test
    void pmuLeadFollowUpEmailRendersAndOpensTheProcedurePopup() {
        String html = new MailchimpEmailTemplateService().render(2L, "lead_follow_up", Map.of("FNAME", "Sarah", "LINK", "https://x/"))
                .orElseThrow();
        assertThat(html).contains("Hi Sarah,").contains("It's Lucy from Anna Kara's PMU Studio.")
                .contains("https://pmu-annakara.com/?book=procedure").contains("*|UNSUB|*")
                .doesNotContain("{{").doesNotContain("—").doesNotContain("$75");
    }

    @Test
    void smsTemplatesHaveTheRightClass() {
        SmsMessageTemplateCatalog.TemplateDefault offer = SmsMessageTemplateCatalog.get("consultation_follow_up_offer");
        assertThat(offer.messageClass()).isEqualTo(SmsMessageClass.MARKETING);
        for (String key : new String[] {"consultation_follow_up_thanks", "consultation_follow_up_checkin", "consultation_follow_up_last_checkin"}) {
            SmsMessageTemplateCatalog.TemplateDefault t = SmsMessageTemplateCatalog.get(key);
            assertThat(t.messageClass()).as(key).isEqualTo(SmsMessageClass.TRANSACTIONAL);
            assertThat(t.automationKey()).isEqualTo("consultation_follow_up");
            assertThat(t.defaultBody()).doesNotContain("—").doesNotContainPattern("[\\x{1F300}-\\x{1FAFF}]");
        }
    }
}
