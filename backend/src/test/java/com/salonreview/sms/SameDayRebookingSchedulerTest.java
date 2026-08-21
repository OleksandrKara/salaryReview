package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SameDayRebookingSendRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Same-day-rebooking-discount send poller — see
 * openspec/changes/same-day-rebooking-discount design.md D1/D2/D3.
 */
class SameDayRebookingSchedulerTest {

    private static final String PHONE = "+15551234567";
    private static final String CUSTOMER_ID = "cust1";
    private static final Long BUSINESS_ID = 1L;
    private static final String SEGMENT_ID = "gv2:TEXT_SUBSCRIBERS";

    private SameDayRebookingSendRepository repository;
    private SquareClient square;
    private SmsAutomationService automationService;
    private SmsConsentRepository consentRepository;
    private RebookingProperties rebookingProperties;
    private SmsMessageLogService messageLogService;
    private TwilioSmsConfigService configService;
    private TwilioSmsClient client;
    private TechnicianNameResolver technicianNameResolver;
    private SquareClientProvider squareClientProvider;
    private TwilioSmsConfigRepository twilioConfigs;
    private PromoConfigService promoConfigService;
    private SameDayRebookingScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(SameDayRebookingSendRepository.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        consentRepository = mock(SmsConsentRepository.class);
        rebookingProperties = new RebookingProperties();
        rebookingProperties.setConsentSegmentId(SEGMENT_ID);
        messageLogService = mock(SmsMessageLogService.class);
        configService = mock(TwilioSmsConfigService.class);
        client = mock(TwilioSmsClient.class);
        technicianNameResolver = mock(TechnicianNameResolver.class);
        when(technicianNameResolver.resolveForCustomer(any(), any(), any())).thenReturn(Optional.empty());
        squareClientProvider = mock(SquareClientProvider.class);
        twilioConfigs = mock(TwilioSmsConfigRepository.class);
        when(twilioConfigs.findAll()).thenReturn(List.of(TwilioSmsConfig.builder().businessId(BUSINESS_ID).build()));
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        // Real instance, mocked override repo (no overrides) — exercises the actual
        // SmsMessageTemplateCatalog default wording, same as production with no owner customization.
        var overrideRepo = mock(com.salonreview.repo.SmsTemplateOverrideRepository.class);
        when(overrideRepo.findByBusinessIdAndTemplateKeyAndVariantIndex(any(), any(), anyInt())).thenReturn(Optional.empty());
        SmsMessageTemplateService templateService = new SmsMessageTemplateService(overrideRepo, mock(com.salonreview.repo.SmsMessageRepository.class));
        promoConfigService = mock(PromoConfigService.class);
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, null, "GROUP1", true)));
        scheduler = new SameDayRebookingScheduler(repository, squareClientProvider, twilioConfigs, automationService,
                consentRepository, rebookingProperties, messageLogService, configService, client, technicianNameResolver,
                templateService, "https://salon.akluxnails.com", promoConfigService);

        when(automationService.isEnabled(1L, "same_day_rebooking_discount")).thenReturn(true);
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenReturn(List.of());
        when(messageLogService.generateUniqueClickToken()).thenReturn("tok123");
        SmsMessage reserved = SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber(PHONE).body("").status("NOT_SENT").build();
        when(messageLogService.logOutboundWithLink(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(reserved);
        TwilioSmsConfig configured = mock(TwilioSmsConfig.class);
        when(configured.isConfigured()).thenReturn(true);
        when(configService.get(BUSINESS_ID)).thenReturn(configured);
    }

    private static SameDayRebookingSend send(Instant sendDueAt, Instant promoExpiresAt) {
        return SameDayRebookingSend.builder()
                .id(1L)
                .businessId(BUSINESS_ID)
                .phoneNumber(PHONE)
                .customerName("Jane")
                .squareCustomerId(CUSTOMER_ID)
                .squarePaymentId("pay1")
                .sendDueAt(sendDueAt)
                .promoExpiresAt(promoExpiresAt)
                .state(SameDayRebookingSend.STATE_AWAITING_SEND)
                .build();
    }

    private void givenDue(SameDayRebookingSend... sends) {
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(
                eq(BUSINESS_ID), eq(SameDayRebookingSend.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(sends));
    }

    @Test
    @DisplayName("unbooked, enabled, consented, unexpired → sends and writes SENT")
    void sendsWhenAllConditionsMet() throws Exception {
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueRebookingNudges();

        verify(client).send(any(), eq(PHONE), any());
        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SENT);
    }

    @Test
    @DisplayName("offer already expired by send time → SKIPPED_EXPIRED, never sent")
    void expiredOfferIsSkipped() throws Exception {
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().minusSeconds(60));
        givenDue(s);

        scheduler.sendDueRebookingNudges();

        verifyNoInteractions(client);
        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SKIPPED_EXPIRED);
    }

    @Test
    @DisplayName("customer already has an upcoming appointment → SKIPPED_BOOKED, never sent")
    void upcomingAppointmentIsSkipped() throws Exception {
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        String futureIso = Instant.now().plusSeconds(1800).toString();
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(new SquareClient.Booking("bk1", "ACCEPTED", futureIso, null, null, null, CUSTOMER_ID, null, null, null)));

        scheduler.sendDueRebookingNudges();

        verifyNoInteractions(client);
        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SKIPPED_BOOKED);
    }

    @Test
    @DisplayName("automation disabled → SKIPPED_DISABLED, never sent")
    void disabledAutomationIsSkipped() throws Exception {
        when(automationService.isEnabled(1L, "same_day_rebooking_discount")).thenReturn(false);
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);

        scheduler.sendDueRebookingNudges();

        verifyNoInteractions(client);
        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SKIPPED_DISABLED);
    }

    @Test
    @DisplayName("no business_promo_config row for this business yet → SKIPPED_PROMO_NOT_CONFIGURED, never sent "
            + "(the coupon link would 404)")
    void promoNotConfiguredIsSkipped() throws Exception {
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE)).thenReturn(Optional.empty());
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);

        scheduler.sendDueRebookingNudges();

        verifyNoInteractions(client);
        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SKIPPED_PROMO_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("no consent in either source → still sends, but a transactional reminder with no discount wording")
    void noConsentAnywhereSendsTransactionalReminder() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);
        when(square.customerSegmentIds(CUSTOMER_ID)).thenReturn(List.of());
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueRebookingNudges();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("this time of year")
                .contains("3-4 week wait")
                .contains("tok123")
                .doesNotContain("Hi ")
                .doesNotContain("Lucy")
                .doesNotContain("$10")
                .doesNotContain("discount")
                .doesNotContain("off");
        verify(messageLogService).logOutboundWithLink(
                eq(BUSINESS_ID), eq("same_day_rebooking_reminder"), any(), any(), any(), anyBoolean(), any(), any(), any(), any());

        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SENT);
    }

    @Test
    @DisplayName("consent present only via Square's own segment → still sends")
    void consentOnlyInSquareSegmentStillSends() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);
        when(square.customerSegmentIds(CUSTOMER_ID)).thenReturn(List.of(SEGMENT_ID));
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueRebookingNudges();

        verify(client).send(any(), eq(PHONE), any());
    }

    @Test
    @DisplayName("consent present only in marketing.contacts → still sends")
    void consentOnlyInMarketingContactsStillSends() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueRebookingNudges();

        verify(client).send(any(), eq(PHONE), any());
        verify(square, never()).customerSegmentIds(any());
    }

    @Test
    @DisplayName("customer has ever left negative feedback → SKIPPED_NEGATIVE_FEEDBACK, never sent, regardless of consent")
    void negativeFeedbackIsSkipped() throws Exception {
        when(messageLogService.hasNegativeFeedback(BUSINESS_ID, PHONE)).thenReturn(true);
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);

        scheduler.sendDueRebookingNudges();

        verifyNoInteractions(client);
        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SameDayRebookingSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
    }

    @Test
    @DisplayName("Square failure while checking upcoming bookings → no row written, retried next poll")
    void squareFailureRetriesNextPoll() {
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenThrow(new RuntimeException("Square down"));

        scheduler.sendDueRebookingNudges();

        verifyNoInteractions(client);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("consented branch: resolved technician name is name-dropped in the body")
    void consentedBranchNamesResolvedTechnician() throws Exception {
        when(technicianNameResolver.resolveForCustomer(eq(BUSINESS_ID), eq(CUSTOMER_ID), any())).thenReturn(Optional.of("Susan"));
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueRebookingNudges();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("Susan").contains("$10")
                .doesNotContain("Hi ").doesNotContain("Lucy").doesNotContain("—");
    }

    @Test
    @DisplayName("transactional branch: resolved technician name is name-dropped, no gendered pronoun used, no em dash")
    void transactionalBranchNamesResolvedTechnician() throws Exception {
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);
        when(square.customerSegmentIds(CUSTOMER_ID)).thenReturn(List.of());
        when(technicianNameResolver.resolveForCustomer(eq(BUSINESS_ID), eq(CUSTOMER_ID), any())).thenReturn(Optional.of("Tatiana"));
        SameDayRebookingSend s = send(Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        givenDue(s);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueRebookingNudges();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("Tatiana").doesNotContain(" her ").doesNotContain(" his ")
                .doesNotContain("Hi ").doesNotContain("Lucy").doesNotContain("—");
    }

    @Test
    @DisplayName("one business's SquareClientProvider failure doesn't stop another business's due sends (tasks.md 3.7)")
    void oneBusinessSquareFailureDoesNotBlockAnother() throws Exception {
        Long otherBusinessId = 2L;
        SquareClient otherSquare = mock(SquareClient.class);
        when(otherSquare.bookingsForCustomer(any(), any())).thenReturn(List.of());
        when(twilioConfigs.findAll()).thenReturn(List.of(
                TwilioSmsConfig.builder().businessId(BUSINESS_ID).build(),
                TwilioSmsConfig.builder().businessId(otherBusinessId).build()));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenThrow(new RuntimeException("business A Square down"));
        when(squareClientProvider.forBusiness(otherBusinessId)).thenReturn(otherSquare);

        SameDayRebookingSend otherSend = SameDayRebookingSend.builder()
                .id(2L).businessId(otherBusinessId).phoneNumber("+15559998888").customerName("Other")
                .squareCustomerId("cust2").squarePaymentId("pay2")
                .sendDueAt(Instant.now().minusSeconds(5)).promoExpiresAt(Instant.now().plusSeconds(3600))
                .state(SameDayRebookingSend.STATE_AWAITING_SEND).build();
        when(repository.findByBusinessIdAndStateAndSendDueAtBefore(
                eq(otherBusinessId), eq(SameDayRebookingSend.STATE_AWAITING_SEND), any()))
                .thenReturn(List.of(otherSend));
        when(automationService.isEnabled(otherBusinessId, "same_day_rebooking_discount")).thenReturn(true);
        TwilioSmsConfig otherConfigured = mock(TwilioSmsConfig.class);
        when(otherConfigured.isConfigured()).thenReturn(true);
        when(configService.get(otherBusinessId)).thenReturn(otherConfigured);
        when(promoConfigService.get(otherBusinessId, PromoConfigService.REBOOK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, null, "GROUP2", true)));
        when(client.send(any(), eq("+15559998888"), any())).thenReturn("SM999");

        scheduler.sendDueRebookingNudges();

        // Business A's own repository lookup is never reached (forBusiness threw first) — only
        // business B's due row gets processed.
        verify(repository, never()).findByBusinessIdAndStateAndSendDueAtBefore(
                eq(BUSINESS_ID), any(), any());
        verify(client).send(any(), eq("+15559998888"), any());
    }
}
