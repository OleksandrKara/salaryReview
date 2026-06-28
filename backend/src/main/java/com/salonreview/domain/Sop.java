package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The stable identity of a Standard Operating Procedure. Content lives in {@link SopVersion}s;
 * {@code currentVersionId} points at the live (published) version, null until first publish.
 * FK columns are mapped as plain Longs (like {@code RagChunk}) to keep the entity graph simple and
 * avoid circular lazy-loading between this and {@code sop_versions}.
 */
@Entity
@Table(name = "sops")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Sop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 128)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SopAudience audience;

    /** Live version id; null until first publish. */
    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SopStatus status = SopStatus.ACTIVE;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = SopStatus.ACTIVE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
