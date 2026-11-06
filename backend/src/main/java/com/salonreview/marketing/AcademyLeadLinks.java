package com.salonreview.marketing;

import com.salonreview.sms.RebookingPromoSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signed "Stop follow-ups" link for the academy lead Telegram alert (2026-10-14). Reuses the promo
 * link HMAC secret under its own prefix, same as ConsultationFollowUpLinks, so no signature can
 * double as another kind. */
@Component
public class AcademyLeadLinks {

    private static final String PREFIX = "ACAD-STOP";

    private final RebookingPromoSigner signer;
    private final String publicBaseUrl;

    public AcademyLeadLinks(RebookingPromoSigner signer, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.signer = signer;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Opens the lead in the CRM (login required) with the stop confirmation ready, so the stop is recorded with who and why. */
    public String stopUrl(long leadId) {
        return publicBaseUrl + "/admin/leads/open?academy=" + leadId;
    }

    public boolean verifyStop(long leadId, String signature) {
        return signer.verify(PREFIX, leadId, signature);
    }
}
