package com.salonreview.rag;

import com.salonreview.domain.RagAgentConfig;
import com.salonreview.repo.ChunkMatch;
import com.salonreview.repo.RagChunkRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Embeds a question and fetches the top-k nearest INDEXED chunks within the active config's distance
 * floor. An out-of-corpus question matches nothing → empty list → a "don't know" answer upstream.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagRetrievalService {

    private final VoyageClient voyage;
    private final RagChunkRepository chunks;

    public RagRetrievalService(VoyageClient voyage, RagChunkRepository chunks) {
        this.voyage = voyage;
        this.chunks = chunks;
    }

    public List<ChunkMatch> retrieve(String question, RagAgentConfig cfg, Long businessId) {
        float[] q = voyage.embedQuery(question);
        return chunks.searchNearest(
                VoyageClient.toVectorLiteral(q),
                cfg.getDistanceThreshold().doubleValue(),
                cfg.getK(),
                businessId);
    }
}
