package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Per-business runtime config for the 4-hand-request Telegram alert (see V45, business-scoped by
 * V96). Owner-editable at {@code /api/owner/settings/telegram}; the bot token never leaves this
 * backend — mani and akluxnails-home call {@code POST /api/internal/notifications/four-hand-request}
 * instead of fetching this config directly. Every call site with no session resolves
 * {@code businessId} via {@link com.salonreview.repo.BusinessRepository#legacySmsBusiness} — see
 * {@link com.salonreview.telegram.TelegramConfigService#getForAutomation}.
 */
@Entity
@Table(name = "telegram_notification_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TelegramNotificationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

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
