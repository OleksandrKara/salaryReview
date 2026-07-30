package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per {@code marketing.contacts} lead the {@code lead_follow_up} automation has already
 * considered — see V54, openspec/changes/lead-followup-and-manager-inbox design.md D3. Doubles as
 * both the idempotency marker and the outcome log; no separate state-transition phase is needed
 * here (unlike {@link SmsReplyFlow}) since this automation is genuinely one-way — send once, done.
 */
@Entity
@Table(name = "lead_followup_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class LeadFollowUpSend {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_BOOKED = "SKIPPED_BOOKED";
    public static final String STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
