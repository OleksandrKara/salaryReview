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
    @DisplayName("unknown template key returns null, not an exception")
    void unknownKeyReturnsNull() {
        assertThat(registry.find("does_not_exist")).isNull();
    }
}
