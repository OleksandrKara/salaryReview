package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A phone number a manager/owner has chosen to stop engaging with from the conversation view
 * (see V61) — {@link com.salonreview.sms.TwilioSmsService} refuses to send to any number in this
 * table, automated or manual, since that's the single choke point every outbound SMS already
 * goes through. Keyed by the E.164-normalized phone number itself (see
 * {@code TwilioInboundSmsController}'s doc comment on why this app's own tables always store
 * that form) — existence alone is the signal, so there's nothing else to model here.
 */
@Entity
@Table(name = "blocked_number")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BlockedNumber {

    @Id
    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "blocked_at", nullable = false)
    @Builder.Default
    private Instant blockedAt = Instant.now();
}
