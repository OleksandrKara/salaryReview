package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Owner-configurable "less than how many hours' notice counts as a closure" threshold for {@code
 * provider_schedule_closure_alert} (see {@code ProviderScheduleClosureAlertScheduler}), per
 * business. Absence of a row simply means the business hasn't changed it from the default (24h) —
 * same "not configured yet" convention {@link BusinessPromoConfig}/{@link TelegramNotificationConfig}
 * already use, not a reason to seed one at business-creation time.
 */
@Entity
@Table(name = "provider_schedule_closure_alert_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ProviderScheduleClosureAlertConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "notice_threshold_hours", nullable = false)
    private Integer noticeThresholdHours;

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
