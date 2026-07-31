package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the {@code same_day_rebooking_discount} automation — see
 * openspec/changes/same-day-rebooking-discount design.md D3/D7/D8. All three values are specific
 * to this Square account (or, for {@code promoSecret}, generated once for this deployment) and
 * could change if recreated — kept in config, never hardcoded in Java.
 */
@Component
@ConfigurationProperties(prefix = "rebooking")
@Getter
@Setter
public class RebookingProperties {

    /** Shared HMAC secret signing the {@code promo}/{@code exp} query params — see
     * {@code RebookingPromoSigner}. Blank means the signer refuses to sign (fail closed, no
     * sensible "open" default, matching {@link InternalApiProperties}). */
    private String promoSecret = "";

    /** Square's own "Text Subscribers" customer-group id — a Square customer belonging to this
     * segment is treated as having given SMS-marketing consent through Square itself, independent
     * of {@code marketing.contacts.sms_marketing_consent} (see design.md D3). */
    private String consentSegmentId = "gv2:DN9J6H6X8D4NN9202T6PKWK43C";

    /** The dedicated Square customer-group id backing the automatic $10 discount (see design.md
     * D7) — blank until the one-time Catalog/CustomerGroup setup is done, at which point group
     * enrollment/removal simply no-ops rather than erroring. */
    private String autoDiscountGroupId = "";

    public boolean isSigningConfigured() {
        return promoSecret != null && !promoSecret.isBlank();
    }

    public boolean isAutoDiscountConfigured() {
        return autoDiscountGroupId != null && !autoDiscountGroupId.isBlank();
    }
}
