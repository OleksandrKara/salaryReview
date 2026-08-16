package com.salonreview.repo;

import com.salonreview.domain.RagChunk;
import com.salonreview.domain.RagChunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Chunk persistence + pgvector search. The {@code embedding} column is unmapped on the entity (see
 * {@link RagChunk}), so it is written and queried here via native SQL — the only place that knows
 * about the {@code vector} type.
 */
public interface RagChunkRepository extends JpaRepository<RagChunk, Long> {

    long countByDocumentIdAndStatus(Long documentId, RagChunkStatus status);

    /**
     * Attach an embedding to an already-persisted INDEXED chunk. {@code vec} is a pgvector literal
     * (e.g. {@code "[0.1,0.2,...]"}); it is cast to {@code vector} server-side. Only called for
     * chunks that passed the PII/relevance gate — quarantined chunks keep a null embedding.
     */
    @Modifying
    @Query(value = "UPDATE rag_chunk SET embedding = CAST(:vec AS vector) WHERE id = :id",
            nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("vec") String vec);

    /**
     * Top-k nearest INDEXED chunks to the query vector, within {@code maxDistance} (cosine), scoped
     * to one business (joined through {@code rag_document.business_id} — rag_chunk has no
     * business_id column of its own). The distance filter is the "say you don't know" floor: an
     * out-of-corpus question matches nothing and yields empty context. Quarantined chunks have a
     * null embedding and never match.
     */
    @Query(value = """
            SELECT c.id AS id,
                   c.document_id AS documentId,
                   c.chunk_text AS chunkText,
                   (c.embedding <=> CAST(:vec AS vector)) AS distance
              FROM rag_chunk c
              JOIN rag_document d ON d.id = c.document_id
             WHERE d.business_id = :businessId
               AND c.status = 'INDEXED'
               AND c.embedding IS NOT NULL
               AND (c.embedding <=> CAST(:vec AS vector)) <= :maxDistance
             ORDER BY c.embedding <=> CAST(:vec AS vector)
             LIMIT :k
            """, nativeQuery = true)
    List<ChunkMatch> searchNearest(@Param("vec") String vec,
                                   @Param("maxDistance") double maxDistance,
                                   @Param("k") int k,
                                   @Param("businessId") Long businessId);
}
