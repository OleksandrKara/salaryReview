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

    /** Russian translation of {@link #title}; null falls back to {@link #title}. */
    @Column(name = "title_ru", length = 512)
    private String titleRu;

    @Column(nullable = false, length = 128)
    private String category;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

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

    /** Onboarding sort order — lower shows first; unset SOPs default high so they sort after the ones
     *  an owner has prioritized (see V36). */
    @Column(nullable = false)
    @Builder.Default
    private int priority = 1000;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    // --- RAG sync (V29): pushes the current published version into the assistant's corpus ---

    /** The rag_document holding this SOP's synced content; null when not synced. */
    @Column(name = "rag_doc_id")
    private Long ragDocId;

    /** Which version was synced — when this differs from currentVersionId, the sync is stale. */
    @Column(name = "synced_version_id")
    private Long syncedVersionId;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_synced_by")
    private String lastSyncedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 32)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.NOT_SYNCED;

    @Column(name = "last_sync_error")
    private String lastSyncError;

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
