package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.repo.MailchimpConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-business Mailchimp credentials — same shape and update semantics as
 * {@link TwilioSmsConfigService}, see that class's own doc. */
@Service
public class MailchimpConfigService {

    private final MailchimpConfigRepository repo;

    public MailchimpConfigService(MailchimpConfigRepository repo) {
        this.repo = repo;
    }

    /** {@code isConfigured() == false} (an all-blank config, not a missing row) for a business
     * that's never set one up — unlike Twilio's {@code get}, which throws, since every business
     * already gets a seeded twilio_sms_config row on creation (see BusinessSettingsService) but
     * mailchimp_config starts with none at all (see V128's own doc). */
    public MailchimpConfig get(Long businessId) {
        return repo.findByBusinessId(businessId)
                .orElseGet(() -> MailchimpConfig.builder().businessId(businessId).build());
    }

    /** {@code null} field = leave unchanged; {@code ""} (explicit empty string) = clear it — same
     * contract as {@link TwilioSmsConfigService#update}, since the owner UI's GET only ever
     * returns a masked {@code apiKey}. */
    @Transactional
    public MailchimpConfig update(String apiKey, String audienceId, String fromName, String fromEmail, String replyToEmail,
                                   String updatedByUsername, Long businessId) {
        MailchimpConfig cfg = repo.findByBusinessId(businessId)
                .orElseGet(() -> MailchimpConfig.builder().businessId(businessId).build());
        if (apiKey != null) cfg.setApiKey(blankToNull(apiKey));
        if (audienceId != null) cfg.setAudienceId(blankToNull(audienceId));
        if (fromName != null) cfg.setFromName(blankToNull(fromName));
        if (fromEmail != null) cfg.setFromEmail(blankToNull(fromEmail));
        if (replyToEmail != null) cfg.setReplyToEmail(blankToNull(replyToEmail));
        cfg.setUpdatedBy(updatedByUsername);
        return repo.save(cfg);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
