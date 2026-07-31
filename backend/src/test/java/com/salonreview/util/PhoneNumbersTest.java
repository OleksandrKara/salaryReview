package com.salonreview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @Test
    @DisplayName("normalize: 10-digit US number gets +1 prefix")
    void normalizeTenDigits() {
        assertThat(PhoneNumbers.normalize("(310) 779-6334")).isEqualTo("+13107796334");
    }

    @Test
    @DisplayName("normalize: 11-digit number already starting with 1 just gets a + prefix")
    void normalizeElevenDigitsStartingWithOne() {
        assertThat(PhoneNumbers.normalize("1-310-779-6334")).isEqualTo("+13107796334");
    }

    @Test
    @DisplayName("normalize: already-E.164 input passes through unchanged")
    void normalizeAlreadyE164() {
        assertThat(PhoneNumbers.normalize("+13107796334")).isEqualTo("+13107796334");
    }

    @Test
    @DisplayName("normalize: unrecognized shapes are left untouched rather than mangled")
    void normalizeUnrecognizedShape() {
        assertThat(PhoneNumbers.normalize("12345")).isEqualTo("12345");
    }

    @Test
    @DisplayName("normalize: null in, null out; blank in, blank out")
    void normalizeNullAndBlank() {
        assertThat(PhoneNumbers.normalize(null)).isNull();
        assertThat(PhoneNumbers.normalize("  ")).isEqualTo("");
    }

    @Test
    @DisplayName("last10Digits: strips formatting and country code, keeping just the last 10")
    void last10DigitsStripsFormatting() {
        assertThat(PhoneNumbers.last10Digits("+13107796334")).isEqualTo("3107796334");
        assertThat(PhoneNumbers.last10Digits("(310) 779-6334")).isEqualTo("3107796334");
    }

    @Test
    @DisplayName("last10Digits: fewer than 10 digits yields empty, never a false match")
    void last10DigitsTooShort() {
        assertThat(PhoneNumbers.last10Digits("12345")).isEqualTo("");
        assertThat(PhoneNumbers.last10Digits(null)).isEqualTo("");
    }
}
