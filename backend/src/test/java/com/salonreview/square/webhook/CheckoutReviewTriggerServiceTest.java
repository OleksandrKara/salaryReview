package com.salonreview.square.webhook;

import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.sms.SameDayRebookingTriggerService;
import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The in-salon-checkout vs booking-linked-order filter — see openspec/changes/sms-automations-hub
 * design.md D2, confirmed against real Square payload shapes before this was written (tasks.md 1.1/1.2).
 */
class CheckoutReviewTriggerServiceTest {

    private static final String PHONE = "+15551234567";

    private SquareClient square;
    private SmsReplyFlowRepository repository;
    private SameDayRebookingTriggerService rebookingTrigger;
    private CheckoutReviewTriggerService service;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        repository = mock(SmsReplyFlowRepository.class);
        rebookingTrigger = mock(SameDayRebookingTriggerService.class);
        service = new CheckoutReviewTriggerService(square, repository, rebookingTrigger);
    }

    private static SquareWebhookEvent.Payment payment(String status, String orderId, String customerId) {
        return new SquareWebhookEvent.Payment("pay_1", status, orderId, customerId);
    }

    private static SquareClient.Order order(String customerId, List<SquareClient.Fulfillment> fulfillments) {
        return new SquareClient.Order("order_1", "loc_1", customerId, "COMPLETED", "2026-07-01T00:00:00Z",
                "2026-07-01T00:00:00Z", null, null, null, null, fulfillments);
    }

    @Test
    @DisplayName("walk-in POS order (no fulfillments) with phone on file → flow row enqueued")
    void walkInOrderEnqueuesFlow() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", "cust_1"));

        var captor = org.mockito.ArgumentCaptor.forClass(SmsReplyFlow.class);
        verify(repository).save(captor.capture());
        SmsReplyFlow saved = captor.getValue();
        assertThat(saved.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(saved.getCustomerName()).isEqualTo("Jane");
        assertThat(saved.getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_SEND);
        assertThat(saved.getSquarePaymentId()).isEqualTo("pay_1");

        // Second, independent enqueue off the same qualifying event — see
        // openspec/changes/same-day-rebooking-discount design.md D1.
        verify(rebookingTrigger).enqueue("pay_1", "cust_1", PHONE, "Jane");
    }

    @Test
    @DisplayName("booking-linked order (fulfillments contains BOOKING) → no flow row created")
    void bookingLinkedOrderSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1"))
                .thenReturn(Optional.of(order("cust_1", List.of(new SquareClient.Fulfillment("BOOKING", "PROPOSED")))));

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
        verify(square, never()).customerPhone(any()); // no phone lookup should even happen
    }

    @Test
    @DisplayName("no phone on file for the customer → no flow row created, silent skip")
    void noPhoneOnFileSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(null);

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("no customer on the order at all → no flow row created")
    void noCustomerOnOrderSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order(null, null)));

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", null));

        verify(repository, never()).save(any());
        verify(square, never()).customerPhone(any());
    }

    @Test
    @DisplayName("payment not COMPLETED → ignored")
    void nonCompletedPaymentIgnored() {
        service.handlePaymentUpdated(payment("PENDING", "order_1", "cust_1"));

        verifyNoInteractions(square, repository);
    }

    @Test
    @DisplayName("duplicate Square redelivery of the same payment id → idempotent, no second flow row")
    void duplicateEventIsIdempotent() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(true);

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
        verifyNoInteractions(square);
    }

    @Test
    @DisplayName("order lookup fails (not found) → no flow row, no exception")
    void orderNotFoundSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.empty());

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("unexpected exception anywhere in the flow → swallowed, never thrown back to the controller")
    void exceptionNeverPropagates() {
        when(repository.existsBySquarePaymentId("pay_1")).thenThrow(new RuntimeException("boom"));

        service.handlePaymentUpdated(payment("COMPLETED", "order_1", "cust_1"));
        // no assertion needed beyond "didn't throw" — the @Test method completing is the assertion
    }
}
