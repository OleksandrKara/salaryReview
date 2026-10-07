package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A finished Mailchimp campaign still waiting to be sent (V165, see MailchimpDeferredSendScheduler). */
@Entity
@Table(name = "mailchimp_deferred_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MailchimpDeferredSend {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_SENT = "SENT";
    public static final String STATE_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "campaign_id", nullable = false)
    private String campaignId;

    @Column(name = "campaign_title")
    private String campaignTitle;

    @Builder.Default
    @Column(nullable = false)
    private String state = STATE_PENDING;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}
