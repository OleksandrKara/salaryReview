package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A phone-number → Square customer id link discovered by the Contacts tab's "Sync appointments"
 * action, for a lead who never completed the tracked booking flow (a manager followed up and
 * booked them directly, or they came back through some other channel). Owned entirely by this
 * app — never written by salonLandings, unlike marketing.contacts itself — so resolving it here
 * never risks a write conflicting with that other service.
 */
@Entity
@Table(name = "marketing_contact_square_link")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MarketingContactSquareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, unique = true, length = 32)
    private String phoneNumber;

    @Column(name = "square_customer_id", nullable = false, length = 64)
    private String squareCustomerId;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;
}
