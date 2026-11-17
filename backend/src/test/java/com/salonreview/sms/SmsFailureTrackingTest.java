package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SmsFailureTrackingTest {

    private SmsMessageRepository repository;
    private TelegramNotificationService telegram;
    private SmsMessageLogService logService;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageRepository.class);
        when(repository.save(any(SmsMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        telegram = mock(TelegramNotificationService.class);
        logService = new SmsMessageLogService(repository, mock(SmsEventBroadcaster.class));
        logService.setFailureAlerts(new SmsFailureAlertService(telegram));
    }

    private SmsMessage automationRow() {
        return SmsMessage.builder().id(7L).businessId(1L).direction("OUTBOUND").phoneNumber("+15551230247")
                .automationKey("lead_follow_up").templateKey("lead_follow_up_1").status("SENT").build();
    }

    @Test
    void sendFailureKeepsTwilioCodeAndAlertsOnce() {
        SmsMessage m = automationRow();
        logService.recordSendFailure(m, new TwilioSendException("x", 400, "21211", null));
        logService.recordSendFailure(automationRow(), new TwilioSendException("x", 400, "21211", null));

        assertThat(m.getStatus()).isEqualTo("NOT_SENT");
        assertThat(m.getDeliveryStatus()).isEqualTo("failed");
        assertThat(m.getDeliveryErrorCode()).isEqualTo("21211");
        assertThat(m.getDeliveryErrorMessage()).isEqualTo("Invalid phone number");
        verify(telegram, times(1)).sendOwnerAlert(eq(1L), contains("...0247"));
    }

    @Test
    void manualSendFailureIsNotAlertedBecauseTheManagerSeesIt() {
        SmsMessage m = automationRow();
        m.setSentByUserId(5L);
        logService.recordSendFailure(m, new TwilioSendException("t", 0, "timeout", null));

        assertThat(m.getDeliveryErrorCode()).isEqualTo("timeout");
        verifyNoInteractions(telegram);
    }

    @Test
    void unknownExceptionGetsUnknownCode() {
        SmsMessage m = automationRow();
        logService.recordSendFailure(m, new RuntimeException("boom"));
        assertThat(m.getDeliveryErrorCode()).isEqualTo("unknown");
    }

    @Test
    void undeliveredCallbackAlertsButDeliveredDoesNot() {
        SmsMessage m = automationRow();
        m.setTwilioMessageSid("SM1");
        when(repository.findByTwilioMessageSid("SM1")).thenReturn(Optional.of(m));

        logService.updateDeliveryStatus("SM1", "delivered", null);
        verifyNoInteractions(telegram);

        logService.updateDeliveryStatus("SM1", "undelivered", "30003");
        verify(telegram).sendOwnerAlert(eq(1L), contains("30003"));
    }

    @Test
    void reconcilerAppliesTwilioStatusOrAlertsWhenStillQueued() throws Exception {
        SmsMessage lost = automationRow();
        lost.setTwilioMessageSid("SMa");
        SmsMessage queued = automationRow();
        queued.setId(8L);
        queued.setTwilioMessageSid("SMb");
        when(repository.findStuckDeliveries(any(), any(), any())).thenReturn(List.of(lost, queued));
        when(repository.findById(anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0).equals(7L) ? lost : queued));
        when(repository.findByTwilioMessageSid("SMa")).thenReturn(Optional.of(lost));
        TwilioSmsConfigService configService = mock(TwilioSmsConfigService.class);
        when(configService.get(1L)).thenReturn(TwilioSmsConfig.builder().accountSid("AC").apiKey("SK").apiSecret("s").fromPhoneNumber("+1555").build());
        TwilioSmsClient client = mock(TwilioSmsClient.class);
        when(client.fetchStatus(any(), eq("SMa"))).thenReturn(new TwilioSmsClient.MessageStatus("delivered", null));
        when(client.fetchStatus(any(), eq("SMb"))).thenReturn(new TwilioSmsClient.MessageStatus("queued", null));
        SmsDeliveryReconciler r = new SmsDeliveryReconciler(repository, logService, configService, client, new SmsFailureAlertService(telegram));

        assertThat(r.run(Instant.now())).isEqualTo(2);

        assertThat(lost.getDeliveryStatus()).isEqualTo("delivered");
        assertThat(lost.getStuckAlertedAt()).isNotNull();
        assertThat(queued.getStuckAlertedAt()).isNotNull();
        verify(telegram, times(1)).sendOwnerAlert(eq(1L), contains("stuck"));
    }

    @Test
    void twilioErrorBodyCodeIsParsed() {
        assertThat(TwilioSmsClient.errorCodeOf("{\"code\":21610,\"message\":\"x\"}")).isEqualTo("21610");
        assertThat(TwilioSmsClient.errorCodeOf("not json")).isNull();
    }
}
