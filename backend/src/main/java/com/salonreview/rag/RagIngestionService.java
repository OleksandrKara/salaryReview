package com.salonreview.rag;

import com.salonreview.domain.RagChunk;
import com.salonreview.domain.RagChunkStatus;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.RagDocumentStatus;
import com.salonreview.domain.RagRedactionAudit;
import com.salonreview.repo.RagChunkRepository;
import com.salonreview.repo.RagDocumentRepository;
import com.salonreview.repo.RagRedactionAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Owns the ingestion pipeline for the RAG knowledge assistant.
 *
 * <p>Upload extracts text (so a bad file is rejected immediately and the admin can preview it) and
 * stores the document PENDING. Approval runs the safety-first pipeline:
 * {@code chunk → classify → quarantine-or-pass → embed → store}. The classifier runs BEFORE the
 * Voyage call, so a chunk flagged PII/irrelevant is persisted QUARANTINED with a null embedding and
 * is never sent off-box.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private final RagDocumentRepository documents;
    private final RagChunkRepository chunks;
    private final RagRedactionAuditRepository audits;
    private final DocumentTextExtractor extractor;
    private final Chunker chunker;
    private final ChunkClassifier classifier;
    private final VoyageClient voyage;

    public RagIngestionService(RagDocumentRepository documents, RagChunkRepository chunks,
                               RagRedactionAuditRepository audits, DocumentTextExtractor extractor,
                               Chunker chunker, ChunkClassifier classifier, VoyageClient voyage) {
        this.documents = documents;
        this.chunks = chunks;
        this.audits = audits;
        this.extractor = extractor;
        this.chunker = chunker;
        this.classifier = classifier;
        this.voyage = voyage;
    }

    /** Extract text and store the document PENDING. Throws on unparseable input (→ 400). */
    @Transactional
    public RagDocument upload(String filename, byte[] bytes, String uploadedBy, Long businessId) {
        DocumentTextExtractor.Extracted extracted = extractor.extract(bytes, filename);
        if (extracted.text() == null || extracted.text().isBlank()) {
            throw new IllegalArgumentException("No text could be extracted from " + filename);
        }
        RagDocument doc = RagDocument.builder()
                .businessId(businessId)
                .filename(filename)
                .sourceType(extracted.sourceType())
                .extractedText(extracted.text())
                .status(RagDocumentStatus.PENDING)
                .uploadedBy(uploadedBy)
                .build();
        return documents.save(doc);
    }

    /**
     * Approve a PENDING document and run ingestion. Idempotent guard: only PENDING documents are
     * ingested. Returns the updated document; empty when it doesn't exist or isn't this business's.
     */
    @Transactional
    public Optional<RagDocument> approve(Long documentId, Long businessId) {
        Optional<RagDocument> found = documents.findByIdAndBusinessId(documentId, businessId);
        if (found.isEmpty()) return Optional.empty();
        RagDocument doc = found.get();
        if (doc.getStatus() != RagDocumentStatus.PENDING) return Optional.of(doc);

        doc.setStatus(RagDocumentStatus.INDEXING);
        documents.save(doc);

        try {
            List<Chunk> pieces = chunker.chunk(doc.getExtractedText());

            // Phase 1: classify every chunk and persist rows. Quarantined chunks are saved with a null
            // embedding and never sent to Voyage — the safety gate runs first.
            List<RagChunk> toEmbed = new ArrayList<>(pieces.size());
            int ordinal = 0;
            for (Chunk piece : pieces) {
                ChunkClassification verdict = classifier.classify(piece.text());
                RagChunk row = RagChunk.builder()
                        .documentId(doc.getId())
                        .ordinal(ordinal++)
                        .chunkText(piece.text())
                        .charStart(piece.charStart())
                        .charEnd(piece.charEnd())
                        .contentSha256(sha256(piece.text()))
                        .status(verdict.isQuarantined() ? RagChunkStatus.QUARANTINED : RagChunkStatus.INDEXED)
                        .quarantineReason(verdict.isQuarantined() ? verdict.quarantineReason() : null)
                        .build();
                row = chunks.save(row);
                if (!verdict.isQuarantined()) toEmbed.add(row);
            }

            // Phase 2: embed all non-quarantined chunks in ONE Voyage API call (free tier = 3 RPM;
            // batching keeps each document to a single request regardless of chunk count).
            if (!toEmbed.isEmpty()) {
                List<String> texts = toEmbed.stream().map(RagChunk::getChunkText).toList();
                List<float[]> vecs = voyage.embedDocuments(texts);
                for (int i = 0; i < toEmbed.size(); i++) {
                    chunks.updateEmbedding(toEmbed.get(i).getId(), VoyageClient.toVectorLiteral(vecs.get(i)));
                }
            }

            int indexed = toEmbed.size();
            doc.setStatus(indexed > 0 ? RagDocumentStatus.INDEXED : RagDocumentStatus.QUARANTINED);
            doc.setIndexedAt(Instant.now());
            return Optional.of(documents.save(doc));
        } catch (Exception e) {
            log.error("Ingestion failed for document {}: {}", documentId, e.toString());
            doc.setStatus(RagDocumentStatus.FAILED);
            doc.setStatusDetail(e.getMessage());
            return Optional.of(documents.save(doc));
        }
    }

    /**
     * Delete a document: write a redaction audit row, then delete the document (its chunks + vectors
     * cascade away via the FK, so the content is no longer retrievable). Returns false when not
     * found or not this business's.
     */
    @Transactional
    public boolean delete(Long documentId, String deletedBy, Long businessId) {
        Optional<RagDocument> found = documents.findByIdAndBusinessId(documentId, businessId);
        if (found.isEmpty()) return false;
        RagDocument doc = found.get();

        long chunkCount = chunks.countByDocumentIdAndStatus(doc.getId(), RagChunkStatus.INDEXED)
                + chunks.countByDocumentIdAndStatus(doc.getId(), RagChunkStatus.QUARANTINED);
        audits.save(RagRedactionAudit.builder()
                .businessId(businessId)
                .documentId(doc.getId())
                .filename(doc.getFilename())
                .chunkCount((int) chunkCount)
                .deletedBy(deletedBy)
                .build());

        documents.delete(doc); // ON DELETE CASCADE removes rag_chunk rows + their vectors
        return true;
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
