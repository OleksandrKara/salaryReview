package com.salonreview.kb;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.RagChunkStatus;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.SyncStatus;
import com.salonreview.rag.RagIngestionService;
import com.salonreview.repo.KbArticleRepository;
import com.salonreview.repo.RagChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Syncs KB articles into the RAG store, reusing the existing ingest/delete paths. Per the KB spec the
 * PII gate is <b>all-or-nothing</b>: if the existing per-chunk classifier quarantines any chunk, the
 * whole article is rejected ({@code ERROR}) and nothing is left in the RAG store. Bulk sync runs
 * sequentially and is guarded against concurrent runs.
 */
@Service
public class KbSyncService {

    private static final Logger log = LoggerFactory.getLogger(KbSyncService.class);

    private static final String PII_MESSAGE =
            "PII quarantine flagged this content — not synced. Remove personal data (client/staff "
                    + "names, emails, phone numbers) and try again.";

    private final KbArticleRepository repo;
    private final ObjectProvider<RagIngestionService> ragIngestionProvider;
    private final RagChunkRepository ragChunks;
    private final AtomicBoolean syncAllRunning = new AtomicBoolean(false);

    public KbSyncService(KbArticleRepository repo, ObjectProvider<RagIngestionService> ragIngestionProvider,
                         RagChunkRepository ragChunks) {
        this.repo = repo;
        this.ragIngestionProvider = ragIngestionProvider;
        this.ragChunks = ragChunks;
    }

    /** Sync one article. Returns the updated article (possibly in ERROR). 404 handled by the caller. */
    public Optional<KbArticle> syncOne(Long id, String by) {
        return repo.findById(id).map(a -> doSync(a, by));
    }

    /**
     * Sync every article that is NOT_SYNCED / CHANGED / ERROR, one at a time. Rejects a concurrent
     * run with {@link SyncInProgressException} (→ 409).
     */
    public List<KbArticle> syncAll(String by) {
        if (!syncAllRunning.compareAndSet(false, true)) {
            throw new SyncInProgressException();
        }
        try {
            return repo.findBySyncStatusInOrderByCategoryAscTitleAsc(
                            EnumSet.of(SyncStatus.NOT_SYNCED, SyncStatus.CHANGED, SyncStatus.ERROR)).stream()
                    .map(a -> doSync(a, by))
                    .toList();
        } finally {
            syncAllRunning.set(false);
        }
    }

    /** Whether a bulk sync is currently running (for the UI). */
    public boolean isSyncAllRunning() {
        return syncAllRunning.get();
    }

    // ---------------------------------------------------------------- internals

    private KbArticle doSync(KbArticle a, String by) {
        String freshHash = KbArticleService.contentHash(a.getBody());

        // Already up to date — no-op (verify the hash, don't trust a possibly-stale flag).
        if (a.getSyncStatus() == SyncStatus.SYNCED && a.getRagDocId() != null
                && freshHash.equals(a.getContentHash())) {
            return a;
        }

        // Never embed nothing.
        if (a.getBody() == null || a.getBody().isBlank()) {
            a.setLastSyncError("Article body is empty — nothing to sync.");
            return repo.save(a);
        }

        RagIngestionService rag = ragIngestionProvider.getIfAvailable();
        if (rag == null) {
            a.setSyncStatus(SyncStatus.ERROR);
            a.setLastSyncError("RAG ingestion is not enabled (set RAG_ENABLED=true and a VOYAGE_API_KEY).");
            return repo.save(a);
        }

        // Retire any prior RAG document first — no orphan, no duplicate.
        if (a.getRagDocId() != null) {
            rag.delete(a.getRagDocId(), by);
            a.setRagDocId(null);
        }

        try {
            RagDocument doc = rag.upload(a.getTitle() + ".md",
                    a.getBody().getBytes(StandardCharsets.UTF_8), by);
            rag.approve(doc.getId());

            long quarantined = ragChunks.countByDocumentIdAndStatus(doc.getId(), RagChunkStatus.QUARANTINED);
            long indexed = ragChunks.countByDocumentIdAndStatus(doc.getId(), RagChunkStatus.INDEXED);

            if (quarantined > 0 || indexed == 0) {
                // All-or-nothing: any flagged (or nothing indexable) → reject the whole article.
                rag.delete(doc.getId(), by);
                a.setRagDocId(null);
                a.setSyncStatus(SyncStatus.ERROR);
                a.setLastSyncError(quarantined > 0 ? PII_MESSAGE
                        : "Nothing could be indexed from this article.");
                return repo.save(a);
            }

            a.setRagDocId(doc.getId());
            a.setContentHash(freshHash);
            a.setLastSyncedAt(Instant.now());
            a.setLastSyncedBy(by);
            a.setSyncStatus(SyncStatus.SYNCED);
            a.setLastSyncError(null);
            return repo.save(a);
        } catch (Exception e) {
            log.error("KB sync failed for article {}: {}", a.getId(), e.toString());
            a.setSyncStatus(SyncStatus.ERROR);
            a.setLastSyncError("Sync failed: " + e.getMessage());
            return repo.save(a);
        }
    }

    /** Translates to 409 in the controller. */
    public static class SyncInProgressException extends RuntimeException {
        public SyncInProgressException() {
            super("A sync is already in progress.");
        }
    }
}
