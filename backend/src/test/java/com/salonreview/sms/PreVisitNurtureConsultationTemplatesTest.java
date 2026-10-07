package com.salonreview.sms;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Business 2's consultation templates render with every token the scheduler supplies, and the
 * artist facts file has an entry for both artists who take consultations. */
class PreVisitNurtureConsultationTemplatesTest {

    private static final String[] TOKENS = {"FNAME", "ARTIST", "ARTIST_CAP", "DAY", "TIME", "RELATIVE_DAY",
            "FORMAT_TITLE", "FORMAT_DETAILS", "STUDIO_NAME", "STUDIO_ADDRESS", "TEXT_NUMBER", "ARTIST_PHOTO_URL",
            "ARTIST_HEADLINE", "ARTIST_BIO", "ARTIST_QUOTE", "ARTIST_QUOTE_AUTHOR", "CALENDAR_URL"};

    @Test
    void everyConsultationTemplateRendersWithoutLeftoverTokens() {
        MailchimpEmailTemplateService service = new MailchimpEmailTemplateService();
        Map<String, String> vars = new HashMap<>();
        for (String t : TOKENS) vars.put(t, "x");
        for (String step : new String[] {"welcome", "meet_artist", "prep", "reminder"}) {
            String key = "pre_visit_nurture_consultation_" + step;
            assertThat(service.has(2L, key)).as(key).isTrue();
            Optional<String> html = service.render(2L, key, vars);
            assertThat(html).as(key).isPresent();
            assertThat(html.get()).as(key).doesNotContain("{{").contains("*|UNSUB|*").doesNotContain("—");
        }
    }

    @Test
    void bothConsultationArtistsHaveProfiles() {
        PreVisitNurtureContent content = new PreVisitNurtureContent();
        assertThat(content.artist(2L, "Anna").orElseThrow().photoUrl()).endsWith("anna-ring.jpg");
        assertThat(content.artist(2L, "Anastasiia").orElseThrow().photoUrl()).endsWith("anastasiia-ring-v2.jpg");
        // Unknown or former artist: Anna (owner decision 2026-10-07), never a client photo.
        assertThat(content.artist(2L, "Someone").orElseThrow().photoUrl()).endsWith("anna-ring.jpg");
        assertThat(content.artist(2L, null).orElseThrow().photoUrl()).endsWith("anna-ring.jpg");
        assertThat(content.artistName(2L, "Someone")).isEqualTo("Anna");
        assertThat(content.artistName(2L, null)).isEqualTo("Anna");
        assertThat(content.artistName(2L, "Anastasiia")).isEqualTo("Anastasiia");
        assertThat(content.artistName(1L, "Mila")).isEqualTo("Mila");
        assertThat(content.studio(2L).orElseThrow().textNumber()).isEqualTo("(833) 912-5558");
        assertThat(content.artist(1L, "Anna")).isEmpty();
    }
}
