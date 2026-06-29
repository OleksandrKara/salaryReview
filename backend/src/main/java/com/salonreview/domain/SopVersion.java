package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An immutable content snapshot of a SOP. Numbered per SOP from 1; never updated after creation
 * (content changes happen by adding a new version). A version becomes eligible to be live when an
 * owner publishes it.
 */
@Entity
@Table(name = "sop_versions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SopVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sop_id", nullable = false)
    private Long sopId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** Russian translation of {@link #body}; null when not translated (English is shown as fallback). */
    @Column(name = "body_ru", columnDefinition = "text")
    private String bodyRu;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SopVersionStatus status = SopVersionStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = SopVersionStatus.DRAFT;
    }
}
