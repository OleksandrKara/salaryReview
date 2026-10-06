package com.salonreview.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signed "Don't message her" links for the consultation follow-up staff alert. Reuses the promo
 * link HMAC secret (RebookingPromoSigner) with its own "CFU-STOP" prefix, so a promo signature can
 * never double as a stop signature or the other way round. */
@Component
public class ConsultationFollowUpLinks {

    private static final String PREFIX = "CFU-STOP";

    private final RebookingPromoSigner signer;
    private final String publicBaseUrl;

    public ConsultationFollowUpLinks(RebookingPromoSigner signer, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.signer = signer;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** {@code null} when link signing isn't configured: the alert then goes out without a button. */
    public String stopUrl(long followUpId) {
        String sig = signer.sign(PREFIX, followUpId);
        return sig == null ? null : publicBaseUrl + "/api/public/consultation-follow-up/stop?id=" + followUpId + "&sig=" + sig;
    }

    public boolean verifyStop(long followUpId, String signature) {
        return signer.verify(PREFIX, followUpId, signature);
    }
}
