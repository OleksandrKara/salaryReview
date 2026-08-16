package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.salonreview.ai.SmsDraftService.DraftResult;
import com.salonreview.config.AiSmsDraftProperties;
import com.salonreview.domain.Language;
import com.salonreview.domain.RagAgentConfig;
import com.salonreview.domain.SmsMessage;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.rag.RagConfigService;
import com.salonreview.rag.RagRetrievalService;
import com.salonreview.repo.ChunkMatch;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.web.dto.MarketingContactDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmsDraftService} — mirrors {@code FunnelAnalysisServiceTest}'s
 * spy-and-override-callClaude pattern so these tests exercise the service's own logic (feature
 * flag, context assembly, conditional RAG grounding, refusal fallback) without touching the real
 * Anthropic SDK plumbing.
 */
class SmsDraftServiceTest {

    private static final Long BUSINESS_ID = 1L;

    @SuppressWarnings("unchecked")
    private ObjectProvider<AnthropicClient> anthropicProvider = mock(ObjectProvider.class);
    private AiSmsDraftProperties props;
    private SmsMessageLogService smsMessageLogService;
    private MarketingContactsService contactsService;
    @SuppressWarnings("unchecked")
    private ObjectProvider<RagRetrievalService> ragRetrievalProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private ObjectProvider<RagConfigService> ragConfigProvider = mock(ObjectProvider.class);

    private SmsDraftService service;
    private SmsDraftService spied;

    private static final String PHONE = "+15551234567";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        anthropicProvider = mock(ObjectProvider.class);
        when(anthropicProvider.getIfAvailable()).thenReturn(mock(AnthropicClient.class));

        props = mock(AiSmsDraftProperties.class);
        when(props.isEnabled()).thenReturn(true);

        smsMessageLogService = mock(SmsMessageLogService.class);
        when(smsMessageLogService.thread(anyString())).thenReturn(List.of());

        contactsService = mock(MarketingContactsService.class);
        when(contactsService.contactByPhone(anyString())).thenReturn(Optional.empty());

        ragRetrievalProvider = mock(ObjectProvider.class);
        when(ragRetrievalProvider.getIfAvailable()).thenReturn(null);
        ragConfigProvider = mock(ObjectProvider.class);
        when(ragConfigProvider.getIfAvailable()).thenReturn(null);

        service = new SmsDraftService(anthropicProvider, props, smsMessageLogService, contactsService,
                ragRetrievalProvider, ragConfigProvider);
        spied = spy(service);
    }

    private static SmsMessage outbound(String body) {
        return SmsMessage.builder().direction("OUTBOUND").phoneNumber(PHONE).body(body)
                .status("SENT").createdAt(Instant.now()).build();
    }

    private static SmsMessage inbound(String body) {
        return SmsMessage.builder().direction("INBOUND").phoneNumber(PHONE).body(body)
                .status("RECEIVED").createdAt(Instant.now()).build();
    }

    private static DraftResult canned() {
        return new DraftResult("Hi! It's Lucy 💛 -Lucy", SmsDraftPrompts.PROMPT_VERSION, SmsDraftService.MODEL);
    }

    @Test
    @DisplayName("disabled feature flag returns empty without touching Claude")
    void disabledReturnsEmpty() {
        when(props.isEnabled()).thenReturn(false);

        Optional<DraftResult> result = service.draft(PHONE, Language.EN, BUSINESS_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(anthropicProvider);
    }

    @Test
    @DisplayName("no configured Anthropic client returns empty")
    void noClientReturnsEmpty() {
        when(anthropicProvider.getIfAvailable()).thenReturn(null);

        Optional<DraftResult> result = service.draft(PHONE, Language.EN, BUSINESS_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(smsMessageLogService);
    }

    @Test
    @DisplayName("context sent to Claude includes customer name, appointment history, and thread")
    void contextAssemblyIncludesContactAndThread() throws Exception {
        MarketingContactDto.Appointment appt = new MarketingContactDto.Appointment(
                "bk1", "COMPLETED", Instant.parse("2026-06-01T18:00:00Z"), "Gel Manicure",
                new BigDecimal("55.00"), "Nina", "CARD", new BigDecimal("55.00"),
                null, null, null, null, null, null);
        MarketingContactDto.Contact contact = new MarketingContactDto.Contact(
                "c1", "maria", null, PHONE, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, List.of(), List.of(appt), null, null, null, null, null, null,
                true, 5);
        when(contactsService.contactByPhone(PHONE)).thenReturn(Optional.of(contact));
        when(smsMessageLogService.thread(PHONE)).thenReturn(List.of(
                outbound("Hi Maria! It's Lucy 💛 Time for your next gel fill?"),
                inbound("My last polish chipped after 3 days, kind of annoyed")));

        doReturn(canned()).when(spied).callClaude(any(), anyString(), eq(Language.EN));

        Optional<DraftResult> result = spied.draft(PHONE, Language.EN, BUSINESS_ID);

        assertThat(result).isPresent();
        org.mockito.ArgumentCaptor<String> userMessageCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(spied).callClaude(any(), userMessageCaptor.capture(), eq(Language.EN));
        String userMessage = userMessageCaptor.getValue();
        assertThat(userMessage).contains("Maria");
        assertThat(userMessage).contains("Gel Manicure");
        assertThat(userMessage).contains("VIP customer: yes");
        assertThat(userMessage).contains("chipped after 3 days");
    }

    @Test
    @DisplayName("RAG grounding is skipped when there's no inbound message to ground against")
    void ragSkippedWithoutInboundMessage() throws Exception {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        RagConfigService configService = mock(RagConfigService.class);
        when(ragRetrievalProvider.getIfAvailable()).thenReturn(retrieval);
        when(ragConfigProvider.getIfAvailable()).thenReturn(configService);
        when(smsMessageLogService.thread(PHONE)).thenReturn(List.of(outbound("Hi! Time for a fill?")));

        doReturn(canned()).when(spied).callClaude(any(), anyString(), eq(Language.EN));

        spied.draft(PHONE, Language.EN, BUSINESS_ID);

        verify(retrieval, never()).retrieve(anyString(), any(), any());
    }

    @Test
    @DisplayName("RAG grounding is included when the customer's most recent inbound message raises a question")
    void ragIncludedWithInboundMessage() throws Exception {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        RagConfigService configService = mock(RagConfigService.class);
        RagAgentConfig cfg = RagAgentConfig.builder().systemPrompt("s").model("m")
                .temperature(BigDecimal.ONE).k(3).distanceThreshold(BigDecimal.ONE).active(true).build();
        when(ragConfigProvider.getIfAvailable()).thenReturn(configService);
        when(ragRetrievalProvider.getIfAvailable()).thenReturn(retrieval);
        when(configService.getActive(BUSINESS_ID)).thenReturn(cfg);
        ChunkMatch match = mock(ChunkMatch.class);
        when(match.getChunkText()).thenReturn("Our cancellation policy requires 24 hours notice.");
        when(retrieval.retrieve(eq("Do you have a cancellation fee?"), eq(cfg), eq(BUSINESS_ID))).thenReturn(List.of(match));
        when(smsMessageLogService.thread(PHONE)).thenReturn(List.of(
                outbound("Hi! Time for a fill?"),
                inbound("Do you have a cancellation fee?")));

        doReturn(canned()).when(spied).callClaude(any(), anyString(), eq(Language.EN));

        spied.draft(PHONE, Language.EN, BUSINESS_ID);

        org.mockito.ArgumentCaptor<String> userMessageCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(spied).callClaude(any(), userMessageCaptor.capture(), eq(Language.EN));
        assertThat(userMessageCaptor.getValue()).contains("24 hours notice");
        verify(retrieval).retrieve(eq("Do you have a cancellation fee?"), eq(cfg), eq(BUSINESS_ID));
    }

    @Test
    @DisplayName("a policy refusal falls back to a friendly manual-reply message, in the requested language")
    void refusalFallsBackToFriendlyMessage() throws Exception {
        doThrow(new SmsDraftService.RefusalException("policy_refusal"))
                .when(spied).callClaude(any(), anyString(), eq(Language.RU));

        Optional<DraftResult> result = spied.draft(PHONE, Language.RU, BUSINESS_ID);

        assertThat(result).isPresent();
        assertThat(result.get().body()).contains("вручную");
    }

    @Test
    @DisplayName("a non-refusal Claude failure is rethrown as DraftFailedException")
    void otherFailuresRethrowAsDraftFailed() throws Exception {
        doThrow(new RuntimeException("anthropic 5xx"))
                .when(spied).callClaude(any(), anyString(), eq(Language.EN));

        org.junit.jupiter.api.Assertions.assertThrows(SmsDraftService.DraftFailedException.class,
                () -> spied.draft(PHONE, Language.EN, BUSINESS_ID));
    }
}
