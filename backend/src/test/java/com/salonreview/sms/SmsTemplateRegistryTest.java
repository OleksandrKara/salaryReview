package com.salonreview.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmsTemplateRegistryTest {

    private final SmsTemplateRegistry registry = new SmsTemplateRegistry();

    @Test
    @DisplayName("four_hand_request_received is registered as TRANSACTIONAL")
    void fourHandTemplateIsTransactional() {
        SmsTemplate template = registry.find("four_hand_request_received");

        assertThat(template).isNotNull();
        assertThat(template.messageClass()).isEqualTo(SmsMessageClass.TRANSACTIONAL);
    }

    @Test
    @DisplayName("four_hand_request_received renders variables into the body")
    void fourHandTemplateRendersVariables() {
        SmsTemplate template = registry.find("four_hand_request_received");

        String rendered = template.render().apply(Map.of("name", "Jane", "preferredTime", "Sat 2pm"));

        assertThat(rendered).contains("Hi Jane").contains("Sat 2pm");
    }

    @Test
    @DisplayName("four_hand_request_received title-cases an all-lowercase name")
    void fourHandTemplateNormalizesName() {
        SmsTemplate template = registry.find("four_hand_request_received");

        String rendered = template.render().apply(Map.of("name", "oleksandr", "preferredTime", "Sat 2pm"));

        assertThat(rendered).contains("Hi Oleksandr").doesNotContain("oleksandr");
    }

    @Test
    @DisplayName("checkout_rating_request title-cases an all-lowercase name and contains no em dash")
    void checkoutRatingRequestNormalizesNameAndAvoidsEmDash() {
        SmsTemplate template = registry.find("checkout_rating_request");

        String rendered = template.render().apply(Map.of("name", "oleksandr", "technician", "Susan"));

        assertThat(rendered).contains("Hi Oleksandr").doesNotContain("oleksandr").doesNotContain("—");
    }

    @Test
    @DisplayName("lead_follow_up_nudge title-cases an all-lowercase name, asks about hidden availability "
            + "instead of \"hold you a spot\", has no em dash, and carries no link — everyone who gets "
            + "this just came from the site itself")
    void leadFollowUpNudgeNormalizesNameAndAsksAboutOpenings() {
        SmsTemplate template = registry.find("lead_follow_up_nudge");

        String rendered = template.render().apply(Map.of("name", "oleksandr"));

        assertThat(rendered)
                .contains("Hi Oleksandr")
                .doesNotContain("oleksandr")
                .contains("don't show on our site")
                .doesNotContain("hold you a spot")
                .doesNotContain("http")
                .doesNotContain("—");
    }

    @Test
    @DisplayName("unknown template key returns null, not an exception")
    void unknownKeyReturnsNull() {
        assertThat(registry.find("does_not_exist")).isNull();
    }
}
