package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A phone number the app refuses to send any further SMS to — {@link com.salonreview.sms.TwilioSmsService}
 * checks this table for every send, automated or manual, since that's the single choke point every
 * outbound SMS already goes through (see V61). Keyed by the E.164-normalized phone number itself
 * (see {@code TwilioInboundSmsController}'s doc comment on why this app's own tables always store
 * that form).
 */
@Entity
@Table(name = "blocked_number")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BlockedNumber {

    /** A manager chose "Block number" from the conversation menu. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** The customer texted a standard CTIA opt-out keyword (STOP/UNSUBSCRIBE/...) — see
     * {@code TwilioInboundSmsController}'s {@code OPT_OUT_KEYWORDS}. Legally binding: nothing
     * further may be sent to this number short of the manager manually unblocking it. */
    public static final String SOURCE_STOP_REQUEST = "STOP_REQUEST";

    @Id
    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "blocked_at", nullable = false)
    @Builder.Default
    private Instant blockedAt = Instant.now();

    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = SOURCE_MANUAL;
}
