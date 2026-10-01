package com.salonreview.sms;

import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.repo.SameDayRebookingSendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** See openspec/changes/same-day-rebooking-discount design.md D1/D2. */
class SameDayRebookingTriggerServiceTest {

    private SameDayRebookingSendRepository repository;
    private SameDayRebookingTriggerService trigger;

    @BeforeEach
    void setUp() {
        repository = mock(SameDayRebookingSendRepository.class);
        trigger = new SameDayRebookingTriggerService(repository);
    }

    @Test
    @DisplayName("enqueues a new AWAITING_SEND row due 1h after checkout, promo to that night's midnight")
    void enqueuesNewRow() {
        trigger = new SameDayRebookingTriggerService(repository, Clock.fixed(pt(2026, 9, 30, 14, 0), ZONE));
        trigger.enqueue(1L, "pay1", "cust1", "+15551234567", "Jane");

        ArgumentCaptor<SameDayRebookingSend> captor = ArgumentCaptor.forClass(SameDayRebookingSend.class);
        verify(repository).save(captor.capture());
        SameDayRebookingSend saved = captor.getValue();
        assertThat(saved.getBusinessId()).isEqualTo(1L);
        assertThat(saved.getState()).isEqualTo(SameDayRebookingSend.STATE_AWAITING_SEND);
        assertThat(saved.getSquarePaymentId()).isEqualTo("pay1");
        assertThat(saved.getSquareCustomerId()).isEqualTo("cust1");
        assertThat(saved.getPhoneNumber()).isEqualTo("+15551234567");
        assertThat(saved.getCustomerName()).isEqualTo("Jane");
        assertThat(saved.getSendDueAt()).isEqualTo(pt(2026, 9, 30, 15, 0));
        assertThat(saved.getPromoExpiresAt()).isEqualTo(pt(2026, 10, 1, 0, 0));
    }

    // --- timing rule (owner decision 2026-10-01), all in salon (Pacific) time ---------------------

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

    private static Instant pt(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZONE).toInstant();
    }

    private static void assertSchedule(Instant paidAt, Instant expectedDue, Instant expectedPromoEnd) {
        SameDayRebookingTriggerService.Schedule s = SameDayRebookingTriggerService.scheduleFor(paidAt);
        assertThat(s.sendDueAt()).isEqualTo(expectedDue);
        assertThat(s.promoExpiresAt()).isEqualTo(expectedPromoEnd);
    }

    @Test
    @DisplayName("daytime checkout: text 1h later, offer and 19:00 email the same day")
    void daytime() {
        assertSchedule(pt(2026, 9, 30, 14, 0), pt(2026, 9, 30, 15, 0), pt(2026, 10, 1, 0, 0));
        assertSchedule(pt(2026, 9, 30, 17, 30), pt(2026, 9, 30, 18, 30), pt(2026, 10, 1, 0, 0));
    }

    @Test
    @DisplayName("text lands 19:00-20:45: offer runs to the next midnight, so the next day's 19:00 email is still true")
    void evening() {
        assertSchedule(pt(2026, 9, 30, 18, 30), pt(2026, 9, 30, 19, 30), pt(2026, 10, 2, 0, 0));
    }

    @Test
    @DisplayName("checkout 19:46-20:30 (e.g. Ina, paid 19:47): capped at 20:45, offer to the next midnight")
    void lateCappedAt2045() {
        assertSchedule(pt(2026, 9, 30, 19, 47), pt(2026, 9, 30, 20, 45), pt(2026, 10, 2, 0, 0));
        assertSchedule(pt(2026, 9, 30, 20, 30), pt(2026, 9, 30, 20, 45), pt(2026, 10, 2, 0, 0));
    }

    @Test
    @DisplayName("checkout after 20:30: next morning 10:00, offer to the end of that day")
    void veryLateMovesToNextMorning() {
        assertSchedule(pt(2026, 9, 30, 20, 35), pt(2026, 10, 1, 10, 0), pt(2026, 10, 2, 0, 0));
        assertSchedule(pt(2026, 9, 30, 23, 10), pt(2026, 10, 1, 10, 0), pt(2026, 10, 2, 0, 0));
    }

    @Test
    @DisplayName("checkout before 08:00: never texted before 09:00")
    void earlyMorningWaitsUntilNine() {
        assertSchedule(pt(2026, 9, 30, 7, 30), pt(2026, 9, 30, 9, 0), pt(2026, 10, 1, 0, 0));
    }

    @Test
    @DisplayName("DST end (Nov 1, 2026): next-morning send is still 10:00 local, offer to local midnight")
    void dstFallBack() {
        assertSchedule(pt(2026, 10, 31, 20, 40), pt(2026, 11, 1, 10, 0), pt(2026, 11, 2, 0, 0));
    }

    @Test
    @DisplayName("send window is 09:00-20:45 salon time")
    void sendWindow() {
        assertThat(SameDayRebookingTriggerService.isWithinSendWindow(pt(2026, 9, 30, 8, 59))).isFalse();
        assertThat(SameDayRebookingTriggerService.isWithinSendWindow(pt(2026, 9, 30, 9, 0))).isTrue();
        assertThat(SameDayRebookingTriggerService.isWithinSendWindow(pt(2026, 9, 30, 20, 45))).isTrue();
        assertThat(SameDayRebookingTriggerService.isWithinSendWindow(pt(2026, 9, 30, 20, 46))).isFalse();
    }

    @Test
    @DisplayName("Square redelivering the same payment id → not enqueued twice")
    void doesNotEnqueueTwiceForSamePayment() {
        when(repository.existsBySquarePaymentId("pay1")).thenReturn(true);

        trigger.enqueue(1L, "pay1", "cust1", "+15551234567", "Jane");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("same phone number already sent one today (different payment id) → not enqueued twice")
    void doesNotEnqueueTwiceForSamePhoneSameDay() {
        when(repository.existsByBusinessIdAndPhoneNumberAndCreatedAtAfter(eq(1L), eq("+15551234567"), any()))
                .thenReturn(true);

        trigger.enqueue(1L, "pay2", "cust1", "+15551234567", "Jane");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("repository failure is swallowed — never throws back to the webhook controller")
    void neverThrowsOnFailure() {
        when(repository.existsBySquarePaymentId(any())).thenThrow(new RuntimeException("db down"));

        trigger.enqueue(1L, "pay1", "cust1", "+15551234567", "Jane");
        // no assertion needed beyond "didn't throw" — the test method completing is the assertion
    }
}
