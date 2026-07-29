package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Square's {@code payment.updated} webhook (checkout-review-request automation — see
 * openspec/changes/sms-automations-hub). Blank {@code signatureKey} means every webhook request is
 * rejected — there's no sensible "open" default for this, matching {@link InternalApiProperties}.
 */
@Component
@ConfigurationProperties(prefix = "square.webhook")
@Getter
@Setter
public class SquareWebhookProperties {

    private String signatureKey = "";

    /** Must match, byte-for-byte, the URL configured in the Square Developer Dashboard
     * subscription — it's part of the HMAC input Square signs, not just documentation. */
    private String notificationUrl = "";

    public boolean isConfigured() {
        return signatureKey != null && !signatureKey.isBlank();
    }
}
