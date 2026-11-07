package com.salonreview.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Signed links for the consultation follow-up. The "Don't message her" staff-alert link, and the
 * client's personal $75 OFF booking link (owner request 2026-10-07):
 * {@code pmu-annakara.com/?book=procedure&offer=<id>.<sig>}, naming the consultation_follow_up row
 * that holds the offer, so the booking popup can show the discount only to whoever really has it.
 * Both reuse the promo link HMAC secret (RebookingPromoSigner), each under its own prefix, so no
 * signature can double as another kind. */
@Component
public class ConsultationFollowUpLinks {

    private static final String PREFIX = "CFU-STOP";
    private static final String OFFER_PREFIX = "CFU-OFFER";
    static final String BOOK_URL = "https://pmu-annakara.com/?book=procedure";

    private final RebookingPromoSigner signer;
    private final String publicBaseUrl;

    public ConsultationFollowUpLinks(RebookingPromoSigner signer, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.signer = signer;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Opens the lead in the CRM (login required) with the stop confirmation ready, so the stop is recorded with who and why. */
    public String stopUrl(long followUpId) {
        return publicBaseUrl + "/admin/leads/open?consult=" + followUpId + "&business=2";
    }

    public boolean verifyStop(long followUpId, String signature) {
        return signer.verify(PREFIX, followUpId, signature);
    }

    /** The client's personal offer link, or the plain booking link when signing isn't configured. */
    public String offerBookUrl(long followUpId) {
        String sig = signer.sign(OFFER_PREFIX, followUpId);
        return sig == null ? BOOK_URL : BOOK_URL + "&offer=" + followUpId + "." + sig;
    }

    /** The consultation_follow_up row id a well-signed offer token names. */
    public Optional<Long> verifyOffer(String token) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0) return Optional.empty();
        try {
            long id = Long.parseLong(token.substring(0, dot));
            return signer.verify(OFFER_PREFIX, id, token.substring(dot + 1)) ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
