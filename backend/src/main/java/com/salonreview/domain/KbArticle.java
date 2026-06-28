package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Knowledge Base article — in-app informational content (service menus, scripts, FAQ) that
 * owners/managers edit and sync on demand into the RAG store. No approval workflow / version history
 * (unlike SOPs); {@code updatedAt} is the audit trail.
 *
 * <p>{@code visibleRoles} drives per-article read access (a provider sees only articles whose set
 * contains {@code PROVIDER}). {@code contentHash} detects edits since the last sync; {@code ragDocId}
 * links to this article's current {@code rag_document} (null when never synced).
 */
@Entity
@Table(name = "kb_articles")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class KbArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 128)
    private String category;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** Roles allowed to read this article. Stored as a JSON array of role names (e.g. ["OWNER"]). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_roles", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Role> visibleRoles = new ArrayList<>();

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** rag_document.id of this article's current RAG entry; null when never synced. */
    @Column(name = "rag_doc_id")
    private Long ragDocId;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_synced_by")
    private String lastSyncedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 32)
    @Builder.Default
    private SyncStatus syncStatus = SyncStatus.NOT_SYNCED;

    @Column(name = "last_sync_error", columnDefinition = "text")
    private String lastSyncError;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
