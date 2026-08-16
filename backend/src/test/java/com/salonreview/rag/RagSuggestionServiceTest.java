package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.Language;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.RagDocumentStatus;
import com.salonreview.domain.RagSuggestionCache;
import com.salonreview.domain.RagSuggestionCacheId;
import com.salonreview.repo.RagDocumentRepository;
import com.salonreview.repo.RagSuggestionCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the deterministic gating/empty paths of {@link RagSuggestionService} (no LLM). */
class RagSuggestionServiceTest {

    private static final Long BUSINESS_ID = 1L;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AnthropicClient> clientProvider = mock(ObjectProvider.class);
    private final RagDocumentRepository documents = mock(RagDocumentRepository.class);
    private final RagSuggestionCacheRepository cacheRepo = mock(RagSuggestionCacheRepository.class);
    private RagProperties props;
    private RagSuggestionService service;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.setEnabled(true);
        when(cacheRepo.findById(any())).thenReturn(Optional.empty());
        service = new RagSuggestionService(clientProvider, documents, cacheRepo, props);
    }

    @Test
    @DisplayName("flag off → empty, corpus never queried")
    void flagOff() {
        props.getSuggestions().setEnabled(false);

        assertThat(service.get(Language.EN, BUSINESS_ID).topics()).isEmpty();
        verify(documents, never()).findByBusinessIdAndStatusOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("empty corpus → empty, no model call")
    void emptyCorpus() {
        when(documents.findByBusinessIdAndStatusOrderByCreatedAtDesc(BUSINESS_ID, RagDocumentStatus.INDEXED))
                .thenReturn(List.of());

        assertThat(service.get(Language.EN, BUSINESS_ID).topics()).isEmpty();
        verify(clientProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("indexed docs present but no Anthropic client and no cache → empty (no crash)")
    void noClient() {
        when(documents.findByBusinessIdAndStatusOrderByCreatedAtDesc(BUSINESS_ID, RagDocumentStatus.INDEXED))
                .thenReturn(List.of(
                RagDocument.builder().id(1L).filename("No-show policy.md").status(RagDocumentStatus.INDEXED).build()));
        when(clientProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.get(Language.EN, BUSINESS_ID).topics()).isEmpty();
    }

    @Test
    @DisplayName("get is permanent: a stored row is returned as-is, never regenerated")
    void getReturnsStoredWithoutRegenerating() {
        when(cacheRepo.findById(new RagSuggestionCacheId(BUSINESS_ID, "EN"))).thenReturn(Optional.of(RagSuggestionCache.builder()
                .businessId(BUSINESS_ID).language("EN").signature("sig")
                .payload("{\"topics\":[{\"label\":\"Policies\",\"questions\":[\"What's the no-show fee?\"]}]}")
                .build()));

        StarterSuggestions out = service.get(Language.EN, BUSINESS_ID);

        assertThat(out.topics()).hasSize(1);
        assertThat(out.topics().get(0).label()).isEqualTo("Policies");
        // Permanent: no corpus scan and no LLM when a row already exists.
        verify(documents, never()).findByBusinessIdAndStatusOrderByCreatedAtDesc(any(), any());
        verify(clientProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("refresh regenerates even when a row exists (re-scans the corpus)")
    void refreshRegenerates() {
        when(cacheRepo.findById(new RagSuggestionCacheId(BUSINESS_ID, "EN"))).thenReturn(Optional.of(RagSuggestionCache.builder()
                .businessId(BUSINESS_ID).language("EN").signature("sig").payload("{\"topics\":[]}").build()));
        when(documents.findByBusinessIdAndStatusOrderByCreatedAtDesc(BUSINESS_ID, RagDocumentStatus.INDEXED))
                .thenReturn(List.of(
                RagDocument.builder().id(1L).filename("No-show policy.md").status(RagDocumentStatus.INDEXED).build()));
        when(clientProvider.getIfAvailable()).thenReturn(null); // generation stops here (empty), but it tried

        service.refresh(Language.EN, BUSINESS_ID);

        // Refresh ignores the stored row and re-scans the corpus (the regeneration path).
        verify(documents).findByBusinessIdAndStatusOrderByCreatedAtDesc(BUSINESS_ID, RagDocumentStatus.INDEXED);
        verify(clientProvider).getIfAvailable();
    }
}
