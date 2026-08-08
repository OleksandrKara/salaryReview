package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The customer's emoji reaction on an {@link SmsMessage} — an Apple tapback-over-SMS text (e.g.
 * {@code Loved "message"}), parsed and matched by {@code SmsReactionService}. At most one per
 * message (see V70's unique index) — a re-tap with a different reaction updates this row.
 */
@Entity
@Table(name = "sms_message_reaction")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsMessageReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sms_message_id", nullable = false)
    private Long smsMessageId;

    @Column(name = "emoji", nullable = false)
    private String emoji;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
