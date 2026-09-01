package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One business's Search Console + GA4 + PageSpeed Insights credentials — seo-monitoring-dashboard
 * design.md D1. {@link #gscServiceAccountJsonEncrypted} and {@link #pagespeedApiKeyEncrypted} are
 * AES-GCM ciphertext (see {@code com.salonreview.config.SeoCredentialCipher}), never plaintext.
 * Deliberately a separate cipher/master-key from {@link SquareConnection}'s — rotating one
 * credential type's key must never force re-encrypting the other's.
 */
@Entity
@Table(name = "seo_connection")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "gsc_service_account_json_encrypted", nullable = false)
    private String gscServiceAccountJsonEncrypted;

    @Column(name = "ga4_property_id", nullable = false)
    private String ga4PropertyId;

    @Column(name = "ga4_measurement_id", nullable = false)
    private String ga4MeasurementId;

    @Column(name = "pagespeed_api_key_encrypted", nullable = false)
    private String pagespeedApiKeyEncrypted;

    @Column(name = "connected_by_user_id", nullable = false)
    private Long connectedByUserId;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** Non-null after any failed sync attempt (design.md Risks) — surfaced to the owner rather
     * than failing silently, same pattern as Square's sync-status indicator. Cleared back to null
     * on the next successful sync. */
    @Column(name = "last_sync_error")
    private String lastSyncError;

    @PrePersist
    void prePersist() {
        if (connectedAt == null) connectedAt = Instant.now();
    }
}
