package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code authToken} for every webhook Twilio calls on this account with no session of its own:
 * the inbound-SMS webhook (reply branching for the checkout-review-request automation) and the
 * per-message delivery-status callback (see {@code TwilioStatusCallbackController}). Blank
 * {@code authToken} means every such request is rejected — there's no sensible "open" default for
 * this, matching {@link InternalApiProperties}.
 *
 * <p>{@code authToken} is the master Account Auth Token (not the restricted API Key/Secret pair
 * {@code twilio_sms_config} stores for sending) — Twilio's webhook signature scheme requires it
 * specifically.
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
