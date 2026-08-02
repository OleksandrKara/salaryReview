package com.salonreview.square;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Parses a bank statement CSV into transactions (openspec design.md §14, D7). MVP targets one
 * configurable column mapping for the single business account in use — a small constant, not a UI
 * — with a Debit/Credit-column fallback for the common bank-export variant. A header row is
 * required; an unrecognized header set fails loudly rather than silently misreading data.
 */
@Component
public class CsvStatementParser {

    private static final String DATE_COLUMN = "Date";
    private static final String DESCRIPTION_COLUMN = "Description";
    private static final String AMOUNT_COLUMN = "Amount";
    private static final String DEBIT_COLUMN = "Debit";
    private static final String CREDIT_COLUMN = "Credit";

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"));

    private final MerchantNormalizer normalizer;

    public CsvStatementParser(MerchantNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public record ParsedTransaction(LocalDate date, String rawDescription, BigDecimal amount,
                                     String normalizedMerchant, String merchantKey,
                                     String fingerprint, int occurrenceIndex) {}

    public List<ParsedTransaction> parse(byte[] csvBytes) {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            boolean hasAmount = headerMap.containsKey(AMOUNT_COLUMN);
            boolean hasDebitCredit = headerMap.containsKey(DEBIT_COLUMN) && headerMap.containsKey(CREDIT_COLUMN);
            if (!headerMap.containsKey(DATE_COLUMN) || !headerMap.containsKey(DESCRIPTION_COLUMN) || !(hasAmount || hasDebitCredit)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Couldn't find expected columns: need '" + DATE_COLUMN + "', '" + DESCRIPTION_COLUMN +
                        "', and either '" + AMOUNT_COLUMN + "' or both '" + DEBIT_COLUMN + "'/'" + CREDIT_COLUMN + "'");
            }

            List<ParsedTransaction> result = new ArrayList<>();
            Map<String, Integer> occurrenceCounts = new HashMap<>();
            for (CSVRecord record : parser) {
                LocalDate date = parseDate(record.get(DATE_COLUMN));
                String rawDescription = record.get(DESCRIPTION_COLUMN).trim();
                BigDecimal amount = hasAmount ? parseAmount(record.get(AMOUNT_COLUMN)) : debitCreditAmount(record);

                MerchantNormalizer.Normalized normalized = normalizer.normalize(rawDescription);
                String fp = fingerprint(date, amount, normalized.normalizedMerchant());
                int occurrenceIndex = occurrenceCounts.getOrDefault(fp, 0);
                occurrenceCounts.put(fp, occurrenceIndex + 1);

                result.add(new ParsedTransaction(date, rawDescription, amount,
                        normalized.normalizedMerchant(), normalized.merchantKey(), fp, occurrenceIndex));
            }
            return result;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV file", e);
        }
    }

    private static LocalDate parseDate(String raw) {
        String trimmed = raw.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unrecognized date: " + raw);
    }

    private static BigDecimal parseAmount(String raw) {
        String cleaned = raw.trim().replace("$", "").replace(",", "");
        boolean parenNegative = cleaned.startsWith("(") && cleaned.endsWith(")");
        if (parenNegative) cleaned = "-" + cleaned.substring(1, cleaned.length() - 1);
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unrecognized amount: " + raw);
        }
    }

    /** Debit/Credit fallback: a populated Debit cell is money out (negative), a populated Credit
     * cell is money in (positive) — the same signed convention as a single Amount column. */
    private static BigDecimal debitCreditAmount(CSVRecord record) {
        String debit = record.isMapped(DEBIT_COLUMN) ? record.get(DEBIT_COLUMN).trim() : "";
        String credit = record.isMapped(CREDIT_COLUMN) ? record.get(CREDIT_COLUMN).trim() : "";
        if (!debit.isEmpty()) return parseAmount(debit).abs().negate();
        if (!credit.isEmpty()) return parseAmount(credit).abs();
        return BigDecimal.ZERO;
    }

    private static String fingerprint(LocalDate date, BigDecimal amount, String normalizedMerchant) {
        String input = date + "|" + amount.stripTrailingZeros().toPlainString() + "|" + normalizedMerchant;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
