package com.salonreview.telegram;

import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.repo.TelegramNotificationConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramConfigService {

    private final TelegramNotificationConfigRepository repo;

    public TelegramConfigService(TelegramNotificationConfigRepository repo) {
        this.repo = repo;
    }

    public TelegramNotificationConfig get() {
        return repo.getSingleton();
    }

    /**
     * {@code null} field = leave unchanged; {@code ""} (explicit empty string) = clear it. The
     * owner UI's GET only ever returns a masked token, so it must never round-trip that masked
     * value back into a PUT — a {@code null} botToken here means "the owner didn't touch this
     * field," not "clear it."
     */
    @Transactional
    public TelegramNotificationConfig update(String botToken, String chatId, String updatedByUsername) {
        TelegramNotificationConfig cfg = repo.getSingleton();
        if (botToken != null) cfg.setBotToken(blankToNull(botToken));
        if (chatId != null) cfg.setChatId(blankToNull(chatId));
        cfg.setUpdatedBy(updatedByUsername);
        return repo.save(cfg);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
