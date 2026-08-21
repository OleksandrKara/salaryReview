package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An owner's custom body for one variant slot of one SMS template, business-scoped. Absence of a
 * row for a given (business_id, template_key, variant_index) is the common case — it means "use
 * the in-code default for that slot" — see {@code com.salonreview.sms.SmsMessageTemplateCatalog}.
 * A single-variant template key only ever has a row at {@link #variantIndex} 0. Only the raw text
 * lives here; {@code SmsMessageClass} (transactional vs. marketing) stays fixed in code per
 * template_key, never owner-editable — see that catalog's own doc for why.
 */
@Entity
@Table(name = "sms_template_override")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsTemplateOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "template_key", nullable = false)
    private String templateKey;

    /** Which of the catalog key's {@code defaultBodies} this overrides — see V122. */
    @Column(name = "variant_index", nullable = false)
    @Builder.Default
    private int variantIndex = 0;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
