package com.salonreview.sop;

import com.salonreview.domain.RagChunkStatus;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.Sop;
import com.salonreview.domain.SopAudience;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.domain.SyncStatus;
import com.salonreview.rag.RagIngestionService;
import com.salonreview.repo.RagChunkRepository;
import com.salonreview.repo.SopRepository;
import com.salonreview.repo.SopVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Syncs SOPs into the RAG store so the assistant can answer from them, reusing the same ingest/delete
 * paths and the all-or-nothing PII gate as KB sync ({@link com.salonreview.kb.KbSyncService}).
 *
 * <p>What syncs is the SOP's <b>current published version</b>; {@code synced_version_id} records which
 * version is in the store, so publishing a new version shows as {@code CHANGED} until re-synced. Only
 * ACTIVE SOPs with a published version are syncable — a SOP that is archived or has no published
 * version has its rag_document retired so stale policy never stays answerable. Bulk sync runs
 * sequentially and is guarded against concurrent runs.
 */
@Service
public class SopSyncService {

    private static final Logger log = LoggerFactory.getLogger(SopSyncService.class);

    private static final String PII_MESSAGE =
            "PII quarantine flagged this content — not synced. Remove personal data (client/staff "
                    + "names, emails, phone numbers) and try again.";

    private final SopRepository sops;
    private final SopVersionRepository versions;
    private final ObjectProvider<RagIngestionService> ragIngestionProvider;
    private final RagChunkRepository ragChunks;
    private final AtomicBoolean syncAllRunning = new AtomicBoolean(false);

    public SopSyncService(SopRepository sops, SopVersionRepository versions,
                          ObjectProvider<RagIngestionService> ragIngestionProvider, RagChunkRepository ragChunks) {
        this.sops = sops;
        this.versions = versions;
        this.ragIngestionProvider = ragIngestionProvider;
        this.ragChunks = ragChunks;
    }

    /** The syncable corpus: ACTIVE SOPs (newest categories first), for the admin section.
     * Provider-only SOPs are excluded entirely — the assistant is OWNER/MANAGER-only (see
     * SecurityConfig), so a provider-audience SOP has no reader who could ever ask about it;
     * listing it here would just be clutter with nothing to actually sync it for.
     */
    public List<Sop> list(Long businessId) {
        return sops.findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(businessId, SopStatus.ACTIVE).stream()
                .filter(s -> s.getAudience() != SopAudience.PROVIDER)
                .toList();
    }

    /** Sync one SOP. Returns the updated SOP (possibly ERROR); empty when it doesn't exist or isn't
     * this business's. */
    public Optional<Sop> syncOne(Long id, String by, Long businessId) {
        return sops.findByIdAndBusinessId(id, businessId).map(s -> doSync(s, by, businessId));
    }

    /**
     * Sync every SOP in one business, one at a time (archived/unpublished ones get retired). Rejects
     * a concurrent run with {@link SyncInProgressException} (→ 409). Returns the refreshed ACTIVE
     * list for the UI.
     */
    public List<Sop> syncAll(String by, Long businessId) {
        if (!syncAllRunning.compareAndSet(false, true)) {
            throw new SyncInProgressException();
        }
        try {
            sops.findAllByBusinessIdOrderByPriorityAscCategoryAscTitleAsc(businessId).forEach(s -> doSync(s, by, businessId));
            return list(businessId);
        } finally {
            syncAllRunning.set(false);
        }
    }

    public boolean isSyncAllRunning() {
        return syncAllRunning.get();
    }

    /** Display status: a SYNCED SOP whose live version moved past the synced one reads as CHANGED. */
    public SyncStatus effectiveStatus(Sop sop) {
        if (sop.getSyncStatus() == SyncStatus.SYNCED && sop.getSyncedVersionId() != null
                && !sop.getSyncedVersionId().equals(sop.getCurrentVersionId())) {
            return SyncStatus.CHANGED;
        }
        return sop.getSyncStatus();
    }

    /** Whether the SOP's current published version carries a Russian translation (drives the EN/RU chip). */
    public boolean currentVersionHasTranslation(Sop sop) {
        if (sop.getCurrentVersionId() == null) return false;
        return versions.findById(sop.getCurrentVersionId())
                .map(v -> v.getBodyRu() != null && !v.getBodyRu().isBlank())
                .orElse(false);
    }

    // ---------------------------------------------------------------- internals

    private Sop doSync(Sop sop, String by, Long businessId) {
        boolean isProviderOnly = sop.getAudience() == SopAudience.PROVIDER;
        boolean syncable = sop.getStatus() == SopStatus.ACTIVE && sop.getCurrentVersionId() != null && !isProviderOnly;

        // Archived, never-published, or provider-only: retire any prior doc so retired/irrelevant
        // policy isn't answerable — the assistant is OWNER/MANAGER-only, so a provider-audience
        // SOP has no reader who could ever ask about it (see SecurityConfig).
        if (!syncable) {
            if (sop.getRagDocId() != null) {
                RagIngestionService rag = ragIngestionProvider.getIfAvailable();
                if (rag != null) rag.delete(sop.getRagDocId(), by, businessId);
            }
            sop.setRagDocId(null);
            sop.setSyncedVersionId(null);
            sop.setSyncStatus(SyncStatus.NOT_SYNCED);
            sop.setLastSyncError(isProviderOnly
                    ? "Provider-only — the assistant isn't used by providers, so this isn't synced."
                    : sop.getStatus() != SopStatus.ACTIVE
                    ? "Archived — removed from the assistant."
                    : "No published version to sync yet.");
            return sops.save(sop);
        }

        // Already up to date — no-op.
        if (sop.getSyncStatus() == SyncStatus.SYNCED && sop.getRagDocId() != null
                && sop.getCurrentVersionId().equals(sop.getSyncedVersionId())) {
            return sop;
        }

        SopVersion version = versions.findById(sop.getCurrentVersionId()).orElse(null);
        String body = (version == null) ? null : version.getBody();
        if (body == null || body.isBlank()) {
            sop.setSyncStatus(SyncStatus.ERROR);
            sop.setLastSyncError("The published version has no content to sync.");
            return sops.save(sop);
        }

        RagIngestionService rag = ragIngestionProvider.getIfAvailable();
        if (rag == null) {
            sop.setSyncStatus(SyncStatus.ERROR);
            sop.setLastSyncError("RAG ingestion is not enabled (set RAG_ENABLED=true and a VOYAGE_API_KEY).");
            return sops.save(sop);
        }

        // Retire any prior RAG document first — no orphan, no duplicate.
        if (sop.getRagDocId() != null) {
            rag.delete(sop.getRagDocId(), by, businessId);
            sop.setRagDocId(null);
        }

        try {
            // Index both languages so the assistant can retrieve in either (English + any translation).
            String combined = body;
            if (version.getBodyRu() != null && !version.getBodyRu().isBlank()) {
                combined = combined + "\n\n---\n\n" + version.getBodyRu();
            }
            RagDocument doc = rag.upload(sop.getTitle() + ".md", combined.getBytes(StandardCharsets.UTF_8), by,
                    businessId);
            rag.approve(doc.getId(), businessId);

            long quarantined = ragChunks.countByDocumentIdAndStatus(doc.getId(), RagChunkStatus.QUARANTINED);
            long indexed = ragChunks.countByDocumentIdAndStatus(doc.getId(), RagChunkStatus.INDEXED);

            if (quarantined > 0 || indexed == 0) {
                rag.delete(doc.getId(), by, businessId);
                sop.setRagDocId(null);
                sop.setSyncStatus(SyncStatus.ERROR);
                sop.setLastSyncError(quarantined > 0 ? PII_MESSAGE : "Nothing could be indexed from this SOP.");
                return sops.save(sop);
            }

            sop.setRagDocId(doc.getId());
            sop.setSyncedVersionId(sop.getCurrentVersionId());
            sop.setLastSyncedAt(Instant.now());
            sop.setLastSyncedBy(by);
            sop.setSyncStatus(SyncStatus.SYNCED);
            sop.setLastSyncError(null);
            return sops.save(sop);
        } catch (Exception e) {
            log.error("SOP sync failed for {}: {}", sop.getId(), e.toString());
            sop.setSyncStatus(SyncStatus.ERROR);
            sop.setLastSyncError("Sync failed: " + e.getMessage());
            return sops.save(sop);
        }
    }

    /** Translates to 409 in the controller. */
    public static class SyncInProgressException extends RuntimeException {
        public SyncInProgressException() {
            super("A sync is already in progress.");
        }
    }
}
