package com.salonreview.repo;

/**
 * Projection for a nearest-neighbour search hit. Native-query column aliases ({@code id},
 * {@code documentId}, {@code chunkText}, {@code distance}) map to these getters.
 */
public interface ChunkMatch {
    Long getId();

    Long getDocumentId();

    String getChunkText();

    /** Cosine distance to the query vector (0 = identical, larger = less similar). */
    double getDistance();
}
