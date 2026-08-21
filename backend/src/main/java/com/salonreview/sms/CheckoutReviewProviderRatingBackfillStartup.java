package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.SquareConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * One-time (per row) startup backfill for V120's new {@code sms_reply_flow.provider_id} /
 * {@code sms_message.reply_flow_id}/{@code rating} columns, so every {@code
 * checkout_review_request} reply that arrived before this feature existed still shows up on the
 * {@code /owner/reviews} dashboard — not just ones sent after this deployed. Same
 * idempotent-startup-backfill pattern as {@link com.salonreview.square.ProviderVisitStartup} /
 * {@link com.salonreview.square.RevenueSnapshotStartup}: runs on a background daemon thread so it
 * never delays {@link ApplicationReadyEvent}, and every later restart just re-queries for
 * still-{@code null} rows (near-instant once the real backfill has already run once).
 *
 * <p>Two independent passes, since a flow and its reply are different tables with no guaranteed
 * 1:1 (a flow with no reply at all needs no message pass, and matching a reply back to its flow
 * doesn't need a fresh Square call): (1) resolve {@code provider_id} for old flows via the same
 * {@link TechnicianNameResolver} lookup used live, using the flow's own {@code created_at} as
 * {@code asOf} (it's created within {@link
 * com.salonreview.square.webhook.CheckoutReviewTriggerService#SEND_DELAY} of the real checkout, so
 * this reproduces what the live lookup would have seen); (2) link each old, unlinked reply to the
 * newest flow that already existed when it arrived and doesn't already have a reply linked (see
 * {@link #backfillMessageLinks}'s own "claim" doc), and parse its rating — pure DB matching, no
 * Square call needed.
 */
@Component
public class CheckoutReviewProviderRatingBackfillStartup {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReviewProviderRatingBackfillStartup.class);
    static final String AUTOMATION_KEY = "checkout_review_request";

    private final SmsReplyFlowRepository flows;
    private final SmsMessageRepository messages;
    private final TechnicianNameResolver technicianNameResolver;
    private final SquareConnectionRepository connections;

    public CheckoutReviewProviderRatingBackfillStartup(SmsReplyFlowRepository flows, SmsMessageRepository messages,
                                                         TechnicianNameResolver technicianNameResolver,
                                                         SquareConnectionRepository connections) {
        this.flows = flows;
        this.messages = messages;
        this.technicianNameResolver = technicianNameResolver;
        this.connections = connections;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread t = new Thread(() -> {
            for (var connection : connections.findAll()) {
                Long businessId = connection.getBusinessId();
                try {
                    int flowsResolved = backfillFlowProviders(businessId);
                    int messagesLinked = backfillMessageLinks(businessId);
                    if (flowsResolved > 0 || messagesLinked > 0) {
                        log.info("Checkout-review provider/rating backfill for business {}: {} flow(s) resolved, "
                                + "{} reply/replies linked", businessId, flowsResolved, messagesLinked);
                    }
                } catch (RuntimeException e) {
                    log.warn("Checkout-review provider/rating backfill failed for business {} (retried at next "
                            + "restart): {}", businessId, e.toString());
                }
            }
        }, "checkout-review-provider-rating-backfill");
        t.setDaemon(true);
        t.start();
    }

    int backfillFlowProviders(Long businessId) {
        int resolved = 0;
        for (SmsReplyFlow flow : flows.findByBusinessIdAndAutomationKeyAndProviderIdIsNullAndSquareCustomerIdIsNotNull(
                businessId, AUTOMATION_KEY)) {
            Optional<Provider> provider = technicianNameResolver.resolveProviderForCustomer(
                    businessId, flow.getSquareCustomerId(), flow.getCreatedAt());
            if (provider.isPresent()) {
                flow.setProviderId(provider.get().getId());
                flows.save(flow);
                resolved++;
            }
            // Genuinely unresolvable (no matching booking found in the lookback window, e.g. the
            // visit predates this account's Square booking history) — provider_id stays null
            // forever for this row; see this class's own doc on why that's an acceptable
            // one-time-best-effort tradeoff rather than retried indefinitely.
        }
        return resolved;
    }

    /** "Claim" semantics: a flow gets linked from at most one message, ever. Without this, every
     * message in a negative-rating back-and-forth (the customer's actual reply, then any
     * follow-up explaining more) matched the same "newest flow that existed before it" and all
     * got linked to that one flow — inflating {@code checkout_review_request}'s reply-rate stat
     * past 100% (found live 2026-08-21: 857%, "60/7") and, on {@code /owner/reviews}, showing a
     * single visit's conversation as several separate reviews. Processes oldest-unlinked-first
     * (see the repository query's own doc) and tracks claimed flow ids both from rows already
     * linked (a prior backfill run, or live linking) and from this same pass, so a message whose
     * matching flow is already spoken for is left unlinked rather than double-claiming it. */
    int backfillMessageLinks(Long businessId) {
        int linked = 0;
        java.util.Set<Long> claimedFlowIds = new java.util.HashSet<>(
                messages.findDistinctReplyFlowIdsByBusinessIdAndAutomationKey(businessId, AUTOMATION_KEY));
        for (SmsMessage message : messages.findByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNullOrderByCreatedAtAsc(
                businessId, AUTOMATION_KEY, "INBOUND")) {
            Optional<SmsReplyFlow> flow = flows.findFirstByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtBeforeOrderByCreatedAtDesc(
                    businessId, message.getPhoneNumber(), AUTOMATION_KEY, message.getCreatedAt());
            if (flow.isEmpty() || claimedFlowIds.contains(flow.get().getId())) {
                continue; // no flow existed yet, or it already has a reply — this is a follow-up, not the answer
            }
            message.setReplyFlowId(flow.get().getId());
            CheckoutReviewRatingParser.parse(message.getBody()).ifPresent(message::setRating);
            messages.save(message);
            claimedFlowIds.add(flow.get().getId());
            linked++;
        }
        return linked;
    }
}
