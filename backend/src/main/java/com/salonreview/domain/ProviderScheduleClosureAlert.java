package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Schedule notification audit/outbox. V160 distinguishes legacy rows, queued attempts and
 * confirmed Telegram delivery; only SENT/deliveredAt contributes to automation delivery counts.
 */
@Entity
@Table(name = "provider_schedule_closure_alert")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ProviderScheduleClosureAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "team_member_id", nullable = false)
    private String teamMemberId;

    @Column(name = "team_member_name")
    private String teamMemberName;

    @Column(name = "slot_count", nullable = false)
    private int slotCount;

    @Column(name = "earliest_slot_at", nullable = false)
    private Instant earliestSlotAt;

    @Column(name = "latest_slot_at", nullable = false)
    private Instant latestSlotAt;

    @Column(name = "sent_at", nullable = false)
    @Builder.Default
    private Instant sentAt = Instant.now();

    @Column(name = "delivery_status", nullable = false)
    @Builder.Default
    private String deliveryStatus = "LEGACY";

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
