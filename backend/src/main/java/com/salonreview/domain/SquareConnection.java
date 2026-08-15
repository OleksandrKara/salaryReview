package com.salonreview.domain;

import com.salonreview.config.SquareProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One business's Square account credentials — the multi-tenant replacement for the single
 * process-wide {@code square.*} env vars (see {@link SquareProperties}, design.md D5).
 * {@link #accessTokenEncrypted} is AES-GCM ciphertext
 * (see {@code com.salonreview.config.SquareCredentialCipher}), never plaintext.
 */
@Entity
@Table(name = "square_connection")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SquareConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false)
    private SquareProperties.Environment environment;

    @Column(name = "access_token_encrypted", nullable = false)
    private String accessTokenEncrypted;

    @Column(name = "location_id", nullable = false)
    private String locationId;

    /** Not consumed by any current API call — see V93's own migration comment. Purely
     * informational, stored so the owner has one place to see everything about this connection. */
    @Column(name = "application_id")
    private String applicationId;

    /** Nullable — Square doesn't require it upfront; see SquareClient.Location's own note. */
    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "connected_by_user_id", nullable = false)
    private Long connectedByUserId;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @PrePersist
    void prePersist() {
        if (connectedAt == null) connectedAt = Instant.now();
    }
}
