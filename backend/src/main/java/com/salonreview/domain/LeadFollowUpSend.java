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
    /** A different {@code contactUpdatedAt} touch for the same phone number already got a SENT
     * nudge within {@code LeadFollowUpScheduler}'s own resend cooldown — see that scheduler and
     * {@code LeadFollowUpSendRepository#existsByPhoneNumberAndStateAndCreatedAtAfter}'s own doc for
     * the 2026-09-05 duplicate-text incident this guards against. */
    public static final String STATE_SKIPPED_RECENTLY_SENT = "SKIPPED_RECENTLY_SENT";

    // --- Funnel continuation (2026-09-05) — email at ~24h, a final plain SMS at ~72h, both only
    // for a touch whose own initial SMS actually sent (state=SENT) and who's still unbooked at
    // that later check. See LeadFollowUpScheduler's own doc.
    public static final String EMAIL_STATE_SENT = "SENT";
    public static final String EMAIL_STATE_SKIPPED_BOOKED = "SKIPPED_BOOKED";
    public static final String EMAIL_STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String EMAIL_STATE_SKIPPED_NO_EMAIL = "SKIPPED_NO_EMAIL";
    public static final String EMAIL_STATE_SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED";
    public static final String EMAIL_STATE_SKIPPED_NO_TEMPLATE = "SKIPPED_NO_TEMPLATE";
    public static final String EMAIL_STATE_SEND_FAILED = "SEND_FAILED";

    public static final String SMS_FOLLOWUP_STATE_SENT = "SENT";
    public static final String SMS_FOLLOWUP_STATE_SKIPPED_BOOKED = "SKIPPED_BOOKED";
    public static final String SMS_FOLLOWUP_STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Added V154 — see that migration's own doc for why every pre-existing row backfilled to
     * business 1 exactly (not a guess). Needed so steps 2/3 of the funnel, which run as their own
     * independent poll well after step 1's per-business loop context is gone, can still resolve
     * which business a touch belongs to. */
    @Column(name = "business_id", nullable = false)
    private Long businessId;

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

    /** Step 2 of the funnel (~24h later) — {@code null} until that scheduler tick has considered
     * this touch at all, one of the {@code EMAIL_STATE_*} constants after. */
    @Column(name = "email_followup_state")
    private String emailFollowupState;

    /** Step 3 (~72h later) — {@code null} until considered, one of the {@code SMS_FOLLOWUP_STATE_*}
     * constants after. */
    @Column(name = "sms_followup_state")
    private String smsFollowupState;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
