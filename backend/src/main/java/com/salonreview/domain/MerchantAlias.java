package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A confirmed mapping from one specific post-normalization descriptor variant to the canonical
 * merchant it actually refers to — populated only by owner confirmation of a fuzzy-match suggestion
 * (see {@code MerchantNormalizer}, openspec design.md D2), never pre-seeded with guesses.
 */
@Entity
@Table(name = "merchant_aliases")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MerchantAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_pattern", nullable = false, unique = true)
    private String rawPattern;

    @Column(name = "canonical_merchant", nullable = false)
    private String canonicalMerchant;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void touch() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
