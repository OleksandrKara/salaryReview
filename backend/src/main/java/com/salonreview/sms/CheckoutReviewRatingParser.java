package com.salonreview.sms;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extracts the 1-5 star rating a customer's reply to a {@code checkout_review_request} rating
 * request actually contains — for persisting on {@link com.salonreview.domain.SmsMessage#getRating()}
 * (see V120) so the {@code /owner/reviews} dashboard can show a real number per review instead of
 * just the send/no-send branch decision. Deliberately separate from {@link
 * TwilioInboundSmsController}'s own branch-reply/negative-feedback decision (its own
 * {@code highestStandaloneNumber} helper): this one only ever reports a value actually in the
 * requested 1-5 range, since {@code rating} is a display field, not a threshold check — a "10"
 * reply is a real 5-star-or-better outcome for that decision, but not a literal "1-5 rating" worth
 * showing on the dashboard, so it stays unrated here (empty) rather than misreported as either
 * end of the scale.
 */
final class CheckoutReviewRatingParser {

    /** A standalone digit 1-5, not part of a longer number ("$50" or a phone-number fragment
     * shouldn't parse as a 5-star rating) — word boundary on both sides. */
    private static final Pattern RATING = Pattern.compile("\\b([1-5])\\b");

    private CheckoutReviewRatingParser() {
    }

    /** {@code empty} for a reply with no standalone 1-5 digit — still a real review (the text
     * itself is stored regardless), just not one with a parseable star rating. The first match
     * wins if a reply somehow contains more than one. */
    static Optional<Integer> parse(String body) {
        if (body == null) {
            return Optional.empty();
        }
        var matcher = RATING.matcher(body);
        return matcher.find() ? Optional.of(Integer.valueOf(matcher.group(1))) : Optional.empty();
    }
}
