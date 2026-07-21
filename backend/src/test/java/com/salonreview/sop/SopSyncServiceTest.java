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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SopSyncService}: ingest success, all-or-nothing PII, no-op, retire, status. */
class SopSyncServiceTest {

    private final SopRepository sops = mock(SopRepository.class);
    private final SopVersionRepository versions = mock(SopVersionRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RagIngestionService> ragProvider = mock(ObjectProvider.class);
    private final RagIngestionService rag = mock(RagIngestionService.class);
    private final RagChunkRepository ragChunks = mock(RagChunkRepository.class);
    private SopSyncService service;

    @BeforeEach
    void setUp() {
        when(sops.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ragProvider.getIfAvailable()).thenReturn(rag);
        service = new SopSyncService(sops, versions, ragProvider, ragChunks);
    }

    // BOTH (not PROVIDER) — provider-only SOPs are deliberately excluded from sync (see
    // providerOnlyNeverSynced below), so the general-behavior tests need an audience that stays
    // syncable.
    private Sop activeSop(Long currentVersionId) {
        return Sop.builder().id(1L).title("Cleaning").category("Hygiene").audience(SopAudience.BOTH)
                .status(SopStatus.ACTIVE).currentVersionId(currentVersionId).createdBy("owner")
                .syncStatus(SyncStatus.NOT_SYNCED).build();
    }

    private void stubVersionBody(Long versionId, String body) {
        when(versions.findById(versionId)).thenReturn(Optional.of(
                SopVersion.builder().id(versionId).sopId(1L).versionNumber(1).body(body).build()));
    }

    @Test
    @DisplayName("syncs the current published version → SYNCED with rag doc + version recorded")
    void syncSuccess() {
        Sop sop = activeSop(100L);
        when(sops.findById(1L)).thenReturn(Optional.of(sop));
        stubVersionBody(100L, "Wash hands before each client.");
        when(rag.upload(eq("Cleaning.md"), any(), eq("owner"))).thenReturn(RagDocument.builder().id(55L).build());
        when(ragChunks.countByDocumentIdAndStatus(55L, RagChunkStatus.QUARANTINED)).thenReturn(0L);
        when(ragChunks.countByDocumentIdAndStatus(55L, RagChunkStatus.INDEXED)).thenReturn(3L);

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(out.getRagDocId()).isEqualTo(55L);
        assertThat(out.getSyncedVersionId()).isEqualTo(100L);
        assertThat(out.getLastSyncError()).isNull();
        verify(rag).approve(55L);
    }

    @Test
    @DisplayName("all-or-nothing: any quarantined chunk rejects the whole SOP and deletes the doc")
    void piiRejected() {
        Sop sop = activeSop(100L);
        when(sops.findById(1L)).thenReturn(Optional.of(sop));
        stubVersionBody(100L, "Call the client at 555-123-4567.");
        when(rag.upload(any(), any(), any())).thenReturn(RagDocument.builder().id(55L).build());
        when(ragChunks.countByDocumentIdAndStatus(55L, RagChunkStatus.QUARANTINED)).thenReturn(1L);
        when(ragChunks.countByDocumentIdAndStatus(55L, RagChunkStatus.INDEXED)).thenReturn(2L);

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(out.getRagDocId()).isNull();
        assertThat(out.getLastSyncError()).contains("PII");
        verify(rag).delete(55L, "owner");
    }

    @Test
    @DisplayName("already synced to the current version → no-op (no re-upload, no save)")
    void upToDateNoOp() {
        Sop sop = activeSop(100L);
        sop.setSyncStatus(SyncStatus.SYNCED);
        sop.setRagDocId(55L);
        sop.setSyncedVersionId(100L);
        when(sops.findById(1L)).thenReturn(Optional.of(sop));

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
        verify(rag, never()).upload(any(), any(), any());
        verify(sops, never()).save(any());
    }

    @Test
    @DisplayName("RAG not enabled → ERROR, no crash")
    void ragUnavailable() {
        when(ragProvider.getIfAvailable()).thenReturn(null);
        Sop sop = activeSop(100L);
        when(sops.findById(1L)).thenReturn(Optional.of(sop));
        stubVersionBody(100L, "Some policy text.");

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.ERROR);
        assertThat(out.getLastSyncError()).contains("RAG");
    }

    @Test
    @DisplayName("archived SOP retires its rag document and resets to NOT_SYNCED")
    void archivedRetires() {
        Sop sop = Sop.builder().id(1L).title("Old policy").category("c").audience(SopAudience.BOTH)
                .status(SopStatus.ARCHIVED).currentVersionId(100L).createdBy("owner")
                .ragDocId(55L).syncedVersionId(100L).syncStatus(SyncStatus.SYNCED).build();
        when(sops.findById(1L)).thenReturn(Optional.of(sop));

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        verify(rag).delete(55L, "owner");
        assertThat(out.getRagDocId()).isNull();
        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
        verify(rag, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("synced version older than the live version reads as CHANGED")
    void effectiveStatusChanged() {
        Sop sop = activeSop(101L); // live version is 101
        sop.setSyncStatus(SyncStatus.SYNCED);
        sop.setSyncedVersionId(100L); // but 100 was synced

        assertThat(service.effectiveStatus(sop)).isEqualTo(SyncStatus.CHANGED);
    }

    @Test
    @DisplayName("provider-only SOP is never synced, even if active with a published version")
    void providerOnlyNeverSynced() {
        Sop sop = Sop.builder().id(1L).title("Clock-in steps").category("c").audience(SopAudience.PROVIDER)
                .status(SopStatus.ACTIVE).currentVersionId(100L).createdBy("owner")
                .syncStatus(SyncStatus.NOT_SYNCED).build();
        when(sops.findById(1L)).thenReturn(Optional.of(sop));

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(out.getLastSyncError()).contains("Provider-only");
        verify(rag, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("a provider-only SOP that was previously synced (e.g. audience narrowed after the fact) gets retired")
    void providerOnlyRetiresExistingSync() {
        Sop sop = Sop.builder().id(1L).title("Clock-in steps").category("c").audience(SopAudience.PROVIDER)
                .status(SopStatus.ACTIVE).currentVersionId(100L).createdBy("owner")
                .ragDocId(55L).syncedVersionId(100L).syncStatus(SyncStatus.SYNCED).build();
        when(sops.findById(1L)).thenReturn(Optional.of(sop));

        Sop out = service.syncOne(1L, "owner").orElseThrow();

        verify(rag).delete(55L, "owner");
        assertThat(out.getRagDocId()).isNull();
        assertThat(out.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
    }

    @Test
    @DisplayName("list() excludes provider-only SOPs from the admin page entirely")
    void listExcludesProviderOnly() {
        Sop managerSop = Sop.builder().id(1L).title("Manager thing").category("c").audience(SopAudience.MANAGER)
                .status(SopStatus.ACTIVE).createdBy("owner").build();
        Sop bothSop = Sop.builder().id(2L).title("Both thing").category("c").audience(SopAudience.BOTH)
                .status(SopStatus.ACTIVE).createdBy("owner").build();
        Sop providerSop = Sop.builder().id(3L).title("Provider thing").category("c").audience(SopAudience.PROVIDER)
                .status(SopStatus.ACTIVE).createdBy("owner").build();
        when(sops.findByStatusOrderByPriorityAscCategoryAscTitleAsc(SopStatus.ACTIVE))
                .thenReturn(java.util.List.of(managerSop, bothSop, providerSop));

        assertThat(service.list()).extracting(Sop::getId).containsExactly(1L, 2L);
    }
}
