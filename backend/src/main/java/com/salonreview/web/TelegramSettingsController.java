package com.salonreview.web;

import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.telegram.TelegramConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;

/**
 * OWNER-only settings for the 4-hand-request Telegram alert. Falls under the existing
 * {@code /api/owner/**} matcher in {@link com.salonreview.config.SecurityConfig} — no new security
 * config needed. GET only ever returns a masked token; the frontend must not PUT that masked value
 * back (see {@link TelegramConfigService#update}'s null-vs-empty-string contract).
 */
@RestController
@RequestMapping("/api/owner/settings/telegram")
public class TelegramSettingsController {

    private final TelegramConfigService configService;

    public TelegramSettingsController(TelegramConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<TelegramSettingsDto> get() {
        return ResponseEntity.ok(toDto(configService.get()));
    }

    @PutMapping
    public ResponseEntity<TelegramSettingsDto> update(@RequestBody TelegramSettingsUpdateRequest body, Principal principal) {
        return ResponseEntity.ok(toDto(configService.update(body.botToken(), body.chatId(), principal.getName())));
    }

    private static TelegramSettingsDto toDto(TelegramNotificationConfig cfg) {
        return new TelegramSettingsDto(mask(cfg.getBotToken()), cfg.getBotToken() != null, cfg.getChatId(),
                cfg.getUpdatedAt(), cfg.getUpdatedBy());
    }

    private static String mask(String token) {
        if (token == null || token.isBlank()) return null;
        return token.length() <= 4 ? "••••" : "••••" + token.substring(token.length() - 4);
    }

    public record TelegramSettingsDto(String botTokenMasked, boolean botTokenSet, String chatId,
                                       Instant updatedAt, String updatedBy) {
    }

    /** {@code null} field = leave unchanged; {@code ""} = clear. See {@link TelegramConfigService#update}. */
    public record TelegramSettingsUpdateRequest(String botToken, String chatId) {
    }
}
