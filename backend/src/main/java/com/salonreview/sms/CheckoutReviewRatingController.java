package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.regex.Pattern;

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
 *
 * <p><b>{@link #rate} is a stateless interstitial, {@link #confirm} does the real work.</b> Found
 * live 2026-09-09: EVERY rating ever recorded (6 for 6, going back to launch) landed 2-5 seconds
 * after its email was sent — including one customer's real email-open (Mailchimp's own open-pixel
 * timestamp) arriving 45 minutes AFTER her "rating" had already been recorded and the flow marked
 * COMPLETED, and two other customers each getting two different ratings ~15ms apart for the same
 * flow (the exact race {@code completeIfNotAlready} already guards the DB write against — this is
 * the same underlying cause, just not the same symptom). No human reads an email and picks a
 * rating within low single-digit seconds of receiving it, let alone 45 minutes before opening it
 * at all — corporate email gateways and security scanners (Microsoft Defender for Office 365 Safe
 * Links, Barracuda, Mimecast, etc.) fetch every link in an inbound email within seconds of
 * delivery to check it for malware, and the direct-GET-does-the-write design here had no way to
 * tell that apart from a real click. The one thing those scanners essentially never do is execute
 * JavaScript — they're link-safety crawlers, not full browser engines — so {@link #rate} (the
 * literal link the email carries) now does nothing but verify the signature/expiry look
 * well-formed enough to be worth rendering a redirect for for and return a tiny HTML page whose
 * inline script immediately navigates to {@link #confirm} with the same four params; a scanner
 * fetching {@link #rate} gets back inert HTML and never reaches {@link #confirm} at all, while a
 * real browser's near-instant script-driven navigation is indistinguishable from the old direct
 * redirect to an actual customer (still "redirect straight through," per the owner's original
 * 2026-09-05 direction — just via one extra sub-second hop instead of zero).
 */
@RestController
public class CheckoutReviewRatingController {

    /** Matches exactly what {@link CheckoutReviewRatingSigner#sign} can ever produce
     * (unpadded URL-safe base64) — validated before this untrusted query param is embedded in the
     * interstitial's HTML/JS in {@link #rate}, so a malformed/malicious {@code sig} is rejected
     * outright rather than ever reaching string interpolation. {@link #confirm} re-derives and
     * compares it properly regardless; this is only about what's safe to echo back into markup. */
    private static final Pattern SAFE_SIGNATURE = Pattern.compile("^[A-Za-z0-9_-]+$");

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

    /** The actual link the email carries. Deliberately does NOT touch the database at all — see
     * this class's own doc for why: an automated scanner fetching this must never be able to
     * record a rating, and the only reliable way to tell it apart from a real customer's browser
     * is that the scanner won't run the script below. */
    @GetMapping(value = "/api/public/checkout-review/rate", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> rate(@RequestParam("flow") long flowId, @RequestParam("rating") int rating,
                                        @RequestParam("exp") long expEpochSeconds, @RequestParam("sig") String signature) {
        if (rating < 1 || rating > 5 || !SAFE_SIGNATURE.matcher(signature).matches()) {
            return ResponseEntity.notFound().build();
        }
        String confirmUrl = UriComponentsBuilder.fromPath("/api/public/checkout-review/confirm")
                .queryParam("flow", flowId).queryParam("rating", rating)
                .queryParam("exp", expEpochSeconds).queryParam("sig", signature)
                .build().toUriString();
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>One moment…</title></head><body>"
                + "<script>window.location.replace(" + toJsStringLiteral(confirmUrl) + ");</script>"
                + "<noscript><a href=\"" + confirmUrl + "\">Continue</a></noscript>"
                + "</body></html>";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }

    /** {@code confirmUrl} is built server-side from an already-validated signature (see {@link
     * #SAFE_SIGNATURE}) plus numeric params Spring itself already typed as {@code long}/{@code
     * int} — nothing attacker-controlled reaches this beyond that restricted charset, but escaping
     * it properly for a JS string literal anyway (not just trusting "it can't contain a quote")
     * costs nothing and isn't a place to cut a corner. */
    private static String toJsStringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003C") + "\"";
    }

    /** Does what {@link #rate} used to do directly: verify, record, redirect. Only ever reached
     * via {@link #rate}'s own script-driven navigation (or its {@code <noscript>} fallback link, a
     * real click either way) — never the literal link text in the email. */
    @Transactional
    @GetMapping("/api/public/checkout-review/confirm")
    public ResponseEntity<Void> confirm(@RequestParam("flow") long flowId, @RequestParam("rating") int rating,
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
        //
        // Atomic claim, not a read-then-write check on flow.getState() — found live 2026-09-08:
        // email link-prescanning security scanners fetch all 5 rating links within milliseconds of
        // each other, and a plain check let multiple concurrent requests all see "not yet
        // completed" before any of them persisted, recording several different ratings for one
        // flow (2 real customers hit this, 3 ratings logged each). completeIfNotAlready is a
        // single atomic UPDATE — only the request that actually flips the row wins.
        if (replyFlowRepository.completeIfNotAlready(flow.getId()) == 1) {
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
     * too). Only ever called after {@link SmsReplyFlowRepository#completeIfNotAlready} has already
     * atomically flipped the flow to {@code COMPLETED} — this no longer needs to (and must not
     * redundantly) set that state itself. */
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
    }

    private ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
