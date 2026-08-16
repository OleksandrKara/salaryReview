package com.salonreview.telegram;

import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.TelegramNotificationConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramConfigService {

    private final TelegramNotificationConfigRepository repo;
    private final BusinessRepository businesses;

    public TelegramConfigService(TelegramNotificationConfigRepository repo, BusinessRepository businesses) {
        this.repo = repo;
        this.businesses = businesses;
    }

    public TelegramNotificationConfig get(Long businessId) {
        return repo.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("telegram_notification_config missing for business " + businessId));
    }

    /** Convenience for the scheduler/webhook/internal-endpoint call sites with no session to
     * derive a business from — see {@link BusinessRepository#legacySmsBusiness}'s own doc for why
     * this always resolves to Business A regardless of which business triggered the call. */
    public TelegramNotificationConfig getForAutomation() {
        return get(businesses.legacySmsBusiness().getId());
    }

    /**
     * {@code null} field = leave unchanged; {@code ""} (explicit empty string) = clear it. The
     * owner UI's GET only ever returns a masked token, so it must never round-trip that masked
     * value back into a PUT — a {@code null} botToken here means "the owner didn't touch this
     * field," not "clear it."
     */
    @Transactional
    public TelegramNotificationConfig update(String botToken, String chatId, String updatedByUsername, Long businessId) {
        TelegramNotificationConfig cfg = get(businessId);
        if (botToken != null) cfg.setBotToken(blankToNull(botToken));
        if (chatId != null) cfg.setChatId(blankToNull(chatId));
        cfg.setUpdatedBy(updatedByUsername);
        return repo.save(cfg);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
