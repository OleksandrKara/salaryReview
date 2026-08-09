package com.salonreview.square;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a bank statement CSV into transactions (openspec design.md §14, D7). MVP targets one
 * configurable column mapping for the single business account in use — a small constant, not a UI
 * — with a Debit/Credit-column fallback for the common bank-export variant. Some exports (this
 * business's real bank) prefix the transaction table with an "Account Summary" section of its
 * own — a differently-shaped header followed by a few balance-total rows — so the real header row
 * is located by scanning for the expected columns rather than assumed to be line 1. An
 * unrecognized header set (nowhere in the file) fails loudly rather than silently misreading data.
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
        String repaired = repairUnescapedQuotes(new String(csvBytes, StandardCharsets.UTF_8));
        List<CSVRecord> records;
        try (CSVParser rawParser = CSVFormat.DEFAULT.builder().setAllowMissingColumnNames(true).build().parse(new StringReader(repaired))) {
            records = rawParser.getRecords();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV file", e);
        }

        int headerRowIndex = findHeaderRow(records);
        if (headerRowIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Couldn't find expected columns: need '" + DATE_COLUMN + "', '" + DESCRIPTION_COLUMN +
                    "', and either '" + AMOUNT_COLUMN + "' or both '" + DEBIT_COLUMN + "'/'" + CREDIT_COLUMN + "'");
        }

        Map<String, Integer> headerMap = new HashMap<>();
        CSVRecord headerRecord = records.get(headerRowIndex);
        for (int i = 0; i < headerRecord.size(); i++) {
            String name = headerRecord.get(i).trim();
            if (!name.isEmpty()) headerMap.put(name, i);
        }
        boolean hasAmount = headerMap.containsKey(AMOUNT_COLUMN);
        boolean hasDebitCredit = headerMap.containsKey(DEBIT_COLUMN) && headerMap.containsKey(CREDIT_COLUMN);

        List<ParsedTransaction> result = new ArrayList<>();
        Map<String, Integer> occurrenceCounts = new HashMap<>();
        for (int i = headerRowIndex + 1; i < records.size(); i++) {
            CSVRecord record = records.get(i);
            String dateRaw = field(record, headerMap, DATE_COLUMN);
            if (dateRaw.isBlank()) continue;

            String amountRaw = field(record, headerMap, AMOUNT_COLUMN);
            String debitRaw = field(record, headerMap, DEBIT_COLUMN);
            String creditRaw = field(record, headerMap, CREDIT_COLUMN);
            boolean noAmountData = amountRaw.isBlank() && debitRaw.isBlank() && creditRaw.isBlank();
            if (noAmountData) {
                // Opening/closing balance marker rows (e.g. "Beginning balance as of ...") carry a
                // Date and Description but no transaction amount — not a real transaction, skip it
                // rather than throw or import it as a bogus $0 expense.
                continue;
            }

            LocalDate date = parseDate(dateRaw);
            String rawDescription = field(record, headerMap, DESCRIPTION_COLUMN).trim();
            BigDecimal amount = hasAmount && !amountRaw.isBlank()
                    ? parseAmount(amountRaw)
                    : debitCreditAmount(debitRaw, creditRaw);

            MerchantNormalizer.Normalized normalized = normalizer.normalize(rawDescription);
            String fp = fingerprint(date, amount, normalized.normalizedMerchant());
            int occurrenceIndex = occurrenceCounts.getOrDefault(fp, 0);
            occurrenceCounts.put(fp, occurrenceIndex + 1);

            result.add(new ParsedTransaction(date, rawDescription, amount,
                    normalized.normalizedMerchant(), normalized.merchantKey(), fp, occurrenceIndex));
        }
        return result;
    }

    /** Some bank exports (this business's real bank) emit description fields with an unescaped
     * internal quote instead of the RFC 4180 doubled-quote escape, e.g.
     * {@code "Zelle payment to Jane for "Payment"; Conf# 123"} — which the strict default CSV
     * lexer rejects outright. Repairs each line by doubling any quote inside an otherwise-quoted
     * field that isn't actually closing it (i.e. not immediately followed by a delimiter or
     * end-of-line), so the field round-trips through commons-csv as the writer presumably intended. */
    private static String repairUnescapedQuotes(String csvText) {
        String[] lines = csvText.split("\r\n|\r|\n", -1);
        StringBuilder out = new StringBuilder();
        for (int li = 0; li < lines.length; li++) {
            if (li > 0) out.append('\n');
            out.append(repairLine(lines[li]));
        }
        return out.toString();
    }

    private static String repairLine(String line) {
        StringBuilder result = new StringBuilder();
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) == ',')) {
                result.append('"');
                i++;
                while (i < n) {
                    if (line.charAt(i) == '"') {
                        boolean alreadyDoubled = i + 1 < n && line.charAt(i + 1) == '"';
                        boolean closing = i + 1 == n || line.charAt(i + 1) == ',';
                        if (alreadyDoubled) {
                            result.append("\"\"");
                            i += 2;
                        } else if (closing) {
                            result.append('"');
                            i++;
                            break;
                        } else {
                            result.append("\"\"");
                            i++;
                        }
                    } else {
                        result.append(line.charAt(i));
                        i++;
                    }
                }
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    /** Scans for the transaction table's real header row — Date + Description + (Amount, or
     * Debit&Credit) — skipping any leading section (e.g. an "Account Summary" block) that doesn't
     * match. Returns -1 if no row in the file matches. */
    private static int findHeaderRow(List<CSVRecord> records) {
        for (int i = 0; i < records.size(); i++) {
            Set<String> cols = new HashSet<>();
            for (String value : records.get(i)) cols.add(value.trim());
            boolean hasDate = cols.contains(DATE_COLUMN);
            boolean hasDescription = cols.contains(DESCRIPTION_COLUMN);
            boolean hasAmount = cols.contains(AMOUNT_COLUMN);
            boolean hasDebitCredit = cols.contains(DEBIT_COLUMN) && cols.contains(CREDIT_COLUMN);
            if (hasDate && hasDescription && (hasAmount || hasDebitCredit)) {
                return i;
            }
        }
        return -1;
    }

    private static String field(CSVRecord record, Map<String, Integer> headerMap, String column) {
        Integer index = headerMap.get(column);
        if (index == null || index >= record.size()) return "";
        return record.get(index).trim();
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
    private static BigDecimal debitCreditAmount(String debit, String credit) {
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
