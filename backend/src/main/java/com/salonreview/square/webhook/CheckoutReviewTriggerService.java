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
import com.salonreview.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns a qualifying Square {@code payment.updated} event into a pending
 * {@code checkout_review_request} flow — see openspec/changes/sms-automations-hub design.md
 * D1/D2. Never throws back to the webhook controller: any failure here is logged and the event is
 * simply not actioned, matching this codebase's "never block, never throw" notifier convention.
 */
@Service
public class CheckoutReviewTriggerService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReviewTriggerService.class);
    static final String AUTOMATION_KEY = "checkout_review_request";
    static final Duration SEND_DELAY = Duration.ofMinutes(2);

    private final SquareClientProvider squareClientProvider;
    private final SmsReplyFlowRepository repository;
    private final SameDayRebookingTriggerService rebookingTrigger;
    private final SmsMessageLogService messageLogService;
    private final BusinessRepository businessRepository;

    public CheckoutReviewTriggerService(SquareClientProvider squareClientProvider,
                                         SmsReplyFlowRepository repository,
                                         SameDayRebookingTriggerService rebookingTrigger,
                                         SmsMessageLogService messageLogService,
                                         BusinessRepository businessRepository) {
        this.squareClientProvider = squareClientProvider;
        this.repository = repository;
        this.rebookingTrigger = rebookingTrigger;
        this.messageLogService = messageLogService;
        this.businessRepository = businessRepository;
    }

    /** {@code businessId} is resolved by the caller ({@link
     * com.salonreview.square.webhook.SquareWebhookController} — Business A's legacy route via
     * {@code legacySmsBusiness()}, every other business's real per-business route via its own
     * webhook signature key, Phase 3.6) — this service itself has no opinion on how that
     * resolution happened, it just acts on whichever business the caller already verified. */
    public void handlePaymentUpdated(Long businessId, SquareWebhookEvent.Payment payment) {
        try {
            if (payment == null || !"COMPLETED".equals(payment.status()) || payment.orderId() == null) {
                return;
            }
            if (repository.existsBySquarePaymentId(payment.id())) {
                return; // Square redelivered an event we already enqueued a flow for
            }

            SquareClient square = squareClientProvider.forBusiness(businessId);
            Optional<SquareClient.Order> order = square.orderById(payment.orderId());
            if (order.isEmpty()) {
                log.warn("Checkout-review trigger: order {} not found for payment {}", payment.orderId(), payment.id());
                return;
            }
            // 2026-08-17 live incident: design.md D2 originally excluded any order with a
            // fulfillments[].type == "BOOKING" here, on the assumption that meant "an online
            // prepaid booking, not an in-salon checkout" (see the removed SquareClient
            // .isBookingLinked). Checked against 99 real completed orders from this account's own
            // Square history: 27 of them were BOOKING-linked AND source.name == "Point of Sale" —
            // i.e. an ordinary in-salon checkout for a customer who simply had an appointment on
            // the books, which is true of nearly every visit here. Zero orders in that sample had
            // any source value resembling an actual online-prepay flow. The filter was silently
            // discarding roughly a third of all real in-salon checkouts since this automation
            // launched. Removed rather than replaced with a source.name-based filter, since there's
            // no real example in this account's data of what actually needs excluding — see this
            // commit's message for the full investigation.

            String customerId = order.get().customerId();
            if (customerId == null) {
                return; // no customer on the order at all — nothing to text
            }
            // Normalized to E.164 here — Square's own Customer.phoneNumber() returns whatever
            // format was on file (often not E.164), and Twilio's inbound webhook always sends
            // E.164. Without normalizing at this one root-cause write point, the same customer's
            // texts silently split into two "different" threads on the Messages page — see
            // PhoneNumbers' own doc comment.
            String phoneNumber = PhoneNumbers.normalize(square.customerPhone(customerId));
            if (phoneNumber == null) {
                return; // genuinely anonymous walk-in with no phone on file — silent skip, see D2
            }
            // Given name only, deliberately — this feeds "Hi {name}," greetings on both automations
            // triggered below, and a greeting should never read "Hi Jane Smith," (see
            // com.salonreview.square.SquareClient#customerGivenNames' own doc comment).
            String customerName = square.customerGivenNames(List.of(customerId)).get(customerId);

            // Once this phone number has clicked through *all three* reply-branch link types at
            // least once each — a Google review, a Yelp review, and the private feedback form —
            // they've given us everything this automation is for. Repeatedly asking a customer
            // who's already covered all three reads as spammy rather than as care, so stop asking
            // permanently once every flag is set (mirrors SameDayRebookingScheduler's own
            // permanent-exclusion-on-prior-behavior pattern for negative feedback). Doesn't affect
            // the independent same-day-rebooking trigger below — that's a different automation.
            //
            // The row is still saved either way (just straight to COMPLETED, which
            // SmsReplyFlowScheduler never picks up for sending — see its own
            // findByStateAndSendDueAtBefore(AWAITING_SEND, ...) query) rather than skipped
            // entirely, so the existsBySquarePaymentId redelivery guard above keeps working for
            // this payment on a Square retry, and there's still a visible audit row for why no ask
            // went out.
            // A business whose Google-review/Yelp-review/feedback-form URLs haven't all been set
            // yet gets no checkout_review_request flow at all, rather than one whose reply
            // eventually resolves to a dead short link — see CheckoutReviewLinks and
            // Business#getGoogleReviewUrl.
            Business business = businessRepository.findById(businessId).orElse(null);
            boolean reviewLinksConfigured = business != null
                    && isSet(business.getGoogleReviewUrl()) && isSet(business.getYelpReviewUrl())
                    && isSet(business.getFeedbackFormUrl());
            if (!reviewLinksConfigured) {
                log.info("Checkout-review flow skipped for business {} — Google review/Yelp review/feedback form URL not configured yet", businessId);
            } else {
                boolean hasCoveredAllReviewChannels =
                        messageLogService.hasClickedLinkTarget(businessId, phoneNumber, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)
                                && messageLogService.hasClickedLinkTarget(businessId, phoneNumber, CheckoutReviewLinks.YELP_REVIEW_TARGET)
                                && messageLogService.hasClickedLinkTarget(businessId, phoneNumber, CheckoutReviewLinks.FEEDBACK_FORM_TARGET);
                repository.save(SmsReplyFlow.builder()
                        .businessId(businessId)
                        .automationKey(AUTOMATION_KEY)
                        .phoneNumber(phoneNumber)
                        .customerName(customerName)
                        .state(hasCoveredAllReviewChannels ? SmsReplyFlow.STATE_COMPLETED : SmsReplyFlow.STATE_AWAITING_SEND)
                        .squarePaymentId(payment.id())
                        .squareCustomerId(customerId)
                        .sendDueAt(Instant.now().plus(SEND_DELAY))
                        .build());
            }

            // A second, independent send off the same qualifying event — see
            // openspec/changes/same-day-rebooking-discount design.md D1. Reuses the values
            // already resolved above rather than re-hitting Square.
            rebookingTrigger.enqueue(businessId, payment.id(), customerId, phoneNumber, customerName);
        } catch (Exception e) {
            log.warn("Checkout-review trigger failed for payment {} (event ignored): {}",
                    payment == null ? null : payment.id(), e.getMessage());
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
