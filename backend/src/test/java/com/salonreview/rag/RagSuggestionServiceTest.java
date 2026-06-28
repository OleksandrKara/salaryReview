package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.RagDocumentStatus;
import com.salonreview.repo.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the deterministic gating/empty paths of {@link RagSuggestionService} (no LLM). */
class RagSuggestionServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AnthropicClient> clientProvider = mock(ObjectProvider.class);
    private final RagDocumentRepository documents = mock(RagDocumentRepository.class);
    private RagProperties props;
    private RagSuggestionService service;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.setEnabled(true);
        service = new RagSuggestionService(clientProvider, documents, props);
    }

    @Test
    @DisplayName("flag off → empty, corpus never queried")
    void flagOff() {
        props.getSuggestions().setEnabled(false);

        assertThat(service.get().topics()).isEmpty();
        verify(documents, never()).findByStatusOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("empty corpus → empty, no model call")
    void emptyCorpus() {
        when(documents.findByStatusOrderByCreatedAtDesc(RagDocumentStatus.INDEXED)).thenReturn(List.of());

        assertThat(service.get().topics()).isEmpty();
        verify(clientProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("indexed docs present but no Anthropic client → empty (no crash)")
    void noClient() {
        when(documents.findByStatusOrderByCreatedAtDesc(RagDocumentStatus.INDEXED)).thenReturn(List.of(
                RagDocument.builder().id(1L).filename("No-show policy.md").status(RagDocumentStatus.INDEXED).build()));
        when(clientProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.get().topics()).isEmpty();
    }
}
