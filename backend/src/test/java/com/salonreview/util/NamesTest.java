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

    @Test
    @DisplayName("obvious placeholders are not greeting names")
    void placeholdersAreRejected() {
        for (String junk : new String[] {null, "", "  ", "Hubspot", "hubspot 16 10", "HubSpot Unknown", "No name Hubspot",
                "Hubspot 05/29", "Habspot 3.27", "Test", "TEST", "Unknown", "Guest", "Customer", "client", "N/A", "null",
                "Anonymous", "no name", "A", "-", "12345", "(858) 828-7544", "Mike BBB (chthompson8787)",
                "jane@example.com", "Beauty 6 ladies"}) {
            assertThat(Names.isPlaceholder(junk)).as("junk: %s", junk).isTrue();
            assertThat(Names.customerGreetingName(junk)).as("junk: %s", junk).isNull();
        }
    }

    @Test
    @DisplayName("real names, however unusual, short, foreign or upper-case, pass through")
    void realNamesAreKept() {
        for (String real : new String[] {"Jane", "JANE", "Li", "Al", "Ng", "Anna Kara", "Zoë", "José", "Анна", "Nguyễn",
                "O'Brien", "Mary-Jane", "Testa", "Tester", "Guestrin", "Clientelle", "Hubert", "Unknowles", "McDonald",
                "D'Angelo", "Bo", "Xi", "Customerio"}) {
            assertThat(Names.isPlaceholder(real)).as("real: %s", real).isFalse();
            assertThat(Names.customerGreetingName(real)).as("real: %s", real).isNotNull();
        }
        assertThat(Names.customerGreetingName("jane")).isEqualTo("Jane");
        assertThat(Names.customerGreetingName("ЖАННА")).isEqualTo("Жанна");
    }
}
