package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.sms.SmsAutomationService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntakeFormSchedulerTest {

    // 2026-11-18 18:00 UTC = 10:00 PT (daytime); 08:00 UTC = 00:00 PT (night)
    static final Instant DAY = Instant.parse("2026-11-18T18:00:00Z");
    static final Instant NIGHT = Instant.parse("2026-11-18T08:00:00Z");

    SquareBookingMirrorRepository bookings = mock(SquareBookingMirrorRepository.class);
    IntakeFormRepository forms = mock(IntakeFormRepository.class);
    FirstVisitDetector detector = mock(FirstVisitDetector.class);
    IntakeFormService service = mock(IntakeFormService.class);
    SmsAutomationService automations = mock(SmsAutomationService.class);
    SquareClientProvider provider = mock(SquareClientProvider.class);
    SquareClient square = mock(SquareClient.class);
    TelegramNotificationService telegram = mock(TelegramNotificationService.class);

    @BeforeEach
    void setUp() {
        when(provider.forBusiness(1L)).thenReturn(square);
        when(automations.isEnabled(1L, IntakeFormScheduler.AUTOMATION_KEY)).thenReturn(true);
    }

    IntakeFormScheduler at(Instant now, Set<Long> businesses) {
        return new IntakeFormScheduler(businesses, bookings, forms, detector, service, automations, provider, telegram,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    SquareBookingMirror booking(String status, Instant start) {
        return SquareBookingMirror.builder().businessId(1L).squareBookingId("B1").squareCustomerId("C1")
                .status(status).startAt(start).build();
    }

    IntakeForm pending(Instant visit) {
        return IntakeForm.builder().id(1L).businessId(1L).squareBookingId("B1").squareCustomerId("C1")
                .status(IntakeForm.PENDING).appointmentStartAt(visit).build();
    }

    void liveBooking(String status) {
        SquareClient.Booking b = new SquareClient.Booking("B1", status, null, null, null, null, "C1", null, null, null);
        when(square.retrieveBooking("B1")).thenReturn(Optional.of(b));
    }

    @Test
    void flagBlankDoesNothing() {
        at(DAY, IntakeFormScheduler.parse("")).tick();
        verifyNoInteractions(bookings, forms, service, detector);
    }

    @Test
    void automationOffDoesNothing() {
        when(automations.isEnabled(1L, IntakeFormScheduler.AUTOMATION_KEY)).thenReturn(false);
        at(DAY, Set.of(1L)).tick();
        verifyNoInteractions(bookings, forms, service, detector);
    }

    @Test
    void otherBusinessIsNeverTouched() {
        at(DAY, Set.of(2L)).tick();
        verifyNoInteractions(forms, service);
    }

    @Test
    void parseHandlesListAndSpaces() {
        assertThat(IntakeFormScheduler.parse(" 1, 3 ")).containsExactlyInAnyOrder(1L, 3L);
        assertThat(IntakeFormScheduler.parse(null)).isEmpty();
    }

    @Test
    void detectCreatesFormOnlyForFirstVisits() {
        SquareBookingMirror first = booking("ACCEPTED", DAY.plusSeconds(86400));
        when(bookings.findByBusinessIdAndStatusAndCreatedAtBetween(anyLong(), anyString(), any(), any())).thenReturn(List.of(first));
        when(forms.findByBusinessIdAndSquareBookingIdIn(1L, List.of("B1"))).thenReturn(List.of());
        FirstVisitDetector.Result yes = new FirstVisitDetector.Result(FirstVisitDetector.Verdict.FIRST_VISIT, Set.of("C1"), "+15551234567");
        when(detector.evaluate(first)).thenReturn(yes);
        at(DAY, Set.of(1L)).detectNewClients(1L);
        verify(service).create(first, yes);
    }

    @Test
    void detectSkipsReturningAndAlreadyHandled() {
        SquareBookingMirror b = booking("ACCEPTED", DAY.plusSeconds(86400));
        when(bookings.findByBusinessIdAndStatusAndCreatedAtBetween(anyLong(), anyString(), any(), any())).thenReturn(List.of(b));
        when(detector.evaluate(b)).thenReturn(new FirstVisitDetector.Result(FirstVisitDetector.Verdict.RETURNING, Set.of("C1"), "+1"));
        at(DAY, Set.of(1L)).detectNewClients(1L);
        verify(service, never()).create(any(), any());
        when(forms.findByBusinessIdAndSquareBookingIdIn(1L, List.of("B1"))).thenReturn(List.of(pending(DAY)));
        at(DAY, Set.of(1L)).detectNewClients(1L);
        verify(service, never()).create(any(), any());
    }

    @Test
    void nightQueuesNothingAndSendsNothing() {
        at(NIGHT, Set.of(1L)).sendQueued(1L);
        at(NIGHT, Set.of(1L)).sendReminders(1L);
        verifyNoInteractions(service, forms);
    }

    @Test
    void daytimeSendsOnceForAcceptedFutureBooking() {
        IntakeForm f = pending(DAY.plusSeconds(3 * 86400));
        when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of(f));
        when(bookings.findByBusinessIdAndSquareBookingId(1L, "B1")).thenReturn(Optional.of(booking("ACCEPTED", f.getAppointmentStartAt())));
        liveBooking("ACCEPTED");
        at(DAY, Set.of(1L)).sendQueued(1L);
        verify(service).sendText(f, false);
    }

    @Test
    void cancelledBookingIsSkippedTerminally() {
        IntakeForm f = pending(DAY.plusSeconds(86400));
        when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of(f));
        when(bookings.findByBusinessIdAndSquareBookingId(1L, "B1")).thenReturn(Optional.of(booking("CANCELLED_BY_CUSTOMER", f.getAppointmentStartAt())));
        at(DAY, Set.of(1L)).sendQueued(1L);
        verify(service, never()).sendText(any(), anyBoolean());
        assertThat(f.getInitialSmsState()).isEqualTo("SKIPPED_CANCELLED");
    }

    @Test
    void cancelledInLiveSquareIsSkipped() {
        IntakeForm f = pending(DAY.plusSeconds(86400));
        when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of(f));
        when(bookings.findByBusinessIdAndSquareBookingId(1L, "B1")).thenReturn(Optional.of(booking("ACCEPTED", f.getAppointmentStartAt())));
        liveBooking("CANCELLED_BY_SELLER");
        at(DAY, Set.of(1L)).sendQueued(1L);
        verify(service, never()).sendText(any(), anyBoolean());
        assertThat(f.getInitialSmsState()).isEqualTo("SKIPPED_CANCELLED");
    }

    @Test
    void tooLateAndAlreadyStartedAreSkipped() {
        for (long offset : new long[]{600, -3600}) {
            IntakeForm f = pending(DAY.plusSeconds(offset));
            when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of(f));
            when(bookings.findByBusinessIdAndSquareBookingId(1L, "B1")).thenReturn(Optional.of(booking("ACCEPTED", f.getAppointmentStartAt())));
            at(DAY, Set.of(1L)).sendQueued(1L);
            assertThat(f.getInitialSmsState()).isEqualTo("SKIPPED_TOO_LATE");
        }
        verify(service, never()).sendText(any(), anyBoolean());
    }

    @Test
    void signedBeforeSendIsSkipped() {
        IntakeForm f = pending(DAY.plusSeconds(86400));
        f.setStatus(IntakeForm.SIGNED);
        when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of(f));
        at(DAY, Set.of(1L)).sendQueued(1L);
        verify(service, never()).sendText(any(), anyBoolean());
        assertThat(f.getInitialSmsState()).isEqualTo("SKIPPED_SIGNED");
    }

    @Test
    void squareOutageRetriesNextTickWithoutSendingOrMarking() {
        IntakeForm f = pending(DAY.plusSeconds(86400));
        when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of(f));
        when(bookings.findByBusinessIdAndSquareBookingId(1L, "B1")).thenReturn(Optional.of(booking("ACCEPTED", f.getAppointmentStartAt())));
        when(square.retrieveBooking("B1")).thenThrow(new RuntimeException("503"));
        at(DAY, Set.of(1L)).sendQueued(1L);
        verify(service, never()).sendText(any(), anyBoolean());
        assertThat(f.getInitialSmsState()).isNull();
    }

    private IntakeForm reminderCandidate(Instant visit, Instant sentAt) {
        IntakeForm f = pending(visit);
        f.setStatus(IntakeForm.SENT);
        f.setSentAt(sentAt);
        when(forms.findByBusinessIdAndStatusAndReminderSmsStateIsNullAndAppointmentStartAtBetween(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(f));
        when(bookings.findByBusinessIdAndSquareBookingId(1L, "B1")).thenReturn(Optional.of(booking("ACCEPTED", visit)));
        liveBooking("ACCEPTED");
        return f;
    }

    @Test
    void reminderSentOnceInWindowForUnsigned() {
        IntakeForm f = reminderCandidate(DAY.plusSeconds(24 * 3600), DAY.minusSeconds(20 * 3600));
        at(DAY, Set.of(1L)).sendReminders(1L);
        verify(service).sendText(f, true);
    }

    @Test
    void reminderNotSentWhenFirstTextWasJustSent() {
        reminderCandidate(DAY.plusSeconds(24 * 3600), DAY.minusSeconds(3600));
        at(DAY, Set.of(1L)).sendReminders(1L);
        verify(service, never()).sendText(any(), anyBoolean());
    }

    @Test
    void reminderNotSentOnceVisitStartedOrCancelled() {
        IntakeForm started = reminderCandidate(DAY.minusSeconds(600), DAY.minusSeconds(20 * 3600));
        at(DAY, Set.of(1L)).sendReminders(1L);
        verify(service, never()).sendText(any(), anyBoolean());
        assertThat(started.getReminderSmsState()).isEqualTo("SKIPPED_TOO_LATE");
    }

    @Test
    void repeatedTicksAreIdempotentBecauseStateFiltersExcludeHandledForms() {
        IntakeForm f = pending(DAY.plusSeconds(86400));
        f.setInitialSmsState("SENT");
        when(forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)).thenReturn(List.of());
        at(DAY, Set.of(1L)).sendQueued(1L);
        at(DAY, Set.of(1L)).sendQueued(1L);
        verify(service, never()).sendText(any(), anyBoolean());
    }

    @Test
    void squareSyncWritesStatusLineAndRecordsIt() {
        IntakeForm f = pending(DAY);
        f.setStatus(IntakeForm.SENT);
        when(forms.findNeedingSquareSync(IntakeFormScheduler.MAX_SQUARE_SYNC_ATTEMPTS)).thenReturn(List.of(f));
        at(DAY, Set.of(1L)).syncSquare();
        verify(square).updateCustomerNote(org.mockito.ArgumentMatchers.eq("C1"), any());
        assertThat(f.getSquareSyncedStatus()).isEqualTo(IntakeForm.SENT);
    }

    @Test
    void squareSyncFailureAlertsOwnerOnlyOnLastAttempt() {
        IntakeForm f = pending(DAY);
        f.setSquareSyncAttempts(IntakeFormScheduler.MAX_SQUARE_SYNC_ATTEMPTS - 1);
        when(forms.findNeedingSquareSync(IntakeFormScheduler.MAX_SQUARE_SYNC_ATTEMPTS)).thenReturn(List.of(f));
        org.mockito.Mockito.doThrow(new RuntimeException("403")).when(square).updateCustomerNote(anyString(), any());
        at(DAY, Set.of(1L)).syncSquare();
        verify(telegram).sendOwnerAlert(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.argThat((String s) -> !s.contains("allerg")));
    }
}
