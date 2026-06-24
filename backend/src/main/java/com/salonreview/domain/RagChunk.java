package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One chunk of an approved document. The PII/relevance gate runs BEFORE embedding, so a quarantined
 * chunk has a null embedding and is never sent to Voyage and never retrievable.
 *
 * <p><b>The {@code embedding vector(1024)} column is intentionally NOT mapped here.</b> Hibernate has
 * no native pgvector type and JPA runs with {@code ddl-auto: validate}; mapping it would force a
 * custom type. Instead the embedding is written and queried via native SQL in
 * {@link com.salonreview.repo.RagChunkRepository} (cast to {@code vector}, ordered by {@code <=>}).
 * {@code validate} only checks mapped fields, so leaving the column unmapped is safe.
 */
@Entity
@Table(name = "rag_chunk")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RagChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "text")
    private String chunkText;

    @Column(name = "char_start", nullable = false)
    private Integer charStart;

    @Column(name = "char_end", nullable = false)
    private Integer charEnd;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RagChunkStatus status;

    /** Why a chunk was quarantined (e.g. "pii:email,phone" | "irrelevant"). Null for INDEXED. */
    @Column(name = "quarantine_reason", columnDefinition = "text")
    private String quarantineReason;

    // NOTE: no `embedding` field — see the class javadoc. Written/read via native SQL only.

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
