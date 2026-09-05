package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per {@code sms_message.id}, ever, for the win-back email fallback — see V130,
 * {@code WinbackEmailFallbackScheduler}. Doubles as idempotency marker and outcome log, same shape
 * as {@link LapsedCustomerWinbackSend}, scoped to the specific SMS send it followed up on rather
 * than to the customer overall, since {@code repeat_customer_winback} can re-fire for the same
 * customer every 60 days.
 */
@Entity
@Table(name = "winback_email_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WinbackEmailSend {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_CLICKED = "SKIPPED_CLICKED";
    public static final String STATE_SKIPPED_REPLIED = "SKIPPED_REPLIED";
    public static final String STATE_SKIPPED_NO_EMAIL = "SKIPPED_NO_EMAIL";
    public static final String STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String STATE_SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED";
    public static final String STATE_SKIPPED_NO_TEMPLATE = "SKIPPED_NO_TEMPLATE";
    public static final String STATE_SEND_FAILED = "SEND_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "automation_key", nullable = false)
    private String automationKey;

    /** Null for a pure-email campaign with no SMS leg at all (e.g. {@code color_booster_winback_oneoff},
     * see {@code ColorBoosterWinbackOneOffService}) — every other automation here always sets it. */
    @Column(name = "sms_message_id")
    private Long smsMessageId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "email_address")
    private String emailAddress;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "mailchimp_campaign_id")
    private String mailchimpCampaignId;

    /** The exact HTML sent, stored only for a real {@code STATE_SENT} row — lets the manager
     * conversation view preview the email without a live Mailchimp API call. {@code null} for
     * every {@code SKIPPED_*}/{@code SEND_FAILED} row (nothing was ever finalized/sent). */
    @Column(name = "content_html", columnDefinition = "TEXT")
    private String contentHtml;

    /** Earliest open event Mailchimp's per-recipient email-activity report reported for this
     * campaign, or {@code null} if not opened (yet) — see {@code MailchimpActivitySyncScheduler}.
     * Not synced for a {@code SKIPPED_*}/{@code SEND_FAILED} row (no campaign id to look up). */
    @Column(name = "opened_at")
    private Instant openedAt;

    /** Earliest click event from the same report — Mailchimp's own click tracking on the email's
     * links, not {@code sms_message.clicked_at} (which is the shared short-link's click, and could
     * have come from the earlier SMS instead of this email). */
    @Column(name = "email_clicked_at")
    private Instant emailClickedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
