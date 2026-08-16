package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.RepeatCustomerWinbackSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.RepeatCustomerWinbackSendRepository;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RepeatCustomerWinbackSchedulerTest {

    private static final String CUSTOMER_ID = "cust1";
    private static final String PHONE = "+15551234567";
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Long BUSINESS_ID = 1L;

    private RepeatCustomerWinbackEligibilityRepository eligibilityRepository;
    private RepeatCustomerWinbackSendRepository sendRepository;
    private SquareClient square;
    private SmsAutomationService automationService;
    private SmsConsentRepository consentRepository;
    private RebookingProperties rebookingProperties;
    private SmsMessageLogService messageLogService;
    private BlockedNumberRepository blockedNumberRepository;
    private TwilioSmsConfigService configService;
    private TwilioSmsClient client;
    private RepeatCustomerWinbackScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        eligibilityRepository = mock(RepeatCustomerWinbackEligibilityRepository.class);
        sendRepository = mock(RepeatCustomerWinbackSendRepository.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        consentRepository = mock(SmsConsentRepository.class);
        rebookingProperties = new RebookingProperties();
        messageLogService = mock(SmsMessageLogService.class);
        blockedNumberRepository = mock(BlockedNumberRepository.class);
        configService = mock(TwilioSmsConfigService.class);
        client = mock(TwilioSmsClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        BusinessRepository businesses = mock(BusinessRepository.class);
        when(businesses.legacySmsBusiness()).thenReturn(Business.builder().id(1L).name("Test").shortCode("test")
                .timezone("UTC").active(true).build());
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        scheduler = new RepeatCustomerWinbackScheduler(eligibilityRepository, sendRepository, squareClientProvider,
                businesses, automationService, consentRepository, rebookingProperties, messageLogService,
                blockedNumberRepository, configService, client, "https://salon.akluxnails.com");

        when(automationService.isEnabled(1L, "repeat_customer_winback")).thenReturn(true);
        when(square.customerPhone(CUSTOMER_ID)).thenReturn(PHONE);
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "Jane"));
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenReturn(List.of());
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(false);
        when(messageLogService.generateUniqueClickToken()).thenReturn("tok123");
        SmsMessage reserved = SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber(PHONE).body("").status("NOT_SENT").build();
        when(messageLogService.logOutboundWithLink(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(reserved);
        TwilioSmsConfig configured = mock(TwilioSmsConfig.class);
        when(configured.isConfigured()).thenReturn(true);
        when(configService.get(BUSINESS_ID)).thenReturn(configured);
        when(client.send(any(), eq(PHONE), any())).thenReturn("SM123");
    }

    private static Instant expectedEndOfTodaySalonZone() {
        return ZonedDateTime.now(SALON_ZONE).toLocalDate().plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
    }

    private static RepeatCustomerWinbackEligibilityRepository.EligibleCustomer eligible(
            String lastProvider, String previousProvider, boolean rebookedSameDay) {
        return new RepeatCustomerWinbackEligibilityRepository.EligibleCustomer(
                CUSTOMER_ID, LocalDate.now(SALON_ZONE).minusDays(45), 3, lastProvider, previousProvider, rebookedSameDay);
    }

    private void givenEligible(RepeatCustomerWinbackEligibilityRepository.EligibleCustomer... customers) {
        when(eligibilityRepository.findEligibleCustomers()).thenReturn(List.of(customers));
    }

    @Test
    @DisplayName("not consented, same technician on last two visits → default body naming that technician, no discount language, WINBACK link")
    void sameTechnicianSendsDefaultBody() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("Hi Jane!").contains("It's Lucy from AK.LUX.NAILS")
                .contains("It's been a while since your last visit with Susan")
                .contains("tok123").contains("-Lucy")
                .doesNotContain("Alieva").doesNotContain("$").doesNotContain("—");

        ArgumentCaptor<String> linkTargetCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageLogService).logOutboundWithLink(
                eq(BUSINESS_ID), eq("repeat_customer_winback_reminder"), eq("repeat_customer_winback"), eq(PHONE),
                any(), anyBoolean(), any(), any(), linkTargetCaptor.capture(), any());
        assertThat(linkTargetCaptor.getValue()).startsWith("WINBACK:");

        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RepeatCustomerWinbackSend.STATE_SENT);
        assertThat(captor.getValue().getMessageVariant()).isEqualTo("default");
        assertThat(captor.getValue().getProviderChanged()).isFalse();
        assertThat(captor.getValue().getTotalVisitCount()).isEqualTo(3);
        assertThat(captor.getValue().getDaysSinceLastVisit()).isEqualTo(45);
        assertThat(captor.getValue().getPromoExpiresAt()).isEqualTo(expectedEndOfTodaySalonZone());
    }

    @Test
    @DisplayName("not consented, technician changed on last visit → personalized body naming the PREVIOUS technician, offering to check, no discount language")
    void technicianChangedSendsPreviousProviderBody() throws Exception {
        givenEligible(eligible("Bayan Dandiyeva", "Tatiana Nazirova", true));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("want me to check").contains("if Tatiana has an opening")
                .doesNotContain("Nazirova").doesNotContain("Bayan").doesNotContain("Dandiyeva")
                .doesNotContain("$").doesNotContain("—");

        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageVariant()).isEqualTo("previous_provider");
        assertThat(captor.getValue().getProviderChanged()).isTrue();
        assertThat(captor.getValue().getRebookedSameDay()).isTrue();
    }

    @Test
    @DisplayName("consented, same technician → marketing body mentions $5 off, WINBACK link")
    void consentedSameTechnicianSendsMarketingBody() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("It's been a while since your last visit with Susan")
                .contains("Grabbed you $5 off if you book today")
                .doesNotContain("Alieva").doesNotContain("—");

        verify(messageLogService).logOutboundWithLink(
                eq(BUSINESS_ID), eq("repeat_customer_winback_nudge"), eq("repeat_customer_winback"), eq(PHONE),
                any(), anyBoolean(), any(), any(), any(), any());

        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getPromoExpiresAt()).isEqualTo(expectedEndOfTodaySalonZone());
    }

    @Test
    @DisplayName("consented, technician changed → previous-provider marketing body mentions $5 off")
    void consentedTechnicianChangedSendsMarketingBody() throws Exception {
        givenEligible(eligible("Bayan Dandiyeva", "Tatiana Nazirova", true));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(true);

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("want me to check").contains("if Tatiana has an opening")
                .contains("Grabbed you $5 off if you book today")
                .doesNotContain("Nazirova").doesNotContain("Bayan").doesNotContain("Dandiyeva").doesNotContain("—");
    }

    @Test
    @DisplayName("consent via Square segment only (no marketing.contacts consent) → still marketing body")
    void consentOnlyInSquareSegmentSendsMarketingBody() throws Exception {
        String segmentId = "gv2:TEXT_SUBSCRIBERS";
        rebookingProperties.setConsentSegmentId(segmentId);
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(consentRepository.hasMarketingConsent(PHONE)).thenReturn(false);
        when(square.customerSegmentIds(CUSTOMER_ID)).thenReturn(List.of(segmentId));

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("$5");
    }

    @Test
    @DisplayName("phone number unresolved → SKIPPED_UNRESOLVED, no send, no negative-feedback/booking checks")
    void unresolvedPhoneSkipsWithoutSend() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(square.customerPhone(CUSTOMER_ID)).thenReturn(null);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        verify(messageLogService, never()).hasNegativeFeedback(any(), any());
        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RepeatCustomerWinbackSend.STATE_SKIPPED_UNRESOLVED);
        assertThat(captor.getValue().getPhoneNumber()).isNull();
        assertThat(captor.getValue().getPromoExpiresAt()).isNull();
    }

    @Test
    @DisplayName("number is blocked → SKIPPED_BLOCKED, no send")
    void blockedNumberSkipsWithoutSend() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(true);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        verify(messageLogService, never()).hasNegativeFeedback(any(), any());
        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RepeatCustomerWinbackSend.STATE_SKIPPED_BLOCKED);
    }

    @Test
    @DisplayName("prior negative feedback → SKIPPED_NEGATIVE_FEEDBACK, no send")
    void negativeFeedbackSkipsWithoutSend() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(messageLogService.hasNegativeFeedback(BUSINESS_ID, PHONE)).thenReturn(true);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RepeatCustomerWinbackSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
    }

    @Test
    @DisplayName("customer already has an upcoming appointment → SKIPPED_BOOKED, no send")
    void upcomingAppointmentSkipsWithoutSend() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        String futureIso = Instant.now().plusSeconds(3600).toString();
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(new SquareClient.Booking("bk1", "ACCEPTED", futureIso, null, null, null, CUSTOMER_ID, null, null, null)));

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RepeatCustomerWinbackSend.STATE_SKIPPED_BOOKED);
    }

    @Test
    @DisplayName("automation disabled → SKIPPED_DISABLED, no send")
    void disabledAutomationSkipsWithoutSend() throws Exception {
        when(automationService.isEnabled(1L, "repeat_customer_winback")).thenReturn(false);
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        ArgumentCaptor<RepeatCustomerWinbackSend> captor = ArgumentCaptor.forClass(RepeatCustomerWinbackSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RepeatCustomerWinbackSend.STATE_SKIPPED_DISABLED);
    }

    @Test
    @DisplayName("customer sent within the last 60 days → never reprocessed (belt-and-suspenders)")
    void recentlySentCustomerNeverReprocessed() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(sendRepository.existsBySquareCustomerIdAndStateAndCreatedAtAfter(
                eq(CUSTOMER_ID), eq(RepeatCustomerWinbackSend.STATE_SENT), any()))
                .thenReturn(true);

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client, square);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("Square failure checking upcoming bookings → no row written, retried next run")
    void squareFailureRetriesNextRun() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenThrow(new RuntimeException("Square down"));

        scheduler.sendDueWinbacks();

        verifyNoInteractions(client);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("no given name on file → name-less greeting fallback")
    void noGivenNameFallsBackToNameLessGreeting() throws Exception {
        givenEligible(eligible("Susan Alieva", "Susan Alieva", false));
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of());

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).startsWith("Hi! It's Lucy");
    }

    @Test
    @DisplayName("no technician name on file at all → technician-less default body, still no discount")
    void noTechnicianNameFallsBackToGenericBody() throws Exception {
        givenEligible(eligible(null, null, false));

        scheduler.sendDueWinbacks();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("It's been a while since your last visit.")
                .doesNotContain("$").doesNotContain("—");
    }
}
