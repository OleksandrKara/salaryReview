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
    @DisplayName("bare 'cash' style carries the amount immediately before it")
    void bareCashWithAmount() {
        assertThat(amount("Paid $250 cash")).isEqualByComparingTo("250");
        assertThat(amount("Paid $350 cash ")).isEqualByComparingTo("350");
    }

    @Test
    @DisplayName("an unrelated number elsewhere in the note (an invoice number) is ignored — only the "
            + "figure actually next to the cash keyword counts")
    void ignoresUnrelatedNumberFarFromKeyword() {
        // Found live 2026-08-28: the old "first number anywhere in the note" search read the invoice
        // number as the cash amount instead of the real $350 later in the text.
        assertThat(amount("Invoice: 001365 ($100) paid \nPaid $350 cash ")).isEqualByComparingTo("350");
        assertThat(amount("invoice 001748 $100 paid\nPaid $250 cash ")).isEqualByComparingTo("250");
        assertThat(amount("invoice 001766 100$ paid\nPaid $400 cash")).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("multiple cash declarations in one note are summed")
    void sumsMultipleCashMentions() {
        assertThat(amount("Paid deposit $50 cash \npre and after for lips and eyeliner sent 14/08\n"
                + "Paid $200 cash ")).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("a tentative/contingent mention with no dollar figure nearby falls back to the "
            + "service total, same as no amount written at all")
    void tentativeMentionWithNoNearbyAmountFallsBackToServiceTotal() {
        // "Might bring cash" has no number anywhere near the keyword — the "july 4" and "invoice
        // 001672" numbers elsewhere in the note must not be picked up as if they were the amount.
        Optional<CashDeclaration> d = parser.parse(
                "Might bring cash \njuly 4 discount \ninvoice 001672 100$ paid\nPaid $530 card \nTip $25");
        assertThat(d).isPresent();
        assertThat(d.get().amount()).isEmpty();
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
