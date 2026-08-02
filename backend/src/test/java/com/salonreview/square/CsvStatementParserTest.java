package com.salonreview.square;

import com.salonreview.repo.MerchantAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fingerprint + occurrence-index tests (openspec design.md D7, tasks.md 6.2). */
class CsvStatementParserTest {

    private CsvStatementParser parser;

    @BeforeEach
    void setUp() {
        MerchantAliasRepository aliases = mock(MerchantAliasRepository.class);
        when(aliases.findByRawPattern(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        parser = new CsvStatementParser(new MerchantNormalizer(aliases));
    }

    private static byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Identical inputs produce the same fingerprint")
    void identicalInputsMatch() {
        var rows = parser.parse(csv(
                "Date,Description,Amount",
                "2026-08-14,COSTCO WHSE #123,-84.12",
                "2026-08-14,COSTCO WHSE #123,-84.12"));

        assertThat(rows.get(0).fingerprint()).isEqualTo(rows.get(1).fingerprint());
    }

    @Test
    @DisplayName("Any differing input (date/amount/merchant) changes the fingerprint")
    void differingInputChangesFingerprint() {
        var rows = parser.parse(csv(
                "Date,Description,Amount",
                "2026-08-14,COSTCO WHSE #123,-84.12",
                "2026-08-15,COSTCO WHSE #123,-84.12",
                "2026-08-14,COSTCO WHSE #123,-90.00",
                "2026-08-14,TARGET,-84.12"));

        List<String> fingerprints = rows.stream().map(CsvStatementParser.ParsedTransaction::fingerprint).toList();
        assertThat(fingerprints).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Two genuinely identical charges the same day get distinct occurrence indices")
    void trueSameDayRepeatsGetDistinctOccurrenceIndex() {
        var rows = parser.parse(csv(
                "Date,Description,Amount",
                "2026-08-14,NETFLIX,-9.99",
                "2026-08-14,NETFLIX,-9.99"));

        assertThat(rows.get(0).occurrenceIndex()).isZero();
        assertThat(rows.get(1).occurrenceIndex()).isEqualTo(1);
        assertThat(rows.get(0).fingerprint()).isEqualTo(rows.get(1).fingerprint());
    }

    @Test
    @DisplayName("A byte-identical re-parse reproduces the same fingerprint+occurrence sequence")
    void byteIdenticalReparseRoundTrips() {
        byte[] file = csv(
                "Date,Description,Amount",
                "2026-08-14,NETFLIX,-9.99",
                "2026-08-14,NETFLIX,-9.99",
                "2026-08-15,COSTCO,-40.00");

        var first = parser.parse(file);
        var second = parser.parse(file);

        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).fingerprint()).isEqualTo(first.get(i).fingerprint());
            assertThat(second.get(i).occurrenceIndex()).isEqualTo(first.get(i).occurrenceIndex());
        }
    }

    @Test
    @DisplayName("A Debit/Credit column fallback is supported")
    void debitCreditFallback() {
        var rows = parser.parse(csv(
                "Date,Description,Debit,Credit",
                "2026-08-14,COSTCO,84.12,",
                "2026-08-15,REFUND,,20.00"));

        assertThat(rows.get(0).amount()).isEqualByComparingTo("-84.12");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("An unrecognized header set fails loudly, not silently")
    void unrecognizedHeaderFailsLoudly() {
        assertThatThrownBy(() -> parser.parse(csv("Foo,Bar,Baz", "1,2,3")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Date");
    }

    @Test
    @DisplayName("Dates parse correctly and merchant fields are populated")
    void parsesDateAndMerchant() {
        var rows = parser.parse(csv("Date,Description,Amount", "2026-08-14,SQ *AKLUXNAILS,-25.00"));

        assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(rows.get(0).normalizedMerchant()).isEqualTo("AKLUXNAILS");
    }
}
