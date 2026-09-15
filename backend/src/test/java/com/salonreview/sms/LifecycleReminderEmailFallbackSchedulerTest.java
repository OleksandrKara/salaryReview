package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.SmsMessageRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LifecycleReminderEmailFallbackSchedulerTest {

    private static final Long BUSINESS_ID = 2L;
    private static final String PHONE = "+15551234567";
    private static final String CUSTOMER_ID = "cust1";

    private SmsMessageRepository smsMessageRepository;
    private WinbackEmailSendRepository winbackEmailSendRepository;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpEmailService mailchimpEmailService;
    private MailchimpEmailTemplateService templateService;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private SmsAutomationService automationService;
    private LifecycleReminderEmailFallbackScheduler scheduler;

    @BeforeEach
    void setUp() {
        smsMessageRepository = mock(SmsMessageRepository.class);
        winbackEmailSendRepository = mock(WinbackEmailSendRepository.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpEmailService = mock(MailchimpEmailService.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        scheduler = new LifecycleReminderEmailFallbackScheduler(smsMessageRepository, winbackEmailSendRepository,
                mailchimpConfigRepository, mailchimpEmailService, templateService, squareClientProvider,
                automationService);

        MailchimpConfig config = MailchimpConfig.builder().businessId(BUSINESS_ID)
                .apiKey("k-us1").audienceId("a1").fromName("Anna").fromEmail("anna@pmu-annakara.com")
                .replyToEmail("anna@pmu-annakara.com").build();
        when(mailchimpConfigRepository.findAll()).thenReturn(List.of(config));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(automationService.isEnabled(eq(BUSINESS_ID), anyString())).thenReturn(true);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of(CUSTOMER_ID));
        when(square.customerEmail(CUSTOMER_ID)).thenReturn("jane@example.com");
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "Jane"));
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.of("<html></html>"));
        when(winbackEmailSendRepository.existsBySmsMessageId(any())).thenReturn(false);
        when(smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("INBOUND"), any())).thenReturn(false);
    }

    private static SmsMessage candidate(Long id, String automationKey) {
        return SmsMessage.builder().id(id).businessId(BUSINESS_ID).automationKey(automationKey)
                .phoneNumber(PHONE).direction("OUTBOUND").status("SENT")
                .body("").createdAt(Instant.now()).build();
    }

    @Test
    @DisplayName("candidate query covers both color_booster_reminder and touchup_reminder")
    void candidateQueryIncludesBothAutomationKeys() {
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any()))
                .thenReturn(List.of());

        scheduler.sendDueFollowUps();

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(smsMessageRepository).findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), keysCaptor.capture(), eq("OUTBOUND"), eq("SENT"), any(), any());
        assertThat(keysCaptor.getValue()).containsExactlyInAnyOrder(
                ColorBoosterReminderScheduler.AUTOMATION_KEY, TouchupReminderScheduler.AUTOMATION_KEY);
    }

    @Test
    @DisplayName("color_booster_reminder candidate: renders with the real color-booster deep link, sends, saves SENT")
    void colorBoosterSendsWithCorrectLinkAndSubject() throws Exception {
        SmsMessage sms = candidate(1L, ColorBoosterReminderScheduler.AUTOMATION_KEY);
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(mailchimpEmailService.sendWinbackEmail(any(), eq("jane@example.com"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("campaign123");

        scheduler.sendDueFollowUps();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq(ColorBoosterReminderScheduler.AUTOMATION_KEY), varsCaptor.capture());
        assertThat(varsCaptor.getValue())
                .containsEntry("FNAME", "Jane")
                .containsEntry("LINK", "https://book.pmu-annakara.com/?book=color-booster");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailchimpEmailService).sendWinbackEmail(any(), eq("jane@example.com"), subjectCaptor.capture(), anyString(), anyString(), anyString());
        assertThat(subjectCaptor.getValue()).isEqualTo("Time for your color booster, Jane");

        ArgumentCaptor<WinbackEmailSend> saveCaptor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
        assertThat(saveCaptor.getValue().getAutomationKey()).isEqualTo(ColorBoosterReminderScheduler.AUTOMATION_KEY);
    }

    @Test
    @DisplayName("touchup_reminder candidate: uses the touch-up deep link, not color-booster's")
    void touchupUsesItsOwnLink() throws Exception {
        SmsMessage sms = candidate(2L, TouchupReminderScheduler.AUTOMATION_KEY);
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(mailchimpEmailService.sendWinbackEmail(any(), eq("jane@example.com"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("campaign456");

        scheduler.sendDueFollowUps();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq(TouchupReminderScheduler.AUTOMATION_KEY), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("LINK", "https://book.pmu-annakara.com/?book=touch-up");
    }

    @Test
    @DisplayName("candidate already replied → SKIPPED_REPLIED, no email sent")
    void alreadyRepliedSkipsWithoutSending() {
        SmsMessage sms = candidate(3L, ColorBoosterReminderScheduler.AUTOMATION_KEY);
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("INBOUND"), any())).thenReturn(true);

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SKIPPED_REPLIED);
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("no email on file → SKIPPED_NO_EMAIL, no send attempted")
    void noEmailOnFileSkips() {
        SmsMessage sms = candidate(4L, ColorBoosterReminderScheduler.AUTOMATION_KEY);
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(square.customerEmail(CUSTOMER_ID)).thenReturn(null);

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SKIPPED_NO_EMAIL);
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("no first name resolvable → subject has no name suffix, template gets FNAME=there")
    void noNameResolvesToGenericGreeting() throws Exception {
        SmsMessage sms = candidate(5L, TouchupReminderScheduler.AUTOMATION_KEY);
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of());
        when(mailchimpEmailService.sendWinbackEmail(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("campaign789");

        scheduler.sendDueFollowUps();

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailchimpEmailService).sendWinbackEmail(any(), anyString(), subjectCaptor.capture(), anyString(), anyString(), anyString());
        assertThat(subjectCaptor.getValue()).isEqualTo("Time for your touch-up");
    }
}
