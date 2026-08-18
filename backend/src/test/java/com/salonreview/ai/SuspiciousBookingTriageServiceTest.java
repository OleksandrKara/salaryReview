package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.salonreview.config.AiTriageProperties;
import com.salonreview.domain.Half;
import com.salonreview.domain.SuspiciousTriage;
import com.salonreview.domain.TriageClassification;
import com.salonreview.repo.SuspiciousTriageRepository;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SuspiciousBookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SuspiciousBookingTriageService} — exercise the cache and failure-mode
 * paths without touching the real Anthropic SDK. The LLM call ({@code callClaude}) is package-private
 * and overridden via a Mockito spy so each test can supply a canned {@link TriageResult} or simulate
 * a refusal.
 */
class SuspiciousBookingTriageServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AnthropicClient> anthropicProvider = mock(ObjectProvider.class);
    private AiTriageProperties props;
    private SuspiciousTriageRepository triages;
    private SuspiciousBookingService suspiciousBookings;
    @SuppressWarnings("unchecked")
    private ObjectProvider<LangSmithTracer> tracerProvider = mock(ObjectProvider.class);

    private SuspiciousBookingTriageService service;
    private SuspiciousBookingTriageService spied;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        anthropicProvider = mock(ObjectProvider.class);
        when(anthropicProvider.getIfAvailable()).thenReturn(mock(AnthropicClient.class));

        props = mock(AiTriageProperties.class);
        when(props.isEnabled()).thenReturn(true);

        triages = mock(SuspiciousTriageRepository.class);
        suspiciousBookings = mock(SuspiciousBookingService.class);

        tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(null);

        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        service = new SuspiciousBookingTriageService(
                anthropicProvider, props, triages, suspiciousBookings, tracerProvider, currentBusinessContext);
        spied = spy(service);

        // Default: any save returns a persisted-looking row with an ID.
        when(triages.save(any())).thenAnswer(inv -> {
            SuspiciousTriage row = inv.getArgument(0);
            if (row.getId() == null) row.setId(42L);
            return row;
        });
    }

    @Test
    @DisplayName("feature flag off → returns empty without any DB or LLM activity")
    void flagOffShortCircuits() {
        when(props.isEnabled()).thenReturn(false);

        Optional<TriageResult> result = service.triage("bk1", 2026, 6);

        assertThat(result).isEmpty();
        verifyNoInteractions(triages);
        verifyNoInteractions(suspiciousBookings);
    }

    @Test
    @DisplayName("cache hit returns the cached result; LLM is not called")
    void cacheHitSkipsLlm() throws Exception {
        SuspiciousTriage cached = SuspiciousTriage.builder()
                .id(1L)
                .squareBookingId("bk1")
                .promptVersion(TriagePrompts.PROMPT_VERSION)
                .classification(TriageClassification.LIKELY_LEGIT)
                .confidence(BigDecimal.valueOf(0.9))
                .explanation("cached explanation")
                .draftMessage("")
                .signals(List.of("past_appointment_no_order"))
                .model("claude-haiku-4-5")
                .build();
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(1L, "bk1", TriagePrompts.PROMPT_VERSION))
                .thenReturn(Optional.of(cached));

        Optional<TriageResult> result = spied.triage("bk1", 2026, 6);

        assertThat(result).isPresent();
        assertThat(result.get().classification()).isEqualTo(TriageClassification.LIKELY_LEGIT);
        assertThat(result.get().explanation()).isEqualTo("cached explanation");
        verify(spied, never()).callClaude(any(), anyString());
        verifyNoInteractions(suspiciousBookings);
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: a cache hit for business 1 is invisible to business 2 — "
            + "a bare (bookingId, promptVersion) lookup would have leaked business 1's cached AI "
            + "explanation/draft message on a bookingId collision or guess")
    void cacheHitIsScopedToCurrentBusiness() throws Exception {
        // Business 1's cached row exists (as in cacheHitSkipsLlm above), but the current request
        // is business 2's — the business-scoped lookup must miss, forcing a fresh (business-2-
        // scoped) candidate lookup instead of silently returning business 1's cached content.
        SuspiciousTriage business1Cached = SuspiciousTriage.builder()
                .id(1L).businessId(1L).squareBookingId("bk1").promptVersion(TriagePrompts.PROMPT_VERSION)
                .classification(TriageClassification.LIKELY_FRAUD)
                .confidence(BigDecimal.valueOf(0.9))
                .explanation("business 1's private explanation")
                .draftMessage("").signals(List.of()).model("claude-haiku-4-5")
                .build();
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(1L, "bk1", TriagePrompts.PROMPT_VERSION))
                .thenReturn(Optional.of(business1Cached));
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(2L, "bk1", TriagePrompts.PROMPT_VERSION))
                .thenReturn(Optional.empty());

        com.salonreview.config.CurrentBusinessContext business2Context =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(business2Context.id()).thenReturn(2L);
        SuspiciousBookingTriageService business2Service = new SuspiciousBookingTriageService(
                anthropicProvider, props, triages, suspiciousBookings, tracerProvider, business2Context);
        SuspiciousBookingTriageService business2Spied = spy(business2Service);
        when(suspiciousBookings.findCandidateForTriage(2026, 6, "bk1")).thenReturn(Optional.empty());

        Optional<TriageResult> result = business2Spied.triage("bk1", 2026, 6);

        // Empty (→ 404), not business 1's cached LIKELY_FRAUD row — proves the cache lookup never
        // fell through to business 1's data.
        assertThat(result).isEmpty();
        verify(business2Spied, never()).callClaude(any(), anyString());
    }

    @Test
    @DisplayName("non-flagged booking returns empty (→ 404 in controller layer)")
    void nonFlaggedReturnsEmpty() throws Exception {
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(any(), any(), any())).thenReturn(Optional.empty());
        when(suspiciousBookings.findCandidateForTriage(anyInt(), anyInt(), anyString()))
                .thenReturn(Optional.empty());

        Optional<TriageResult> result = spied.triage("bk1", 2026, 6);

        assertThat(result).isEmpty();
        verify(spied, never()).callClaude(any(), anyString());
        verify(triages, never()).save(any());
    }

    @Test
    @DisplayName("cache miss + flagged booking → LLM called, result persisted with prompt_version + model overridden")
    void cacheMissCallsLlmAndPersists() throws Exception {
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(any(), any(), any())).thenReturn(Optional.empty());
        whenFindCandidateReturnsSample();

        // Canned LLM result — prompt_version + model fields will be overwritten by the service.
        TriageResult canned = new TriageResult(
                TriageClassification.NEEDS_REVIEW,
                BigDecimal.valueOf(0.45),
                "Mixed signals; would want to ask the provider.",
                "Hi — could you confirm how this was paid?",
                List.of("past_appointment_no_order", "no_cash_note"),
                "WRONG_VERSION",  // service should overwrite
                "WRONG_MODEL");    // service should overwrite
        doReturn(canned).when(spied).callClaude(any(), anyString());

        Optional<TriageResult> result = spied.triage("bk1", 2026, 6);

        assertThat(result).isPresent();
        assertThat(result.get().classification()).isEqualTo(TriageClassification.NEEDS_REVIEW);
        assertThat(result.get().promptVersion()).isEqualTo(TriagePrompts.PROMPT_VERSION);
        assertThat(result.get().model()).isEqualTo(SuspiciousBookingTriageService.MODEL);

        verify(spied).callClaude(any(), anyString());
        ArgumentCaptor<SuspiciousTriage> cap = ArgumentCaptor.forClass(SuspiciousTriage.class);
        verify(triages).save(cap.capture());
        SuspiciousTriage saved = cap.getValue();
        assertThat(saved.getClassification()).isEqualTo(TriageClassification.NEEDS_REVIEW);
        assertThat(saved.getPromptVersion()).isEqualTo(TriagePrompts.PROMPT_VERSION);
        assertThat(saved.getRefusalCategory()).isNull();
    }

    @Test
    @DisplayName("prompt-version change produces a fresh triage; old row is not touched")
    void promptVersionChangeProducesFreshTriage() throws Exception {
        // Stale row exists under an OLD prompt version, but the lookup is by CURRENT version — miss.
        SuspiciousTriage staleV0 = SuspiciousTriage.builder()
                .id(7L).squareBookingId("bk1").promptVersion("v0")
                .classification(TriageClassification.LIKELY_FRAUD)
                .confidence(BigDecimal.valueOf(0.9))
                .explanation("old explanation under v0").draftMessage("old draft")
                .signals(List.of()).model("claude-haiku-4-5")
                .build();
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(1L, "bk1", TriagePrompts.PROMPT_VERSION))
                .thenReturn(Optional.empty());
        whenFindCandidateReturnsSample();

        TriageResult fresh = new TriageResult(
                TriageClassification.LIKELY_LEGIT, BigDecimal.valueOf(0.95),
                "Actually clean — the v1 prompt classifies this differently.",
                "", List.of("past_appointment_no_order"), "", "");
        doReturn(fresh).when(spied).callClaude(any(), anyString());

        Optional<TriageResult> result = spied.triage("bk1", 2026, 6);

        assertThat(result).isPresent();
        assertThat(result.get().classification()).isEqualTo(TriageClassification.LIKELY_LEGIT);
        verify(spied).callClaude(any(), anyString());
        // The stale v0 row was never read or written.
        verify(triages, never()).delete(staleV0);
        verify(triages, never()).deleteById(7L);
    }

    @Test
    @DisplayName("refusal → persists refusal_category, returns NEEDS_REVIEW with 0 confidence + friendly explanation")
    void refusalProducesFallbackAndPersistsCategory() throws Exception {
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(any(), any(), any())).thenReturn(Optional.empty());
        whenFindCandidateReturnsSample();
        doThrow(new SuspiciousBookingTriageService.RefusalException("cyber"))
                .when(spied).callClaude(any(), anyString());

        Optional<TriageResult> result = spied.triage("bk1", 2026, 6);

        assertThat(result).isPresent();
        assertThat(result.get().classification()).isEqualTo(TriageClassification.NEEDS_REVIEW);
        assertThat(result.get().confidence()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.get().explanation()).contains("couldn't be classified");
        assertThat(result.get().explanation()).contains("cyber");

        ArgumentCaptor<SuspiciousTriage> cap = ArgumentCaptor.forClass(SuspiciousTriage.class);
        verify(triages).save(cap.capture());
        assertThat(cap.getValue().getRefusalCategory()).isEqualTo("cyber");
    }

    @Test
    @DisplayName("hard LLM failure → TriageFailedException propagates (→ 502 in controller layer)")
    void hardLlmFailurePropagates() throws Exception {
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(any(), any(), any())).thenReturn(Optional.empty());
        whenFindCandidateReturnsSample();
        doThrow(new RuntimeException("anthropic 5xx"))
                .when(spied).callClaude(any(), anyString());

        try {
            spied.triage("bk1", 2026, 6);
        } catch (SuspiciousBookingTriageService.TriageFailedException expected) {
            assertThat(expected.getCause()).hasMessage("anthropic 5xx");
            verify(triages, never()).save(any());
            return;
        }
        // Should not reach here — assertion failure if no exception thrown.
        org.junit.jupiter.api.Assertions.fail("expected TriageFailedException");
    }

    @Test
    @DisplayName("recordFeedback with no matching triage row → returns false (→ 404 in controller layer)")
    void recordFeedbackOnMissingRow() {
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(1L, "bk1", TriagePrompts.PROMPT_VERSION))
                .thenReturn(Optional.empty());

        boolean ok = service.recordFeedback("bk1", true, null);

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("recordFeedback updates the matching row and returns true")
    void recordFeedbackUpdatesRow() {
        SuspiciousTriage row = SuspiciousTriage.builder()
                .id(123L).squareBookingId("bk1").promptVersion(TriagePrompts.PROMPT_VERSION)
                .classification(TriageClassification.LIKELY_FRAUD)
                .confidence(BigDecimal.valueOf(0.8))
                .explanation("e").draftMessage("d").signals(List.of()).model("claude-haiku-4-5")
                .build();
        when(triages.findByBusinessIdAndSquareBookingIdAndPromptVersion(1L, "bk1", TriagePrompts.PROMPT_VERSION))
                .thenReturn(Optional.of(row));

        boolean ok = service.recordFeedback("bk1", false, TriageClassification.LIKELY_LEGIT);

        assertThat(ok).isTrue();
        verify(triages).updateFeedback(123L, false, TriageClassification.LIKELY_LEGIT);
    }

    // ---------------------------------------------------------------- helpers

    private void whenFindCandidateReturnsSample() {
        SquareMonthAggregator.SuspiciousCandidate candidate = new SquareMonthAggregator.SuspiciousCandidate(
                "bk1", "cust1", "prov1", "Mary Smith", "svc1",
                LocalDate.of(2026, 6, 15), Instant.parse("2026-06-15T14:00:00Z"),
                Half.SECOND, BigDecimal.valueOf(80), null, null);
        when(suspiciousBookings.findCandidateForTriage(2026, 6, "bk1"))
                .thenReturn(Optional.of(new SuspiciousBookingService.CandidateLookup(
                        candidate, "America/New_York", "Classic facial")));
    }
}
