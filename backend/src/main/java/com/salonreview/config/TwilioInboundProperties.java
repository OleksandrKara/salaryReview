package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Twilio's inbound-SMS webhook (reply branching for the checkout-review-request automation — see
 * openspec/changes/sms-automations-hub). Blank {@code authToken} means every inbound request is
 * rejected — there's no sensible "open" default for this, matching {@link InternalApiProperties}.
 *
 * <p>{@code authToken} is the master Account Auth Token (not the restricted API Key/Secret pair
 * {@code twilio_sms_config} stores for sending) — Twilio's inbound-webhook signature scheme
 * requires it specifically.
 */
@Component
@ConfigurationProperties(prefix = "twilio.inbound")
@Getter
@Setter
public class TwilioInboundProperties {

    private String authToken = "";

    /** Must match, byte-for-byte, the URL configured in the Twilio Console as this number's
     * inbound-SMS webhook — it's part of the signature Twilio computes, not just documentation. */
    private String webhookUrl = "";

    public boolean isConfigured() {
        return authToken != null && !authToken.isBlank();
    }
}
