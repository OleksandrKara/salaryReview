package com.salonreview.kb;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.Role;
import com.salonreview.domain.SyncStatus;
import com.salonreview.rag.RagIngestionService;
import com.salonreview.repo.KbArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link KbArticleService}: hashing/status, visibility defaults + read filtering, delete cleanup. */
class KbArticleServiceTest {

    private KbArticleRepository repo;
    @SuppressWarnings("unchecked")
    private ObjectProvider<RagIngestionService> ragProvider = mock(ObjectProvider.class);
    private RagIngestionService rag;
    private KbArticleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(KbArticleRepository.class);
        ragProvider = mock(ObjectProvider.class);
        rag = mock(RagIngestionService.class);
        when(ragProvider.getIfAvailable()).thenReturn(rag);
        when(repo.save(any())).thenAnswer(inv -> {
            KbArticle a = inv.getArgument(0);
            if (a.getId() == null) a.setId(1L);
            return a;
        });
        service = new KbArticleService(repo, ragProvider);
    }

    @Test
    @DisplayName("create defaults visible_roles to {OWNER,MANAGER}, status NOT_SYNCED, hash set")
    void createDefaults() {
        KbArticle a = service.create("FAQ", "FAQ", "body text", null, null, "owner");
        assertThat(a.getVisibleRoles()).containsExactlyInAnyOrder(Role.OWNER, Role.MANAGER);
        assertThat(a.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(a.getContentHash()).isNotBlank();
    }

    @Test
    @DisplayName("assigning PROVIDER keeps the admin roles and adds PROVIDER")
    void assignProviderKeepsAdmins() {
        KbArticle a = service.create("Menu", "Services", "x", null, List.of(Role.PROVIDER), "owner");
        assertThat(a.getVisibleRoles()).containsExactlyInAnyOrder(Role.OWNER, Role.MANAGER, Role.PROVIDER);
    }

    @Test
    @DisplayName("editing the body of a SYNCED article marks it CHANGED; no embedding happens on save")
    void editSyncedMarksChanged() {
        KbArticle existing = KbArticle.builder().id(7L).title("t").category("c").body("old")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash(KbArticleService.contentHash("old"))
                .ragDocId(99L).syncStatus(SyncStatus.SYNCED).createdBy("owner").build();
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        KbArticle updated = service.update(7L, "t", "c", "new body", null, null).orElseThrow();

        assertThat(updated.getSyncStatus()).isEqualTo(SyncStatus.CHANGED);
        verify(rag, never()).upload(any(), any(), any()); // save never embeds
    }

    @Test
    @DisplayName("editing without changing the body keeps the status")
    void editUnchangedKeepsStatus() {
        KbArticle existing = KbArticle.builder().id(7L).title("t").category("c").body("same")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash(KbArticleService.contentHash("same"))
                .ragDocId(99L).syncStatus(SyncStatus.SYNCED).createdBy("owner").build();
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        KbArticle updated = service.update(7L, "t2", "c2", "same", null, null).orElseThrow();

        assertThat(updated.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
    }

    @Test
    @DisplayName("adding a Russian translation to a SYNCED article marks it CHANGED")
    void editRussianMarksChanged() {
        KbArticle existing = KbArticle.builder().id(7L).title("t").category("c").body("same")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash(KbArticleService.contentHash("same"))
                .ragDocId(99L).syncStatus(SyncStatus.SYNCED).createdBy("owner").build();
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        // English unchanged, but a Russian body is added → the combined hash moves → CHANGED.
        KbArticle updated = service.update(7L, "t", "c", "same", "перевод", null).orElseThrow();

        assertThat(updated.getBodyRu()).isEqualTo("перевод");
        assertThat(updated.getSyncStatus()).isEqualTo(SyncStatus.CHANGED);
    }

    @Test
    @DisplayName("editing a never-synced article marks it NOT_SYNCED (not CHANGED)")
    void editNeverSyncedMarksNotSynced() {
        KbArticle existing = KbArticle.builder().id(7L).title("t").category("c").body("old")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash(KbArticleService.contentHash("old"))
                .ragDocId(null).syncStatus(SyncStatus.NOT_SYNCED).createdBy("owner").build();
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        KbArticle updated = service.update(7L, "t", "c", "changed", null, null).orElseThrow();

        assertThat(updated.getSyncStatus()).isEqualTo(SyncStatus.NOT_SYNCED);
    }

    @Test
    @DisplayName("delete retires the linked RAG document, then removes the row")
    void deleteRetiresRagDoc() {
        KbArticle a = KbArticle.builder().id(7L).title("t").category("c").body("x")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash("h").ragDocId(55L)
                .syncStatus(SyncStatus.SYNCED).createdBy("owner").build();
        when(repo.findById(7L)).thenReturn(Optional.of(a));

        assertThat(service.delete(7L, "owner")).isTrue();
        verify(rag).delete(eq(55L), any());
        verify(repo).delete(a);
    }

    @Test
    @DisplayName("delete of an unsynced article does not call RAG")
    void deleteUnsyncedNoRag() {
        KbArticle a = KbArticle.builder().id(7L).title("t").category("c").body("x")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash("h").ragDocId(null)
                .syncStatus(SyncStatus.NOT_SYNCED).createdBy("owner").build();
        when(repo.findById(7L)).thenReturn(Optional.of(a));

        service.delete(7L, "owner");
        verify(rag, never()).delete(any(), any());
    }

    @Test
    @DisplayName("read filtering: provider sees only shared articles; owner sees all")
    void readFiltering() {
        KbArticle ownerOnly = KbArticle.builder().id(1L).title("a").category("c").body("x")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER)).contentHash("h").syncStatus(SyncStatus.SYNCED)
                .createdBy("owner").build();
        KbArticle shared = KbArticle.builder().id(2L).title("b").category("c").body("x")
                .visibleRoles(List.of(Role.OWNER, Role.MANAGER, Role.PROVIDER)).contentHash("h")
                .syncStatus(SyncStatus.SYNCED).createdBy("owner").build();
        when(repo.findAllByOrderByCategoryAscTitleAsc()).thenReturn(List.of(ownerOnly, shared));

        assertThat(service.list(Role.OWNER)).hasSize(2);
        assertThat(service.list(Role.PROVIDER)).extracting(KbArticle::getId).containsExactly(2L);

        when(repo.findById(1L)).thenReturn(Optional.of(ownerOnly));
        assertThat(service.get(1L, Role.PROVIDER)).isEmpty();      // not shared with provider
        assertThat(service.get(1L, Role.OWNER)).isPresent();
    }
}
