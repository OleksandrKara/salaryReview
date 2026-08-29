package com.salonreview.square.webhook;

import com.salonreview.domain.Business;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.sms.CheckoutReviewLinks;
import com.salonreview.sms.SameDayRebookingTriggerService;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
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
 * 2026-08-17: this class used to also filter out any order with a {@code BOOKING}-type
 * fulfillment, on design.md D2's assumption that meant "an online prepaid booking, not an
 * in-salon checkout." Checked against 99 real completed orders from this account's own Square
 * history: 27 were BOOKING-linked AND {@code source.name == "Point of Sale"} — an ordinary
 * in-salon checkout for a customer who simply had an appointment on the books, true of nearly
 * every visit here. The filter was silently discarding roughly a third of all real checkouts
 * since this automation launched — removed, see {@link CheckoutReviewTriggerService
 * #handlePaymentUpdated}'s own comment.
 */
class CheckoutReviewTriggerServiceTest {

    private static final String PHONE = "+15551234567";
    private static final Long BUSINESS_ID = 1L;

    private SquareClient square;
    private SmsReplyFlowRepository repository;
    private SameDayRebookingTriggerService rebookingTrigger;
    private SmsMessageLogService messageLogService;
    private BusinessRepository businessRepository;
    private CheckoutReviewTriggerService service;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        repository = mock(SmsReplyFlowRepository.class);
        rebookingTrigger = mock(SameDayRebookingTriggerService.class);
        messageLogService = mock(SmsMessageLogService.class);
        businessRepository = mock(BusinessRepository.class);
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(
                Business.builder().id(BUSINESS_ID).googleReviewUrl("https://g.page/r/review")
                        .yelpReviewUrl("https://www.yelp.com/writeareview/biz/review")
                        .feedbackFormUrl("https://forms.gle/feedback").build()));
        service = new CheckoutReviewTriggerService(squareClientProvider, repository, rebookingTrigger,
                messageLogService, businessRepository);
    }

    private static SquareWebhookEvent.Payment payment(String status, String orderId, String customerId) {
        return new SquareWebhookEvent.Payment("pay_1", status, orderId, customerId, null, null, null);
    }

    private static SquareClient.Order order(String customerId, List<SquareClient.Fulfillment> fulfillments) {
        return new SquareClient.Order("order_1", "loc_1", customerId, "COMPLETED", "2026-07-01T00:00:00Z",
                "2026-07-01T00:00:00Z", null, null, null, null, fulfillments, null);
    }

    @Test
    @DisplayName("walk-in POS order (no fulfillments) with phone on file → flow row enqueued")
    void walkInOrderEnqueuesFlow() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        var captor = org.mockito.ArgumentCaptor.forClass(SmsReplyFlow.class);
        verify(repository).save(captor.capture());
        SmsReplyFlow saved = captor.getValue();
        assertThat(saved.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(saved.getCustomerName()).isEqualTo("Jane");
        assertThat(saved.getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_SEND);
        assertThat(saved.getSquarePaymentId()).isEqualTo("pay_1");

        // Second, independent enqueue off the same qualifying event — see
        // openspec/changes/same-day-rebooking-discount design.md D1.
        verify(rebookingTrigger).enqueue(BUSINESS_ID, "pay_1", "cust_1", PHONE, "Jane");
    }

    @Test
    @DisplayName("business has no Google-review/Yelp-review/feedback-form URL configured yet → no checkout_review_request flow, "
            + "but the independent same-day-rebooking trigger still fires")
    void reviewLinksNotConfiguredSkipsFlowButNotRebookingTrigger() {
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(Business.builder().id(BUSINESS_ID).build()));
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
        verify(rebookingTrigger).enqueue(BUSINESS_ID, "pay_1", "cust_1", PHONE, "Jane");
    }

    @Test
    @DisplayName("phone already got a checkout_review_request today (different payment, e.g. a family member's separate checkout) → "
            + "no second flow row, but the independent same-day-rebooking trigger still fires")
    void alreadyAskedTodaySkipsFlowButNotRebookingTrigger() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(repository.existsByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("checkout_review_request"), any())).thenReturn(true);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
        verify(rebookingTrigger).enqueue(BUSINESS_ID, "pay_1", "cust_1", PHONE, "Jane");
    }

    @Test
    @DisplayName("phone has already clicked GOOGLE_REVIEW, YELP_REVIEW, and FEEDBACK_FORM at least once each → row saved COMPLETED, not sent")
    void allReviewChannelsCoveredSkipsSendButStaysIdempotent() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.YELP_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.FEEDBACK_FORM_TARGET)).thenReturn(true);

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        var captor = org.mockito.ArgumentCaptor.forClass(SmsReplyFlow.class);
        // The row is still saved (not skipped outright) so a later Square redelivery of this same
        // payment id still hits the existsBySquarePaymentId guard above instead of re-running this
        // whole method — see the service's own doc comment.
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SmsReplyFlow.STATE_COMPLETED);
        assertThat(captor.getValue().getSquarePaymentId()).isEqualTo("pay_1");

        // The independent same-day-rebooking trigger is unaffected by review-channel coverage.
        verify(rebookingTrigger).enqueue(BUSINESS_ID, "pay_1", "cust_1", PHONE, "Jane");
    }

    @Test
    @DisplayName("phone has clicked only one of the three review channels → still enqueues a real ask")
    void onlyOneReviewChannelCoveredStillEnqueues() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.FEEDBACK_FORM_TARGET)).thenReturn(false);

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        var captor = org.mockito.ArgumentCaptor.forClass(SmsReplyFlow.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_SEND);
    }

    @Test
    @DisplayName("2026-08-17: BOOKING-linked order (customer had an appointment, paid in-salon) still enqueues a flow — "
            + "this used to be silently skipped, discarding ~1/3 of real checkouts, see this class's own doc comment")
    void bookingLinkedInSalonOrderStillEnqueuesFlow() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1"))
                .thenReturn(Optional.of(order("cust_1", List.of(new SquareClient.Fulfillment("BOOKING", "COMPLETED")))));
        when(square.customerPhone("cust_1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust_1"))).thenReturn(Map.of("cust_1", "Jane"));

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        var captor = org.mockito.ArgumentCaptor.forClass(SmsReplyFlow.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_SEND);
    }

    @Test
    @DisplayName("no phone on file for the customer → no flow row created, silent skip")
    void noPhoneOnFileSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order("cust_1", null)));
        when(square.customerPhone("cust_1")).thenReturn(null);

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("no customer on the order at all → no flow row created")
    void noCustomerOnOrderSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.of(order(null, null)));

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", null));

        verify(repository, never()).save(any());
        verify(square, never()).customerPhone(any());
    }

    @Test
    @DisplayName("payment not COMPLETED → ignored")
    void nonCompletedPaymentIgnored() {
        service.handlePaymentUpdated(BUSINESS_ID, payment("PENDING", "order_1", "cust_1"));

        verifyNoInteractions(square, repository);
    }

    @Test
    @DisplayName("duplicate Square redelivery of the same payment id → idempotent, no second flow row")
    void duplicateEventIsIdempotent() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(true);

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
        verifyNoInteractions(square);
    }

    @Test
    @DisplayName("order lookup fails (not found) → no flow row, no exception")
    void orderNotFoundSkipped() {
        when(repository.existsBySquarePaymentId("pay_1")).thenReturn(false);
        when(square.orderById("order_1")).thenReturn(Optional.empty());

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("unexpected exception anywhere in the flow → swallowed, never thrown back to the controller")
    void exceptionNeverPropagates() {
        when(repository.existsBySquarePaymentId("pay_1")).thenThrow(new RuntimeException("boom"));

        service.handlePaymentUpdated(BUSINESS_ID, payment("COMPLETED", "order_1", "cust_1"));
        // no assertion needed beyond "didn't throw" — the @Test method completing is the assertion
    }
}
