package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.LapsedCustomerWinbackSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.LapsedCustomerWinbackSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Lapsed-customer win-back automation — see
 * openspec/changes/lapsed-customer-winback-automation design.md. */
class LapsedCustomerWinbackSchedulerTest {

    private static final String CUSTOMER_ID = "cust1";
    private static final String PHONE = "+15551234567";
    private static final String SEGMENT_ID = "gv2:TEXT_SUBSCRIBERS";
    private static final Long BUSINESS_ID = 1L;
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private LapsedCustomerWinbackEligibilityRepository eligibilityRepository;
    private LapsedCustomerWinbackSendRepository sendRepository;
    private SquareClient square;
    private SmsAutomationService automationService;
    private SmsConsentRepository consentRepository;
    private RebookingProperties rebookingProperties;
    private SmsMessageLogService messageLogService;
    private TwilioSmsConfigService configService;
    private TwilioSmsClient client;
    private LapsedCustomerWinbackScheduler scheduler;

    @BeforeEach
    void setUp() {
        eligibilityRepository = mock(LapsedCustomerWinbackEligibilityRepository.class);
        sendRepository = mock(LapsedCustomerWinbackSendRepository.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        consentRepository = mock(SmsConsentRepository.class);
        rebookingProperties = new RebookingProperties();
        rebookingProperties.setConsentSegmentId(SEGMENT_ID);
        messageLogService = mock(SmsMessageLogService.class);
        configService = mock(TwilioSmsConfigService.class);
        client = mock(TwilioSmsClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        BusinessRepository businesses = mock(BusinessRepository.class);
        when(businesses.legacySmsBusiness()).thenReturn(Business.builder().id(1L).name("Test").shortCode("test")
                .timezone("UTC").active(true).build());
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        scheduler = new LapsedCustomerWinbackScheduler(eligibilityRepository, sendRepository, squareClientProvider,
                businesses, automationService, consentRepository, rebookingProperties, messageLogService, configService,
                client, "https://salon.akluxnails.com");

        when(automationService.isEnabled(1L, "lapsed_customer_winback")).thenReturn(true);
        when(square.customerPhone(CUSTOMER_ID)).thenReturn(PHONE);
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "Jane"));
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenReturn(List.of());
        when(messageLogService.generateUniqueClickToken()).thenReturn("tok123");
        SmsMessage reserved = SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber(PHONE).body("").status("NOT_SENT").build();
        when(messageLogService.logOutboundWithLink(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(reserved);
        TwilioSmsConfig configured = mock(TwilioSmsConfig.class);
        when(configured.isConfigured()).thenReturn(true);
        when(configService.get(BUSINESS_ID)).thenReturn(configured);
    }

    private static LapsedCustomerWinbackEligibilityRepository.EligibleCustomer eligible(String technicianName) {
        return new LapsedCustomerWinbackEligibilityRepository.EligibleCustomer(
                CUSTOMER_ID, LocalDate.now().minusDays(28), technicianName);
    }

    private void givenEligible(LapsedCustomerWinbackEligibilityRepository.EligibleCustomer... customers) {
        when(eligibilityRepository.findEligibleCustomers()).thenReturn(List.of(customers));
    }

    @Test
    @DisplayName("consented, technician known → marketing body naming technician's FIRST name only + $5, SENT row with promo_expires_at")
    void consentedWithTechnicianSendsMarketingBody() throws Exception {
        // provider_visit.provider_name is the raw Square team-member display name, always
        // "First Last" — never pre-trimmed. Using the full name here (not just "Susan") is what
        // actually exercises the last-name-stripping fix; the original version of this test used
        // an already-first-name-only fixture and so never caught the 2026-08-07 regression.
        givenEligible(eligible("Susan Alieva"));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("Hi Jane!").contains("It's Lucy from AK.LUX.NAILS")
                .contains("3+ weeks").contains("Susan's schedule").contains("$5")
                .contains("tok123").contains("-Lucy")
                .doesNotContain("—").doesNotContain("Alieva");
        verify(messageLogService).logOutboundWithLink(
                eq(BUSINESS_ID), eq("lapsed_customer_winback_nudge"), eq("lapsed_customer_winback"), eq(PHONE),
                any(), anyBoolean(), any(), any(), any(), any());

        ArgumentCaptor<LapsedCustomerWinbackSend> captor = ArgumentCaptor.forClass(LapsedCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LapsedCustomerWinbackSend.STATE_SENT);
        assertThat(captor.getValue().getPromoExpiresAt()).isEqualTo(expectedEndOfTodaySalonZone());
    }

    @Test
    @DisplayName("not consented, technician unknown → transactional body, no $5, technician-less fallback wording")
    void notConsentedNoTechnicianSendsTransactionalFallback() throws Exception {
        givenEligible(eligible(null));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);
        when(square.customerSegmentIds(CUSTOMER_ID)).thenReturn(List.of());
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("3+ weeks").contains("Spots are filling up fast")
                .contains("want me to grab you a spot")
                .doesNotContain("$5").doesNotContain("—");
        verify(messageLogService).logOutboundWithLink(
                eq(BUSINESS_ID), eq("lapsed_customer_winback_reminder"), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("consent via Square segment only (no marketing.contacts consent) → still marketing body")
    void consentOnlyInSquareSegmentSendsMarketingBody() throws Exception {
        givenEligible(eligible("Tatiana Nazirova"));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);
        when(square.customerSegmentIds(CUSTOMER_ID)).thenReturn(List.of(SEGMENT_ID));
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("$5").contains("Tatiana's schedule").doesNotContain("Nazirova");
    }

    @Test
    @DisplayName("phone number unresolved → SKIPPED_UNRESOLVED, no send, no negative-feedback/booking checks")
    void unresolvedPhoneSkipsWithoutSend() {
        givenEligible(eligible("Susan"));
        when(square.customerPhone(CUSTOMER_ID)).thenReturn(null);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        verify(messageLogService, never()).hasNegativeFeedback(any(), any());
        ArgumentCaptor<LapsedCustomerWinbackSend> captor = ArgumentCaptor.forClass(LapsedCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LapsedCustomerWinbackSend.STATE_SKIPPED_UNRESOLVED);
        assertThat(captor.getValue().getPhoneNumber()).isNull();
        assertThat(captor.getValue().getPromoExpiresAt()).isNull();
    }

    @Test
    @DisplayName("prior negative feedback → SKIPPED_NEGATIVE_FEEDBACK, no send")
    void negativeFeedbackSkipsWithoutSend() {
        givenEligible(eligible("Susan"));
        when(messageLogService.hasNegativeFeedback(BUSINESS_ID, PHONE)).thenReturn(true);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        ArgumentCaptor<LapsedCustomerWinbackSend> captor = ArgumentCaptor.forClass(LapsedCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LapsedCustomerWinbackSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
    }

    @Test
    @DisplayName("customer already has an upcoming appointment → SKIPPED_BOOKED, no send")
    void upcomingAppointmentSkipsWithoutSend() {
        givenEligible(eligible("Susan"));
        String futureIso = Instant.now().plusSeconds(3600).toString();
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(new SquareClient.Booking("bk1", "ACCEPTED", futureIso, null, null, null, CUSTOMER_ID, null, null, null)));

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        ArgumentCaptor<LapsedCustomerWinbackSend> captor = ArgumentCaptor.forClass(LapsedCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LapsedCustomerWinbackSend.STATE_SKIPPED_BOOKED);
    }

    @Test
    @DisplayName("automation disabled → SKIPPED_DISABLED, no send")
    void disabledAutomationSkipsWithoutSend() {
        when(automationService.isEnabled(1L, "lapsed_customer_winback")).thenReturn(false);
        givenEligible(eligible("Susan"));

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        ArgumentCaptor<LapsedCustomerWinbackSend> captor = ArgumentCaptor.forClass(LapsedCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LapsedCustomerWinbackSend.STATE_SKIPPED_DISABLED);
    }

    @Test
    @DisplayName("customer already present in lapsed_customer_winback_send → never reprocessed")
    void alreadyProcessedCustomerNeverReprocessed() {
        givenEligible(eligible("Susan"));
        when(sendRepository.existsBySquareCustomerId(CUSTOMER_ID)).thenReturn(true);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client, square);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("Square failure checking upcoming bookings → no row written, retried next run")
    void squareFailureRetriesNextRun() {
        givenEligible(eligible("Susan"));
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenThrow(new RuntimeException("Square down"));

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("lowercase given name is title-cased in the greeting")
    void lowercaseNameIsCapitalized() throws Exception {
        givenEligible(eligible("Susan"));
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "jane"));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("Hi Jane!").doesNotContain("jane");
    }

    @Test
    @DisplayName("no given name on file → name-less greeting fallback")
    void noGivenNameFallsBackToNameLessGreeting() throws Exception {
        givenEligible(eligible("Susan"));
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of());
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).startsWith("Hi! It's Lucy");
    }

    private static Instant expectedEndOfTodaySalonZone() {
        return ZonedDateTime.now(SALON_ZONE).toLocalDate().plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
    }
}
