package com.salonreview.sms;

import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.TwilioSmsConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TwilioSmsConfigService {

    private final TwilioSmsConfigRepository repo;

    public TwilioSmsConfigService(TwilioSmsConfigRepository repo) {
        this.repo = repo;
    }

    public TwilioSmsConfig get() {
        return repo.getSingleton();
    }

    /**
     * {@code null} field = leave unchanged; {@code ""} (explicit empty string) = clear it. The
     * owner UI's GET only ever returns masked key/secret values, so it must never round-trip
     * those masked values back into a PUT — a {@code null} here means "the owner didn't touch
     * this field," not "clear it."
     */
    @Transactional
    public TwilioSmsConfig update(String accountSid, String apiKey, String apiSecret,
                                  String fromPhoneNumber, String updatedByUsername) {
        TwilioSmsConfig cfg = repo.getSingleton();
        if (accountSid != null) cfg.setAccountSid(blankToNull(accountSid));
        if (apiKey != null) cfg.setApiKey(blankToNull(apiKey));
        if (apiSecret != null) cfg.setApiSecret(blankToNull(apiSecret));
        if (fromPhoneNumber != null) cfg.setFromPhoneNumber(blankToNull(fromPhoneNumber));
        cfg.setUpdatedBy(updatedByUsername);
        return repo.save(cfg);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
