package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.salonreview.ai.LangSmithTracer;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.Language;
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
import static org.mockito.ArgumentMatchers.eq;
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

    private static final Long BUSINESS_ID = 1L;

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

        when(configService.getActive(BUSINESS_ID)).thenReturn(RagAgentConfig.builder()
                .version(1).systemPrompt("answer only from context").model("claude-haiku-4-5")
                .temperature(BigDecimal.ZERO).k(6).distanceThreshold(new BigDecimal("0.600")).active(true)
                .build());

        RagProperties props = new RagProperties();
        service = new RagAnswerService(anthropicProvider, retrieval, configService, documents, tracerProvider, props);
        spied = spy(service);
    }

    @Test
    @DisplayName("no chunk within the distance floor → 'don't know', no LLM call")
    void emptyRetrievalYieldsDontKnow() {
        when(retrieval.retrieve(anyString(), any(), eq(BUSINESS_ID))).thenReturn(List.of());

        RagAnswer answer = spied.answer("what is the refund policy?", Language.EN, BUSINESS_ID);

        assertThat(answer.answered()).isFalse();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.traceRunId()).isNull();
        assertThat(answer.configVersion()).isEqualTo(1);
        verify(anthropicProvider, never()).getIfAvailable(); // never reached the LLM
    }

    @Test
    @DisplayName("streaming: empty retrieval emits the 'don't know' token then done, no LLM call")
    void streamingEmptyRetrieval() {
        when(retrieval.retrieve(anyString(), any(), eq(BUSINESS_ID))).thenReturn(List.of());

        class Capture implements RagAnswerService.StreamSink {
            final java.util.List<String> tokens = new java.util.ArrayList<>();
            java.util.List<Citation> cites;
            java.util.List<String> followups;
            Boolean answered;
            boolean done, error;
            @Override public void token(String t) { tokens.add(t); }
            @Override public void citations(java.util.List<Citation> c) { cites = c; }
            @Override public void followups(java.util.List<String> f) { followups = f; }
            @Override public void done(String runId, boolean a) { done = true; answered = a; }
            @Override public void error(String m) { error = true; }
        }
        Capture cap = new Capture();

        service.answerStream("what is the refund policy?", Language.EN, cap, BUSINESS_ID);

        assertThat(cap.tokens).hasSize(1);
        assertThat(cap.tokens.get(0)).contains("couldn't find");
        assertThat(cap.cites).isEmpty();
        assertThat(cap.followups).isEmpty();
        assertThat(cap.done).isTrue();
        assertThat(cap.answered).isFalse();
        assertThat(cap.error).isFalse();
        verify(anthropicProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("empty retrieval in Russian → the Russian 'don't know' message")
    void emptyRetrievalRussian() {
        when(retrieval.retrieve(anyString(), any(), eq(BUSINESS_ID))).thenReturn(List.of());

        RagAnswer answer = spied.answer("какая политика возврата?", Language.RU, BUSINESS_ID);

        assertThat(answer.answered()).isFalse();
        assertThat(answer.answer()).isEqualTo("Я не нашёл ничего об этом в текущих документах.");
    }

    @Test
    @DisplayName("hits present → answer assembled with citations from the model")
    void hitsProduceCitedAnswer() {
        ChunkMatch hit = mock(ChunkMatch.class);
        when(hit.getId()).thenReturn(10L);
        when(hit.getDocumentId()).thenReturn(5L);
        when(hit.getChunkText()).thenReturn("The no-show fee is $25.");
        when(hit.getDistance()).thenReturn(0.12);
        when(retrieval.retrieve(anyString(), any(), eq(BUSINESS_ID))).thenReturn(List.of(hit));
        when(documents.findAllById(any())).thenReturn(List.of(
                RagDocument.builder().id(5L).filename("policies.md").build()));

        doReturn(new RagAnswerService.ParsedAnswer(
                "The no-show fee is $25.",
                List.of(new Citation(5L, "policies.md", "The no-show fee is $25.")),
                List.of()))
                .when(spied).callClaude(any(), any(), anyString(), any(), any(), any());

        RagAnswer answer = spied.answer("what's the no-show fee?", Language.EN, BUSINESS_ID);

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
        when(retrieval.retrieve(anyString(), any(), eq(BUSINESS_ID))).thenReturn(List.of(hit));
        when(documents.findAllById(any())).thenReturn(List.of(
                RagDocument.builder().id(5L).filename("p.md").build()));
        org.mockito.Mockito.doThrow(new RagAnswerService.RagAnswerException("anthropic 5xx"))
                .when(spied).callClaude(any(), any(), anyString(), any(), any(), any());

        try {
            spied.answer("q", Language.EN, BUSINESS_ID);
            org.junit.jupiter.api.Assertions.fail("expected RagAnswerException");
        } catch (RagAnswerService.RagAnswerException expected) {
            assertThat(expected.getMessage()).contains("anthropic 5xx");
        }
    }

    // --- follow-up marker splitting (the streaming scanner's building blocks) ---

    @Test
    @DisplayName("parseFollowups: valid JSON array, blanks filtered, capped at 3")
    void parseFollowupsHappyPath() {
        List<String> result = service.parseFollowups(
                "[\"What's the cancellation policy?\", \"  \", \"How much is a redo?\", \"Third?\", \"Fourth?\"]");
        assertThat(result).containsExactly("What's the cancellation policy?", "How much is a redo?", "Third?");
    }

    @Test
    @DisplayName("parseFollowups: blank input → empty list")
    void parseFollowupsBlankInput() {
        assertThat(service.parseFollowups("")).isEmpty();
        assertThat(service.parseFollowups("   ")).isEmpty();
    }

    @Test
    @DisplayName("parseFollowups: malformed JSON → empty list, not an exception")
    void parseFollowupsMalformed() {
        assertThat(service.parseFollowups("not json at all")).isEmpty();
        assertThat(service.parseFollowups("[\"unclosed")).isEmpty();
    }

    @Test
    @DisplayName("suspiciousTailLength: no overlap with the marker → 0, safe to flush everything")
    void suspiciousTailLengthNoOverlap() {
        assertThat(RagAnswerService.suspiciousTailLength("The fee is $25.", RagAnswerService.FOLLOWUPS_MARKER)).isZero();
    }

    @Test
    @DisplayName("suspiciousTailLength: tail exactly matches a prefix of the marker")
    void suspiciousTailLengthPartialMatch() {
        // FOLLOWUPS_MARKER = "\n\n<<<FOLLOWUPS>>>" — a trailing "\n\n<<<FOLL" is a genuine prefix match.
        String buf = "Some answer text.\n\n<<<FOLL";
        int held = RagAnswerService.suspiciousTailLength(buf, RagAnswerService.FOLLOWUPS_MARKER);
        assertThat(held).isEqualTo("\n\n<<<FOLL".length());
    }

    @Test
    @DisplayName("suspiciousTailLength: a tail ending in the complete marker returns 0 — the caller's " +
            "indexOf() check handles a completed marker; this marker has no self-overlapping prefix/suffix")
    void suspiciousTailLengthCompletedMarkerHasNoSelfOverlap() {
        String buf = "prefix" + RagAnswerService.FOLLOWUPS_MARKER;
        assertThat(RagAnswerService.suspiciousTailLength(buf, RagAnswerService.FOLLOWUPS_MARKER)).isZero();
    }
}
