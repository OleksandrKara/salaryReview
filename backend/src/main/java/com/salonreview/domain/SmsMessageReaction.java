package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One emoji reaction on an {@link SmsMessage} — see V70. {@link #source} is {@code CUSTOMER} (an
 * Apple tapback-over-SMS text like {@code Loved "message"}, parsed and matched by
 * {@code SmsReactionService}) or {@code STAFF} (a manager/owner's own reaction, added from the
 * dashboard, never sent to the customer). {@link #reactor} is the fixed sentinel {@code "customer"}
 * for CUSTOMER rows, or the staff username for STAFF rows — together with {@link #smsMessageId} and
 * {@link #source} it's the row's natural key (see the migration's unique index): a re-tap or a
 * changed staff reaction updates this row rather than accumulating duplicates.
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

    @Column(name = "source", nullable = false)
    private String source; // "CUSTOMER" | "STAFF"

    @Column(name = "reactor", nullable = false)
    private String reactor;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
