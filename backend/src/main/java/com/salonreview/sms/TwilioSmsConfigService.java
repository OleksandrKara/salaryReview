package com.salonreview.sms;

import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TwilioSmsConfigService {

    private final TwilioSmsConfigRepository repo;
    private final BusinessRepository businesses;

    public TwilioSmsConfigService(TwilioSmsConfigRepository repo, BusinessRepository businesses) {
        this.repo = repo;
        this.businesses = businesses;
    }

    public TwilioSmsConfig get(Long businessId) {
        return repo.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("twilio_sms_config missing for business " + businessId));
    }

    /** Convenience for the scheduler/webhook call sites with no session to derive a business from
     * — see {@link BusinessRepository#legacySmsBusiness}'s own doc for why this always resolves to
     * Business A regardless of which business's automation triggered the call. */
    public TwilioSmsConfig getForAutomation() {
        return get(businesses.legacySmsBusiness().getId());
    }

    /**
     * {@code null} field = leave unchanged; {@code ""} (explicit empty string) = clear it. The
     * owner UI's GET only ever returns masked key/secret values, so it must never round-trip
     * those masked values back into a PUT — a {@code null} here means "the owner didn't touch
     * this field," not "clear it."
     */
    @Transactional
    public TwilioSmsConfig update(String accountSid, String apiKey, String apiSecret,
                                  String fromPhoneNumber, String senderName, String updatedByUsername, Long businessId) {
        TwilioSmsConfig cfg = get(businessId);
        if (accountSid != null) cfg.setAccountSid(blankToNull(accountSid));
        if (apiKey != null) cfg.setApiKey(blankToNull(apiKey));
        if (apiSecret != null) cfg.setApiSecret(blankToNull(apiSecret));
        if (fromPhoneNumber != null) cfg.setFromPhoneNumber(blankToNull(fromPhoneNumber));
        // NOT NULL column (unlike the fields above) — a blank submission is ignored rather than
        // clearing it, since there's no sensible "no sender name" state to clear to.
        if (senderName != null && !senderName.isBlank()) cfg.setSenderName(senderName.trim());
        cfg.setUpdatedBy(updatedByUsername);
        return repo.save(cfg);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
