package com.salonreview.sms;

import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirstContactAutoReplyServiceTest {

    private static final String PHONE = "+15551234567";

    private TwilioSmsService sms;
    private SmsAutomationService automations;
    private SmsMessageRepository messages;
    private ScheduledExecutorService scheduler;
    private final ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    private FirstContactAutoReplyService service;

    @BeforeEach
    void setUp() {
        sms = mock(TwilioSmsService.class);
        automations = mock(SmsAutomationService.class);
        messages = mock(SmsMessageRepository.class);
        scheduler = mock(ScheduledExecutorService.class);
        service = new FirstContactAutoReplyService(sms, automations, messages, scheduler);
        when(automations.isEnabled(1L, FirstContactAutoReplyService.AUTOMATION_KEY)).thenReturn(true);
        when(sms.sendTemplated(any(), anyString(), anyString(), anyMap())).thenReturn(TwilioSmsService.SmsSendResult.ok());
    }

    @Test
    void stockTextsPickTheRightTemplate() {
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question about a nail appointment."))
                .contains("first_contact_autoreply_nails");
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question for AK.LUX.NAILS. "))
                .contains("first_contact_autoreply_nails");
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question about permanent makeup."))
                .contains("first_contact_autoreply_pmu");
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question about Permanent Eyebrows."))
                .contains("first_contact_autoreply_pmu");
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question about the Russian manicure course."))
                .contains("first_contact_autoreply_nails_course");
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question about the PMU Academy courses."))
                .contains("first_contact_autoreply_academy");
        assertThat(FirstContactAutoReplyService.templateKeyFor(
                "Hi! I have a question about the Powder & Ombre Brows Training Course."))
                .contains("first_contact_autoreply_academy");
    }

    @Test
    void editedOrExtendedTextsGetNoTemplate() {
        assertThat(FirstContactAutoReplyService.templateKeyFor(
                "Hi! I have a question about permanent lip liner and price. Thanks")).isEmpty();
        assertThat(FirstContactAutoReplyService.templateKeyFor(
                "Hi! I have a question about a nail appointment. Do you allow men?")).isEmpty();
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! How long do the nano brows last?")).isEmpty();
        assertThat(FirstContactAutoReplyService.templateKeyFor("Hi! I have a question about my order.")).isEmpty();
        assertThat(FirstContactAutoReplyService.templateKeyFor(null)).isEqualTo(Optional.empty());
    }

    @Test
    void sendsAfterAHalfMinuteDelayToANumberWeHaveNeverTexted() {
        boolean scheduled = service.maybeReply(1L, PHONE, "Hi! I have a question about a nail appointment.");

        assertThat(scheduled).isTrue();
        verify(sms, never()).sendTemplated(any(), anyString(), anyString(), anyMap());
        var delay = ArgumentCaptor.forClass(Long.class);
        verify(scheduler).schedule(task.capture(), delay.capture(), eq(TimeUnit.SECONDS));
        assertThat(delay.getValue()).isBetween(30L, 45L);

        task.getValue().run();
        verify(sms).sendTemplated(eq(1L), eq("first_contact_autoreply_nails"), eq(PHONE), eq(Map.of()));
    }

    @Test
    void skipsWhenAutomationIsOff() {
        when(automations.isEnabled(1L, FirstContactAutoReplyService.AUTOMATION_KEY)).thenReturn(false);

        assertThat(service.maybeReply(1L, PHONE, "Hi! I have a question about a nail appointment.")).isFalse();
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void skipsWhenWeHaveAlreadyTextedThisNumber() {
        when(messages.existsByBusinessIdAndPhoneNumberAndDirectionAndStatus(1L, PHONE, "OUTBOUND", "SENT"))
                .thenReturn(true);

        assertThat(service.maybeReply(1L, PHONE, "Hi! I have a question about a nail appointment.")).isFalse();
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void staffAnsweringDuringTheDelayCancelsTheReply() {
        service.maybeReply(1L, PHONE, "Hi! I have a question about a nail appointment.");
        verify(scheduler).schedule(task.capture(), anyLong(), any());
        when(messages.existsByBusinessIdAndPhoneNumberAndDirectionAndStatus(1L, PHONE, "OUTBOUND", "SENT"))
                .thenReturn(true);

        task.getValue().run();

        verify(sms, never()).sendTemplated(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void switchingTheAutomationOffDuringTheDelayCancelsTheReply() {
        service.maybeReply(1L, PHONE, "Hi! I have a question about a nail appointment.");
        verify(scheduler).schedule(task.capture(), anyLong(), any());
        when(automations.isEnabled(1L, FirstContactAutoReplyService.AUTOMATION_KEY)).thenReturn(false);

        task.getValue().run();

        verify(sms, never()).sendTemplated(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void sendFailureNeverEscapes() {
        when(sms.sendTemplated(any(), anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("boom"));
        service.maybeReply(1L, PHONE, "Hi! I have a question about a nail appointment.");
        verify(scheduler).schedule(task.capture(), anyLong(), any());

        task.getValue().run();
    }
}
