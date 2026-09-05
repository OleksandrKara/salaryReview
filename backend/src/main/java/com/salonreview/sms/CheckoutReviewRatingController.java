package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

/**
 * Public click target for the checkout-review-request satisfaction email's five rating options
 * (Very Satisfied .. Very Dissatisfied) — see {@link CheckoutReviewEmailFallbackScheduler}, which
 * mints these links, and {@link CheckoutReviewRatingSigner}, which signs them.
 *
 * <p>Records the click as if it were the SMS reply it stands in for — same {@link
 * SmsMessage#getRating()}/{@link SmsMessage#getNegativeFeedbackAt()} storage a real text reply
 * gets (see {@link com.salonreview.sms.TwilioInboundSmsController}), so every downstream reader of
 * that (the {@code /owner/reviews} dashboard, the same-day-rebooking negative-feedback exclusion)
 * picks up an email-sourced rating with no changes of its own needed — then redirects straight to
 * the destination in one hop, no follow-up SMS/email asking the customer to click yet another
 * link (owner direction 2026-09-05: "redirect straight through", not a second round trip).
 *
 * <p>{@code permitAll()} in {@link com.salonreview.config.SecurityConfig} — nothing sensitive
 * here beyond what the signed link itself already gates.
 */
@RestController
public class CheckoutReviewRatingController {

    private final CheckoutReviewRatingSigner signer;
    private final SmsReplyFlowRepository replyFlowRepository;
    private final BusinessRepository businessRepository;
    private final SmsMessageLogService messageLogService;

    public CheckoutReviewRatingController(CheckoutReviewRatingSigner signer, SmsReplyFlowRepository replyFlowRepository,
                                           BusinessRepository businessRepository, SmsMessageLogService messageLogService) {
        this.signer = signer;
        this.replyFlowRepository = replyFlowRepository;
        this.businessRepository = businessRepository;
        this.messageLogService = messageLogService;
    }

    @GetMapping("/api/public/checkout-review/rate")
    public ResponseEntity<Void> rate(@RequestParam("flow") long flowId, @RequestParam("rating") int rating,
                                      @RequestParam("exp") long expEpochSeconds, @RequestParam("sig") String signature) {
        if (rating < 1 || rating > 5) {
            return notFound();
        }
        if (Instant.now().isAfter(Instant.ofEpochSecond(expEpochSeconds))) {
            return notFound();
        }
        if (!signer.verify(flowId, rating, expEpochSeconds, signature)) {
            return notFound();
        }
        SmsReplyFlow flow = replyFlowRepository.findById(flowId).orElse(null);
        if (flow == null || !CheckoutReviewReplyService.AUTOMATION_KEY.equals(flow.getAutomationKey())) {
            return notFound();
        }
        Business business = businessRepository.findById(flow.getBusinessId()).orElse(null);
        if (business == null) {
            return notFound();
        }

        // Resolved BEFORE recording this click, so a first-time 5-star click still sees its own
        // escalation rung correctly (Google, unless already clicked) rather than a state that
        // includes an effect this very request is about to cause.
        String linkTarget = resolveLinkTarget(rating, flow, business);
        String target = CheckoutReviewLinks.resolve(linkTarget, business);
        if (target == null) {
            return notFound();
        }

        // Idempotent: a repeat click (double-click, or the customer already replied by SMS in the
        // meantime) redirects to whatever the ladder currently resolves to, without re-logging or
        // overwriting the rating already on record — first response wins, same as the SMS side
        // (see TwilioInboundSmsController's own AWAITING_REPLY-only pending lookup).
        if (!SmsReplyFlow.STATE_COMPLETED.equals(flow.getState())) {
            recordRating(flow, rating, linkTarget);
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /** Only a 5-star click escalates through Google -&gt; Yelp -&gt; private feedback, mirroring
     * {@link CheckoutReviewReplyService#sendBranchReply}'s own ladder exactly; 1-4 always goes
     * straight to the private feedback form — the same "don't ask for a public review unless it's
     * a genuine 5" gate the SMS branch already enforces, just resolved as a direct redirect target
     * instead of a second message. */
    private String resolveLinkTarget(int rating, SmsReplyFlow flow, Business business) {
        if (rating < 5) {
            return CheckoutReviewLinks.FEEDBACK_FORM_TARGET;
        }
        boolean clickedGoogle = messageLogService.hasClickedLinkTarget(
                flow.getBusinessId(), flow.getPhoneNumber(), CheckoutReviewLinks.GOOGLE_REVIEW_TARGET);
        boolean clickedYelp = clickedGoogle && messageLogService.hasClickedLinkTarget(
                flow.getBusinessId(), flow.getPhoneNumber(), CheckoutReviewLinks.YELP_REVIEW_TARGET);
        return !clickedGoogle ? CheckoutReviewLinks.GOOGLE_REVIEW_TARGET
                : !clickedYelp ? CheckoutReviewLinks.YELP_REVIEW_TARGET
                : CheckoutReviewLinks.FEEDBACK_FORM_TARGET;
    }

    /** Status used on the synthetic inbound row {@link #recordRating} logs — deliberately NOT
     * {@code "RECEIVED"} (what a genuine text reply gets), so {@code SmsAutomationService}'s
     * SMS-side reply count for this automation can exclude it and stay genuinely SMS-only (the
     * owner explicitly wants the two channels' numbers kept distinct). */
    static final String STATUS_RATED_VIA_EMAIL = "RATED_VIA_EMAIL";

    /** Two rows, mirroring what an SMS reply would have produced: an INBOUND-shaped message
     * carrying the rating (so every existing reader of {@code sms_message.rating}/{@code
     * negative_feedback_at} picks this up exactly like a real text reply, no changes of its own
     * needed), and an OUTBOUND click-tracked row recording which destination this rating resolved
     * to (so a later ask sees the escalation ladder already advanced, and {@code
     * CheckoutReviewTriggerService}'s "covered all three channels" permanent-stop check sees it
     * too). */
    private void recordRating(SmsReplyFlow flow, int rating, String linkTarget) {
        SmsMessage inbound = messageLogService.logInbound(flow.getBusinessId(), flow.getPhoneNumber(),
                "[Rated " + rating + "/5 via email]", CheckoutReviewReplyService.AUTOMATION_KEY);
        inbound.setStatus(STATUS_RATED_VIA_EMAIL);
        inbound.setReplyFlowId(flow.getId());
        inbound.setRating(rating);
        if (rating <= 4) {
            inbound.setNegativeFeedbackAt(Instant.now());
        }
        messageLogService.save(inbound);

        SmsMessage outbound = messageLogService.logOutboundWithLink(flow.getBusinessId(), "checkout_review_email_rating",
                CheckoutReviewReplyService.AUTOMATION_KEY, flow.getPhoneNumber(), "", true, null, null,
                linkTarget, messageLogService.generateUniqueClickToken());
        outbound.setClickedAt(Instant.now());
        messageLogService.save(outbound);

        flow.setState(SmsReplyFlow.STATE_COMPLETED);
        replyFlowRepository.save(flow);
    }

    private ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
