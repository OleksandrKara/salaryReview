package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An uploaded source document for the RAG knowledge assistant. Lands {@link RagDocumentStatus#PENDING}
 * and is not chunked/classified/embedded until an OWNER approves it. Deleting a document cascades to
 * its {@link RagChunk}s (and their vectors) while a {@link RagRedactionAudit} row survives.
 */
@Entity
@Table(name = "rag_document")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String filename;

    /** PDF | MARKDOWN | TEXT — how the bytes were parsed. */
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    /** Plain text extracted at upload (for admin preview + approval-time chunking). */
    @Column(name = "extracted_text", nullable = false, columnDefinition = "text")
    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RagDocumentStatus status;

    /** Free-text reason when status is FAILED (parse/ingest error). Null otherwise. */
    @Column(name = "status_detail", columnDefinition = "text")
    private String statusDetail;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = RagDocumentStatus.PENDING;
    }
}
