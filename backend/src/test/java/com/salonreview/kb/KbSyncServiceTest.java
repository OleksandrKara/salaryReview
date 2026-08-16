package com.salonreview.kb;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.RagChunkStatus;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.Role;
import com.salonreview.domain.SyncStatus;
import com.salonreview.rag.RagIngestionService;
import com.salonreview.repo.KbArticleRepository;
import com.salonreview.repo.RagChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link KbSyncService}: clean sync, all-or-nothing PII rollback, skips, concurrency guard. */
class KbSyncServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private KbArticleRepository repo;
    @SuppressWarnings("unchecked")
    private ObjectProvider<RagIngestionService> ragProvider = mock(ObjectProvider.class);
    private RagIngestionService rag;
    private RagChunkRepository ragChunks;
    private KbSyncService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(KbArticleRepository.class);
        ragProvider = mock(ObjectProvider.class);
        rag = mock(RagIngestionService.class);
        ragChunks = mock(RagChunkRepository.class);
        when(ragProvider.getIfAvailable()).thenReturn(rag);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new KbSyncService(repo, ragProvider, ragChunks);
    }

    private KbArticle article(String body, Long ragDocId, SyncStatus status) {
        return KbArticle.builder().id(7L).title("FAQ").category("FAQ").body(body)
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash("stale-hash")
                .ragDocId(ragDocId).syncStatus(status).createdBy("owner").build();
    }

    @Test
    @DisplayName("clean content → SYNCED with a rag_doc_id and refreshed hash")
    void cleanSync() {
        when(repo.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(article("the no-show fee is $25", null, SyncStatus.NOT_SYNCED)));
        when(rag.upload(anyString(), any(), anyString())).thenReturn(RagDocument.builder().id(10L).build());
        when(ragChunks.countByDocumentIdAndStatus(10L, RagChunkStatus.QUARANTINED)).thenReturn(0L);
        when(ragChunks.countByDocumentIdAndStatus(10L, RagChunkStatus.INDEXED)).thenReturn(2L);

        KbArticle out = service.syncOne(7L, "owner", BUSINESS_ID).orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(out.getRagDocId()).isEqualTo(10L);
        assertThat(out.getLastSyncError()).isNull();
        assertThat(out.getContentHash()).isEqualTo(KbArticleService.contentHash("the no-show fee is $25"));
    }

    @Test
    @DisplayName("any quarantined chunk → whole article rejected (ERROR), RAG doc rolled back, no rag_doc_id")
    void piiRejectedAllOrNothing() {
        when(repo.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(article("call client 555-1212", null, SyncStatus.NOT_SYNCED)));
        when(rag.upload(anyString(), any(), anyString())).thenReturn(RagDocument.builder().id(10L).build());
        when(ragChunks.countByDocumentIdAndStatus(10L, RagChunkStatus.QUARANTINED)).thenReturn(1L);
        when(ragChunks.countByDocumentIdAndStatus(10L, RagChunkStatus.INDEXED)).thenReturn(0L);

        KbArticle out = service.syncOne(7L, "owner", BUSINESS_ID).orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(out.getRagDocId()).isNull();
        assertThat(out.getLastSyncError()).contains("PII");
        verify(rag).delete(eq(10L), any());           // the just-created doc is rolled back
    }

    @Test
    @DisplayName("blank body → skipped, nothing embedded")
    void blankBodySkipped() {
        when(repo.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(article("   ", null, SyncStatus.NOT_SYNCED)));

        KbArticle out = service.syncOne(7L, "owner", BUSINESS_ID).orElseThrow();

        assertThat(out.getLastSyncError()).contains("empty");
        verify(rag, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("re-sync retires the prior RAG document before creating a new one")
    void reSyncRetiresPriorDoc() {
        when(repo.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(article("fresh content", 5L, SyncStatus.CHANGED)));
        when(rag.upload(anyString(), any(), anyString())).thenReturn(RagDocument.builder().id(11L).build());
        when(ragChunks.countByDocumentIdAndStatus(11L, RagChunkStatus.QUARANTINED)).thenReturn(0L);
        when(ragChunks.countByDocumentIdAndStatus(11L, RagChunkStatus.INDEXED)).thenReturn(1L);

        KbArticle out = service.syncOne(7L, "owner", BUSINESS_ID).orElseThrow();

        verify(rag).delete(eq(5L), any());             // prior doc retired
        assertThat(out.getRagDocId()).isEqualTo(11L);
        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    @DisplayName("RAG not enabled → ERROR with a clear message")
    void ragNotEnabled() {
        when(ragProvider.getIfAvailable()).thenReturn(null);
        when(repo.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(article("content", null, SyncStatus.NOT_SYNCED)));

        KbArticle out = service.syncOne(7L, "owner", BUSINESS_ID).orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(out.getLastSyncError()).contains("RAG ingestion is not enabled");
    }

    @Test
    @DisplayName("a second concurrent sync-all is rejected with SyncInProgressException (→ 409)")
    void concurrentSyncAllRejected() {
        // While the outer sync-all holds the lock (it has begun and called the repo), a re-entrant
        // call must be rejected — deterministic, single-threaded.
        when(repo.findPendingSyncByBusinessIdOrderByCategoryAscTitleAsc(eq(BUSINESS_ID), any())).thenAnswer(inv -> {
            assertThatThrownBy(() -> service.syncAll("owner", BUSINESS_ID))
                    .isInstanceOf(KbSyncService.SyncInProgressException.class);
            return List.of();
        });

        service.syncAll("owner", BUSINESS_ID);                       // outer call completes normally
        assertThat(service.isSyncAllRunning()).isFalse(); // lock released
    }
}
