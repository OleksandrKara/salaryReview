package com.salonreview.square;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a cash-payment declaration in an appointment note.
 *
 * <p>When a client pays cash there is no Square checkout, so by agreement the provider leaves a note
 * on the booking. The convention varies by provider:
 * <ul>
 *   <li>some write {@code cashew $138} (or {@code cashew 193}) — an explicit amount;</li>
 *   <li>others write the Russian {@code наличные} ("cash") with no amount — in which case the cash
 *       value is taken from the appointment's service total.</li>
 * </ul>
 *
 * <p>A note that matches no cash keyword returns empty and isn't counted — which is the incentive for
 * providers to write it the agreed way.
 */
@Component
public class CashNoteParser {

    // Russian "наличные / наличка / наличными …" share the stem "налич"; also accept English cash words.
    private static final Pattern CASH_KEYWORD =
            Pattern.compile("налич|cashew|\\bcash\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // An explicit amount, written either "$138" / "138$" / "138" near a cashew note.
    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern NALICH =
            Pattern.compile("налич", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Whether the note declares a cash payment, and the amount if the provider wrote one. An empty
     * {@code amount} means "use the appointment's service total".
     */
    public Optional<CashDeclaration> parse(String note) {
        if (note == null || note.isBlank()) return Optional.empty();
        if (!CASH_KEYWORD.matcher(note).find()) return Optional.empty();

        // The "наличные" style never carries a number — always fall back to the service total.
        if (NALICH.matcher(note).find()) return Optional.of(new CashDeclaration(Optional.empty()));

        // The "cashew" style usually carries the amount.
        Matcher m = AMOUNT.matcher(note);
        if (m.find()) return Optional.of(new CashDeclaration(Optional.of(new BigDecimal(m.group(1)))));
        return Optional.of(new CashDeclaration(Optional.empty()));
    }

    /**
     * A detected cash payment. {@code amount} is present only when the provider wrote a number;
     * otherwise the caller uses the appointment's service total.
     */
    public record CashDeclaration(Optional<BigDecimal> amount) {}
}
