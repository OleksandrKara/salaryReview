package com.salonreview.square;

import com.salonreview.square.CashNoteParser.CashDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CashNoteParserTest {

    private final CashNoteParser parser = new CashNoteParser();

    @Test
    @DisplayName("'cashew' style carries an explicit amount")
    void cashewWithAmount() {
        assertThat(amount("cashew $80")).isEqualByComparingTo("80");
        assertThat(amount("Cashew $80.50")).isEqualByComparingTo("80.50");
        assertThat(amount("Cashew 193")).isEqualByComparingTo("193");      // no $ still works
        assertThat(amount("client paid cashew $120, lovely")).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("Russian 'наличные' declares cash with no amount → use service total")
    void nalichnyeNoAmount() {
        Optional<CashDeclaration> d = parser.parse("наличные");
        assertThat(d).isPresent();
        assertThat(d.get().amount()).isEmpty();

        // Even capitalized / embedded in a sentence
        assertThat(parser.parse("Наличные до конца").orElseThrow().amount()).isEmpty();
    }

    @Test
    @DisplayName("Malformed or absent note is not a cash declaration")
    void unparseable() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("running late")).isEmpty();
        assertThat(parser.parse("client allergic to acetone")).isEmpty();
    }

    private BigDecimal amount(String note) {
        return parser.parse(note).orElseThrow().amount().orElseThrow();
    }
}
