package com.salonreview.sms;

import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.repo.SameDayRebookingSendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("enqueues a new AWAITING_SEND row with send_due_at ~3h out and a future promo_expires_at")
    void enqueuesNewRow() {
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
        assertThat(saved.getSendDueAt()).isAfter(Instant.now().plusSeconds(3 * 3600 - 5));
        assertThat(saved.getSendDueAt()).isBefore(Instant.now().plusSeconds(3 * 3600 + 5));
        assertThat(saved.getPromoExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Square redelivering the same payment id → not enqueued twice")
    void doesNotEnqueueTwiceForSamePayment() {
        when(repository.existsBySquarePaymentId("pay1")).thenReturn(true);

        trigger.enqueue(1L, "pay1", "cust1", "+15551234567", "Jane");

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
