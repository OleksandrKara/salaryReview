package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per {@code marketing.contacts} lead-touch the {@code lead_follow_up} automation has
 * already considered — see V54/V60, openspec/changes/lead-followup-and-manager-inbox design.md
 * D3. Doubles as both the idempotency marker and the outcome log; no separate state-transition
 * phase is needed here (unlike {@link SmsReplyFlow}) since this automation is genuinely one-way —
 * send once per touch, done. {@code contactUpdatedAt} (not just {@code contactId}) is what makes
 * a row idempotent: a lead who resubmits contact info again (bumping marketing.contacts'
 * updated_at) is a genuinely new touch, worth another nudge, not a duplicate of the first one.
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

    /** The marketing.contacts row's own updated_at at the moment this touch was processed — see
     * this class's own doc comment for why this (not just contactId) is the idempotency key. */
    @Column(name = "contact_updated_at", nullable = false)
    private Instant contactUpdatedAt;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
