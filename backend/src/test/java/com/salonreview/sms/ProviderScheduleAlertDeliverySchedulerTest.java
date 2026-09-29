package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.telegram.ProviderScheduleTelegramSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.salonreview.sms.ProviderScheduleTestFixtures.BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderScheduleAlertDeliverySchedulerTest {
    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final SmsAutomationService automations = mock(SmsAutomationService.class);
    private final ProviderScheduleClosureAlertConfigService config = mock(ProviderScheduleClosureAlertConfigService.class);
    private final ProviderScheduleChangeStore store = mock(ProviderScheduleChangeStore.class);
    private final ProviderScheduleTelegramSender telegram = mock(ProviderScheduleTelegramSender.class);
    private final CurrentBusinessContext context = new CurrentBusinessContext();
    private final ProviderScheduleChangeStore.PendingAlert alert = new ProviderScheduleChangeStore.PendingAlert(
            42, "Tatiana", BASE.plusSeconds(14400), BASE.plusSeconds(28800), 24, 240, "America/Los_Angeles");
    private ProviderScheduleAlertDeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(businesses.findAllByActiveTrue()).thenReturn(List.of(Business.builder().id(1L).build()));
        when(automations.isEnabled(1L, "provider_schedule_closure_alert")).thenReturn(true);
        when(config.getSettings(1L)).thenReturn(policy(false));
        when(store.pendingAlerts(1L, BASE)).thenReturn(List.of(alert));
        when(store.claim(1L, 42, BASE)).thenReturn(true);
        when(telegram.send(eq(1L), anyString(), any(), any(), anyInt(), anyString())).thenAnswer(invocation -> {
            assertThat(context.id()).isEqualTo(1L);
            return new ProviderScheduleTelegramSender.Result("SENT", 321L);
        });
        scheduler = new ProviderScheduleAlertDeliveryScheduler(businesses, automations, config, store, telegram,
                context, Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @Test
    void claimsBeforeNetworkAndRecordsActualResultWithinBusinessContext() {
        scheduler.sendPending();
        var order = inOrder(store, telegram);
        order.verify(store).claim(1L, 42, BASE);
        order.verify(telegram).send(1L, "Tatiana", alert.firstStart(), alert.lastStart(), 24, "America/Los_Angeles");
        order.verify(store).delivered(1L, 42, "SENT", 321L, BASE);
        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    void disabledAutomationAndObservationModeCannotSend() {
        when(config.getSettings(1L)).thenReturn(policy(true));
        scheduler.sendPending();
        verifyNoInteractions(telegram);
        verify(store, never()).claim(any(), anyLong(), any());
        when(automations.isEnabled(1L, "provider_schedule_closure_alert")).thenReturn(false);
        scheduler.sendPending();
        verifyNoInteractions(telegram);
    }

    @Test
    void failedClaimPreventsDuplicateDelivery() {
        when(store.claim(1L, 42, BASE)).thenReturn(false);
        scheduler.sendPending();
        verifyNoInteractions(telegram);
        verify(store, never()).delivered(any(), anyLong(), any(), any(), any());
    }

    @Test
    void changedPolicyDoesNotSendEvidenceFromOldPolicy() {
        when(config.getSettings(1L)).thenReturn(new ProviderScheduleClosureAlertConfigService.Settings(12, 240, false, null, null));
        scheduler.sendPending();
        when(config.getSettings(1L)).thenReturn(new ProviderScheduleClosureAlertConfigService.Settings(24, 300, false, null, null));
        scheduler.sendPending();
        verify(store, never()).claim(any(), anyLong(), any());
        verifyNoInteractions(telegram);
    }

    @Test
    void ambiguousTelegramOutcomeIsRecordedWithoutImmediateRetry() {
        when(telegram.send(eq(1L), anyString(), any(), any(), anyInt(), anyString()))
                .thenReturn(new ProviderScheduleTelegramSender.Result("UNKNOWN", null));
        scheduler.sendPending();
        verify(telegram, times(1)).send(any(), any(), any(), any(), anyInt(), any());
        verify(store).delivered(1L, 42, "UNKNOWN", null, BASE);
    }

    @Test
    void oneBusinessFailureDoesNotStopAnotherBusinessOrLeakContext() {
        when(businesses.findAllByActiveTrue()).thenReturn(List.of(Business.builder().id(1L).build(), Business.builder().id(2L).build()));
        when(config.getSettings(1L)).thenThrow(new IllegalStateException("database"));
        when(automations.isEnabled(2L, "provider_schedule_closure_alert")).thenReturn(true);
        when(config.getSettings(2L)).thenReturn(policy(true));
        scheduler.sendPending();
        verify(config).getSettings(2L);
        verify(store).recoverAmbiguousAttempts(2L, BASE);
        assertThat(context.isPopulated()).isFalse();
    }

    private static ProviderScheduleClosureAlertConfigService.Settings policy(boolean shadow) {
        return new ProviderScheduleClosureAlertConfigService.Settings(24, 240, shadow, null, null);
    }
}
