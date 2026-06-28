package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.salonreview.ai.LangSmithTracer;
import com.salonreview.domain.RagAgentConfig;
import com.salonreview.domain.RagDocument;
import com.salonreview.repo.ChunkMatch;
import com.salonreview.repo.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RagAnswerService}. The Claude call ({@code callClaude}, which assembles
 * document blocks + citations) is package-private and overridden via a Mockito spy, so the
 * retrieval/assembly behaviour is tested without the real Anthropic SDK.
 */
class RagAnswerServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AnthropicClient> anthropicProvider = mock(ObjectProvider.class);
    private RagRetrievalService retrieval;
    private RagConfigService configService;
    private RagDocumentRepository documents;
    @SuppressWarnings("unchecked")
    private ObjectProvider<LangSmithTracer> tracerProvider = mock(ObjectProvider.class);

    private RagAnswerService service;
    private RagAnswerService spied;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        anthropicProvider = mock(ObjectProvider.class);
        when(anthropicProvider.getIfAvailable()).thenReturn(mock(AnthropicClient.class));
        retrieval = mock(RagRetrievalService.class);
        configService = mock(RagConfigService.class);
        documents = mock(RagDocumentRepository.class);
        tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(null);

        when(configService.getActive()).thenReturn(RagAgentConfig.builder()
                .version(1).systemPrompt("answer only from context").model("claude-haiku-4-5")
                .temperature(BigDecimal.ZERO).k(6).distanceThreshold(new BigDecimal("0.600")).active(true)
                .build());

        service = new RagAnswerService(anthropicProvider, retrieval, configService, documents, tracerProvider);
        spied = spy(service);
    }

    @Test
    @DisplayName("no chunk within the distance floor → 'don't know', no LLM call")
    void emptyRetrievalYieldsDontKnow() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(List.of());

        RagAnswer answer = spied.answer("what is the refund policy?");

        assertThat(answer.answered()).isFalse();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.traceRunId()).isNull();
        assertThat(answer.configVersion()).isEqualTo(1);
        verify(anthropicProvider, never()).getIfAvailable(); // never reached the LLM
    }

    @Test
    @DisplayName("streaming: empty retrieval emits the 'don't know' token then done, no LLM call")
    void streamingEmptyRetrieval() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(List.of());

        class Capture implements RagAnswerService.StreamSink {
            final java.util.List<String> tokens = new java.util.ArrayList<>();
            java.util.List<Citation> cites;
            Boolean answered;
            boolean done, error;
            @Override public void token(String t) { tokens.add(t); }
            @Override public void citations(java.util.List<Citation> c) { cites = c; }
            @Override public void done(String runId, boolean a) { done = true; answered = a; }
            @Override public void error(String m) { error = true; }
        }
        Capture cap = new Capture();

        service.answerStream("what is the refund policy?", cap);

        assertThat(cap.tokens).hasSize(1);
        assertThat(cap.tokens.get(0)).contains("couldn't find");
        assertThat(cap.cites).isEmpty();
        assertThat(cap.done).isTrue();
        assertThat(cap.answered).isFalse();
        assertThat(cap.error).isFalse();
        verify(anthropicProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("hits present → answer assembled with citations from the model")
    void hitsProduceCitedAnswer() {
        ChunkMatch hit = mock(ChunkMatch.class);
        when(hit.getId()).thenReturn(10L);
        when(hit.getDocumentId()).thenReturn(5L);
        when(hit.getChunkText()).thenReturn("The no-show fee is $25.");
        when(hit.getDistance()).thenReturn(0.12);
        when(retrieval.retrieve(anyString(), any())).thenReturn(List.of(hit));
        when(documents.findAllById(any())).thenReturn(List.of(
                RagDocument.builder().id(5L).filename("policies.md").build()));

        doReturn(new RagAnswerService.ParsedAnswer(
                "The no-show fee is $25.",
                List.of(new Citation(5L, "policies.md", "The no-show fee is $25."))))
                .when(spied).callClaude(any(), any(), anyString(), any(), any());

        RagAnswer answer = spied.answer("what's the no-show fee?");

        assertThat(answer.answered()).isTrue();
        assertThat(answer.answer()).contains("$25");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().get(0).documentId()).isEqualTo(5L);
        assertThat(answer.citations().get(0).documentTitle()).isEqualTo("policies.md");
    }

    @Test
    @DisplayName("LLM failure → RagAnswerException (→ 502 in controller)")
    void llmFailurePropagates() {
        ChunkMatch hit = mock(ChunkMatch.class);
        when(hit.getId()).thenReturn(10L);
        when(hit.getDocumentId()).thenReturn(5L);
        when(hit.getChunkText()).thenReturn("text");
        when(hit.getDistance()).thenReturn(0.2);
        when(retrieval.retrieve(anyString(), any())).thenReturn(List.of(hit));
        when(documents.findAllById(any())).thenReturn(List.of(
                RagDocument.builder().id(5L).filename("p.md").build()));
        org.mockito.Mockito.doThrow(new RagAnswerService.RagAnswerException("anthropic 5xx"))
                .when(spied).callClaude(any(), any(), anyString(), any(), any());

        try {
            spied.answer("q");
            org.junit.jupiter.api.Assertions.fail("expected RagAnswerException");
        } catch (RagAnswerService.RagAnswerException expected) {
            assertThat(expected.getMessage()).contains("anthropic 5xx");
        }
    }
}
