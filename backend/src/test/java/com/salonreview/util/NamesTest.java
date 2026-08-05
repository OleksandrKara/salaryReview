package com.salonreview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamesTest {

    @Test
    @DisplayName("all-lowercase name is title-cased")
    void lowercaseIsCapitalized() {
        assertThat(Names.capitalizeFirst("oleksandr")).isEqualTo("Oleksandr");
    }

    @Test
    @DisplayName("all-caps name is normalized down, not left shouting")
    void allCapsIsNormalized() {
        assertThat(Names.capitalizeFirst("JANE")).isEqualTo("Jane");
    }

    @Test
    @DisplayName("already-correct casing is left alone")
    void alreadyCorrectIsUnchanged() {
        assertThat(Names.capitalizeFirst("Jane")).isEqualTo("Jane");
    }

    @Test
    @DisplayName("multi-word names get each word capitalized")
    void multiWordNameCapitalizesEachWord() {
        assertThat(Names.capitalizeFirst("mary jane")).isEqualTo("Mary Jane");
    }

    @Test
    @DisplayName("hyphenated and apostrophe names capitalize after the separator too")
    void hyphenAndApostropheCapitalizeNextLetter() {
        assertThat(Names.capitalizeFirst("o'brien")).isEqualTo("O'Brien");
        assertThat(Names.capitalizeFirst("anne-marie")).isEqualTo("Anne-Marie");
    }

    @Test
    @DisplayName("null and blank pass through unchanged")
    void nullAndBlankPassThrough() {
        assertThat(Names.capitalizeFirst(null)).isNull();
        assertThat(Names.capitalizeFirst("")).isEmpty();
        assertThat(Names.capitalizeFirst("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("leading/trailing whitespace is trimmed")
    void trimsWhitespace() {
        assertThat(Names.capitalizeFirst("  oleksandr  ")).isEqualTo("Oleksandr");
    }
}
