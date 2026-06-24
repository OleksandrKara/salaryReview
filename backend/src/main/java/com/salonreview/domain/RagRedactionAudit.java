package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Audit trail that outlives a deleted document. Written when an OWNER deletes a {@link RagDocument}:
 * the document's chunks/vectors are cascade-removed (no longer retrievable), but this row remains as
 * a record of what was purged, by whom, and when. Intentionally NOT a foreign key — it must survive
 * the row it refers to.
 */
@Entity
@Table(name = "rag_redaction_audit")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RagRedactionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 512)
    private String filename;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    @Column(name = "deleted_by", nullable = false)
    private String deletedBy;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @PrePersist
    void prePersist() {
        if (deletedAt == null) deletedAt = Instant.now();
    }
}
