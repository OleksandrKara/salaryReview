package com.salonreview.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutReviewRatingParserTest {

    @Test
    @DisplayName("bare digit 1-5 parses directly")
    void bareDigitParses() {
        assertThat(CheckoutReviewRatingParser.parse("5")).contains(5);
        assertThat(CheckoutReviewRatingParser.parse("1")).contains(1);
    }

    @Test
    @DisplayName("digit with surrounding text still parses")
    void digitWithTextParses() {
        assertThat(CheckoutReviewRatingParser.parse("5 stars! love it")).contains(5);
        assertThat(CheckoutReviewRatingParser.parse("2, not happy with the service")).contains(2);
    }

    @Test
    @DisplayName("no digit at all → empty, not zero")
    void noDigitIsEmpty() {
        assertThat(CheckoutReviewRatingParser.parse("honestly not great")).isEmpty();
        assertThat(CheckoutReviewRatingParser.parse("")).isEmpty();
        assertThat(CheckoutReviewRatingParser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("a digit that's part of a longer number doesn't count — word boundary only")
    void digitInsideLongerNumberDoesNotCount() {
        assertThat(CheckoutReviewRatingParser.parse("call me at 555-1234")).isEmpty();
        assertThat(CheckoutReviewRatingParser.parse("$50 too expensive")).isEmpty();
    }

    @Test
    @DisplayName("out-of-range digits (0, 6-9) never parse")
    void outOfRangeDigitsDoNotParse() {
        assertThat(CheckoutReviewRatingParser.parse("0")).isEmpty();
        assertThat(CheckoutReviewRatingParser.parse("9 out of 10")).isEmpty();
    }

    @Test
    @DisplayName("first standalone match wins when more than one digit is present")
    void firstMatchWins() {
        Optional<Integer> result = CheckoutReviewRatingParser.parse("was a 2 out of 5 honestly");
        assertThat(result).contains(2);
    }

    @Test
    @DisplayName("spelled-out ratings parse like digits (2026-10-05: a client replied Five)")
    void spelledOutRatingParses() {
        assertThat(CheckoutReviewRatingParser.parse("Five")).contains(5);
        assertThat(CheckoutReviewRatingParser.parse("five stars!")).contains(5);
        assertThat(CheckoutReviewRatingParser.parse("A solid four")).contains(4);
        assertThat(CheckoutReviewRatingParser.parse("Two stars, she was late")).contains(2);
        assertThat(CheckoutReviewRatingParser.parse("One")).contains(1);
    }

    @Test
    @DisplayName("number words that aren't the rating stay unparsed")
    void numberWordsInSentencesIgnored() {
        assertThat(CheckoutReviewRatingParser.parse("Best one yet, thank you")).isEmpty();
        assertThat(CheckoutReviewRatingParser.parse("We waited five minutes but it was ok")).isEmpty();
        assertThat(CheckoutReviewRatingParser.parse("no one")).isEmpty();
    }
}
