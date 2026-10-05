package com.salonreview.sms;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a spelled-out rating in a checkout-review reply into digits, so "Five", "five stars" or
 * "ten out of ten" are read the same as "5" / "10" (2026-10-05 live case: a PMU client replied
 * "Five" and got no review link, because only digits were understood).
 *
 * <p>Only where the word clearly is the rating: the whole reply is short (up to 4 words, "Five",
 * "A solid five!") or the word is followed by "star(s)" / "out of". A number word inside a longer
 * sentence ("we waited five minutes for her") is left alone, so it can't turn a complaint into a
 * 5-star rating. "one" is stricter still, since "best one yet" or "no one" are not ratings: it
 * counts only when followed by "star(s)" / "out of", or when the reply is nothing but rating words.
 */
final class CheckoutReviewNumberWords {

    private static final Map<String, String> DIGITS = Map.of(
            "one", "1", "two", "2", "three", "3", "four", "4", "five", "5",
            "six", "6", "seven", "7", "eight", "8", "nine", "9", "ten", "10");

    private static final Pattern NUMBER_WORD = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten)\\b(?=(\\s*(stars?\\b|out\\s+of\\b|/))?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    private static final Set<String> RATING_ONLY_WORDS = Set.of("star", "stars", "out", "of");

    private static final int SHORT_REPLY_MAX_WORDS = 4;

    private CheckoutReviewNumberWords() {
    }

    static String toDigits(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        int words = 0;
        boolean ratingOnly = true;
        Matcher w = WORD.matcher(body);
        while (w.find()) {
            words++;
            String word = w.group().toLowerCase(Locale.US);
            if (!DIGITS.containsKey(word) && !RATING_ONLY_WORDS.contains(word)) {
                ratingOnly = false;
            }
        }
        boolean shortReply = words <= SHORT_REPLY_MAX_WORDS;

        Matcher m = NUMBER_WORD.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String word = m.group(1).toLowerCase(Locale.US);
            boolean ratingContext = m.group(2) != null && !m.group(2).isEmpty();
            boolean isRating = word.equals("one") ? ratingContext || ratingOnly : ratingContext || shortReply;
            m.appendReplacement(out, Matcher.quoteReplacement(isRating ? DIGITS.get(word) : m.group(1)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
