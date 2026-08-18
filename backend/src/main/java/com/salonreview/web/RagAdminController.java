package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.RagAgentConfig;
import com.salonreview.domain.RagChunkStatus;
import com.salonreview.domain.RagDocument;
import com.salonreview.rag.RagConfigService;
import com.salonreview.rag.RagIngestionService;
import com.salonreview.repo.RagChunkRepository;
import com.salonreview.repo.RagDocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * OWNER-only admin surface for the RAG corpus. Sits under {@code /api/rag/admin/**}, gated to OWNER
 * in {@code SecurityConfig}. Feature-flag gate: when {@code rag.enabled=false} every endpoint 404s.
 */
@RestController
@RequestMapping("/api/rag/admin")
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagAdminController {

    private final RagIngestionService ingestion;
    private final RagConfigService configService;
    private final RagDocumentRepository documents;
    private final RagChunkRepository chunks;
    private final RagProperties props;
    private final CurrentBusinessContext currentBusinessContext;

    public RagAdminController(RagIngestionService ingestion, RagConfigService configService,
                             RagDocumentRepository documents, RagChunkRepository chunks, RagProperties props,
                             CurrentBusinessContext currentBusinessContext) {
        this.ingestion = ingestion;
        this.configService = configService;
        this.documents = documents;
        this.chunks = chunks;
        this.props = props;
        this.currentBusinessContext = currentBusinessContext;
    }

    /** Upload a document. Extracts text immediately and stores it PENDING (awaiting approval). */
    @PostMapping("/documents")
    public ResponseEntity<DocumentSummary> upload(@RequestParam("file") MultipartFile file,
                                                  Principal principal) throws IOException {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        if (file.isEmpty()) return ResponseEntity.badRequest().build();
        try {
            RagDocument doc = ingestion.upload(file.getOriginalFilename(), file.getBytes(), principal.getName(),
                    currentBusinessContext.id());
            return ResponseEntity.ok(toSummary(doc));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    /** List all documents, newest first, with chunk/quarantine counts for the admin view. */
    @GetMapping("/documents")
    public ResponseEntity<List<DocumentSummary>> list() {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(documents.findAllByBusinessIdOrderByCreatedAtDesc(currentBusinessContext.id())
                .stream().map(this::toSummary).toList());
    }

    /** Approve a PENDING document → run ingestion (chunk → classify → embed). */
    @PostMapping("/documents/{id}/approve")
    public ResponseEntity<DocumentSummary> approve(@PathVariable Long id) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return ingestion.approve(id, currentBusinessContext.id()).map(d -> ResponseEntity.ok(toSummary(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Delete a document (cascades chunks/vectors; writes a redaction audit row). */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return ingestion.delete(id, principal.getName(), currentBusinessContext.id())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Read the active agent config — 404 both when RAG is globally disabled and when this
     * specific business has no active config yet (not yet onboarded onto RAG, tasks.md 7.4)
     * rather than the 500 a missing row used to produce. */
    @GetMapping("/config")
    public ResponseEntity<ConfigDto> getConfig() {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return configService.findActive(currentBusinessContext.id())
                .map(cfg -> ResponseEntity.ok(toDto(cfg)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Create a new active config version (does not mutate the previous one). */
    @PostMapping("/config")
    public ResponseEntity<ConfigDto> updateConfig(@RequestBody ConfigRequest body) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        RagAgentConfig created = configService.createVersion(
                body.systemPrompt(), body.model(), body.temperature(), body.k(), body.distanceThreshold(),
                currentBusinessContext.id());
        return ResponseEntity.ok(toDto(created));
    }

    private DocumentSummary toSummary(RagDocument d) {
        long indexed = chunks.countByDocumentIdAndStatus(d.getId(), RagChunkStatus.INDEXED);
        long quarantined = chunks.countByDocumentIdAndStatus(d.getId(), RagChunkStatus.QUARANTINED);
        return new DocumentSummary(d.getId(), d.getFilename(), d.getSourceType(), d.getStatus().name(),
                d.getStatusDetail(), indexed, quarantined, d.getCreatedAt(), d.getIndexedAt());
    }

    private static ConfigDto toDto(RagAgentConfig c) {
        return new ConfigDto(c.getVersion(), c.getSystemPrompt(), c.getModel(), c.getTemperature(),
                c.getK(), c.getDistanceThreshold());
    }

    public record DocumentSummary(Long id, String filename, String sourceType, String status,
                                  String statusDetail, long indexedChunks, long quarantinedChunks,
                                  Instant createdAt, Instant indexedAt) {}

    public record ConfigDto(Integer version, String systemPrompt, String model, BigDecimal temperature,
                            Integer k, BigDecimal distanceThreshold) {}

    public record ConfigRequest(String systemPrompt, String model, BigDecimal temperature,
                                Integer k, BigDecimal distanceThreshold) {}
}
