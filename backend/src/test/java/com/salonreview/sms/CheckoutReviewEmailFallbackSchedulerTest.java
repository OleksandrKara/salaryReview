package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Email follow-up for checkout_review_request flows that reached EXPIRED (24h, no SMS reply at
 * all) — see CheckoutReviewEmailFallbackScheduler's own class doc for why this is anchored to that
 * state rather than WinbackEmailFallbackScheduler's fixed-7pm/same-day window. */
class CheckoutReviewEmailFallbackSchedulerTest {

    private static final Long BUSINESS_ID = 1L;
    private static final long FLOW_ID = 7L;
    private static final Long ASK_MESSAGE_ID = 55L;
    private static final String PHONE = "+15551234567";
    private static final String CUSTOMER_ID = "cust1";

    private SmsReplyFlowRepository replyFlowRepository;
    private WinbackEmailSendRepository winbackEmailSendRepository;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpEmailService mailchimpEmailService;
    private MailchimpEmailTemplateService templateService;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private SmsAutomationService automationService;
    private ProviderRepository providerRepository;
    private CheckoutReviewRatingSigner ratingSigner;
    private CheckoutReviewEmailFallbackScheduler scheduler;

    @BeforeEach
    void setUp() {
        replyFlowRepository = mock(SmsReplyFlowRepository.class);
        winbackEmailSendRepository = mock(WinbackEmailSendRepository.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpEmailService = mock(MailchimpEmailService.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        providerRepository = mock(ProviderRepository.class);
        RebookingProperties properties = new RebookingProperties();
        properties.setPromoSecret("test-secret");
        ratingSigner = new CheckoutReviewRatingSigner(properties);
        scheduler = new CheckoutReviewEmailFallbackScheduler(replyFlowRepository, winbackEmailSendRepository,
                mailchimpConfigRepository, mailchimpEmailService, templateService, squareClientProvider,
                automationService, providerRepository, ratingSigner, "https://salon.akluxnails.com");

        MailchimpConfig config = MailchimpConfig.builder().businessId(BUSINESS_ID)
                .apiKey("k-us1").audienceId("a1").fromName("Lucy").fromEmail("lucy@akluxnails.com")
                .replyToEmail("lucy@akluxnails.com").build();
        when(mailchimpConfigRepository.findAll()).thenReturn(List.of(config));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(automationService.isEnabled(eq(BUSINESS_ID), anyString())).thenReturn(true);
        when(square.customerEmail(CUSTOMER_ID)).thenReturn("jane@example.com");
        when(templateService.render(eq(BUSINESS_ID), eq("checkout_review_request"), any())).thenReturn(Optional.of("<html></html>"));
        when(winbackEmailSendRepository.existsBySmsMessageId(any())).thenReturn(false);
    }

    private static SmsReplyFlow expiredFlow() {
        return SmsReplyFlow.builder().id(FLOW_ID).businessId(BUSINESS_ID)
                .automationKey(CheckoutReviewReplyService.AUTOMATION_KEY).phoneNumber(PHONE)
                .customerName("Jane").state(SmsReplyFlow.STATE_EXPIRED).squareCustomerId(CUSTOMER_ID)
                .askSmsMessageId(ASK_MESSAGE_ID).sendDueAt(Instant.now()).build();
    }

    @Test
    @DisplayName("expired flow, customer has an email on file → sends, saves a SENT row keyed to "
            + "the flow's own ask-SMS message id (reusing WinbackEmailSend's shape/metrics)")
    void expiredFlowWithEmailSendsAndSaves() throws Exception {
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        WinbackEmailSend saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
        assertThat(saved.getSmsMessageId()).isEqualTo(ASK_MESSAGE_ID);
        assertThat(saved.getAutomationKey()).isEqualTo(CheckoutReviewReplyService.AUTOMATION_KEY);
        assertThat(saved.getEmailAddress()).isEqualTo("jane@example.com");
        assertThat(saved.getMailchimpCampaignId()).isEqualTo("campaign-1");
    }

    @Test
    @DisplayName("template vars include all five signed rating links, plus FNAME and a blank "
            + "TECHNICIAN_CLAUSE when no provider resolved")
    void buildsFiveRatingLinksAndVars() throws Exception {
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        scheduler.sendDueFollowUps();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq(CheckoutReviewReplyService.AUTOMATION_KEY), varsCaptor.capture());
        Map<String, String> vars = varsCaptor.getValue();
        assertThat(vars.get("FNAME")).isEqualTo("Jane");
        assertThat(vars.get("TECHNICIAN_CLAUSE")).isEmpty();
        for (int rating = 1; rating <= 5; rating++) {
            String link = vars.get("LINK_" + rating);
            assertThat(link).contains("/api/public/checkout-review/rate")
                    .contains("flow=" + FLOW_ID)
                    .contains("rating=" + rating);
            assertThat(ratingSigner.verify(FLOW_ID, rating,
                    Long.parseLong(link.replaceAll(".*[?&]exp=(\\d+).*", "$1")),
                    link.replaceAll(".*[?&]sig=([^&]+).*", "$1"))).isTrue();
        }
    }

    @Test
    @DisplayName("a provider resolved on the flow → TECHNICIAN_CLAUSE names them")
    void resolvedProviderNamedInTechnicianClause() throws Exception {
        SmsReplyFlow flowWithProvider = SmsReplyFlow.builder().id(FLOW_ID).businessId(BUSINESS_ID)
                .automationKey(CheckoutReviewReplyService.AUTOMATION_KEY).phoneNumber(PHONE)
                .customerName("Jane").state(SmsReplyFlow.STATE_EXPIRED).squareCustomerId(CUSTOMER_ID)
                .askSmsMessageId(ASK_MESSAGE_ID).providerId(9L).sendDueAt(Instant.now()).build();
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(flowWithProvider));
        when(providerRepository.findByIdAndBusinessId(9L, BUSINESS_ID))
                .thenReturn(Optional.of(Provider.builder().id(9L).displayName("Susan Alieva").build()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        scheduler.sendDueFollowUps();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq(CheckoutReviewReplyService.AUTOMATION_KEY), varsCaptor.capture());
        assertThat(varsCaptor.getValue().get("TECHNICIAN_CLAUSE")).isEqualTo(" with Susan");
    }

    @Test
    @DisplayName("already has a WinbackEmailSend row for this ask-SMS id → skipped, not re-sent")
    void alreadyProcessedFlowSkipped() {
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));
        when(winbackEmailSendRepository.existsBySmsMessageId(ASK_MESSAGE_ID)).thenReturn(true);

        scheduler.sendDueFollowUps();

        verify(winbackEmailSendRepository, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("automation disabled since the SMS ask went out → skipped with SKIPPED_DISABLED, no email sent")
    void disabledAutomationSkipped() {
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));
        when(automationService.isEnabled(BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY)).thenReturn(false);

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SKIPPED_DISABLED);
        org.mockito.Mockito.verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("customer has no email on file → skipped with SKIPPED_NO_EMAIL")
    void noEmailOnFileSkipped() {
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));
        when(square.customerEmail(CUSTOMER_ID)).thenReturn(null);

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SKIPPED_NO_EMAIL);
    }

    @Test
    @DisplayName("signing not configured → skipped with SKIPPED_NOT_CONFIGURED rather than sending a "
            + "link that could never verify")
    void signingNotConfiguredSkipped() {
        CheckoutReviewEmailFallbackScheduler unsignedScheduler = new CheckoutReviewEmailFallbackScheduler(
                replyFlowRepository, winbackEmailSendRepository, mailchimpConfigRepository, mailchimpEmailService,
                templateService, squareClientProvider, automationService, providerRepository,
                new CheckoutReviewRatingSigner(new RebookingProperties()), "https://salon.akluxnails.com");
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));

        unsignedScheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SKIPPED_NOT_CONFIGURED);
        org.mockito.Mockito.verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("Mailchimp send throws → SEND_FAILED recorded, not left silently unresolved")
    void sendFailureRecordsSendFailed() throws Exception {
        when(replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                BUSINESS_ID, CheckoutReviewReplyService.AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED))
                .thenReturn(List.of(expiredFlow()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Mailchimp API error"));

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SEND_FAILED);
    }
}
