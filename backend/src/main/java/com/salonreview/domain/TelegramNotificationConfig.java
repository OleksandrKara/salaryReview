package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-row runtime config for the 4-hand-request Telegram alert (see V45). Owner-editable at
 * {@code /api/owner/settings/telegram}; the bot token never leaves this backend — mani and
 * akluxnails-home call {@code POST /api/internal/notifications/four-hand-request} instead of
 * fetching this config directly.
 */
@Entity
@Table(name = "telegram_notification_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TelegramNotificationConfig {

    @Id
    @Builder.Default
    private Boolean id = Boolean.TRUE;

    @Column(name = "bot_token")
    private String botToken;

    @Column(name = "chat_id")
    private String chatId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
