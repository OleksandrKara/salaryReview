package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One Telegram alert actually sent by {@code ProviderScheduleClosureAlertScheduler} — a single
 * poll can find several slots newly closed for the same provider (a provider very often blocks
 * their whole remaining day at once, not one slot at a time), grouped into exactly one row/one
 * message rather than one per slot. Doubles as this automation's "sent" count on the
 * {@code /owner/automations} hub (see {@code SmsAutomationService#list}), since it has no
 * {@code sms_message} row to count the way every SMS-channel automation does.
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
}
