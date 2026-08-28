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
 *   <li>some write {@code cashew $138} (or {@code cashew 193}) — an explicit amount right after the
 *       keyword;</li>
 *   <li>some write {@code Paid $138 cash} — the amount right before a bare "cash";</li>
 *   <li>others write the Russian {@code наличные} ("cash") with no amount at all — in which case the
 *       cash value is taken from the appointment's service total.</li>
 * </ul>
 *
 * <p>The amount search only looks in a short window immediately next to each keyword occurrence
 * (after "cashew", before a bare "cash") — never anywhere else in the note. Found live 2026-08-28: an
 * earlier version took the first number found <em>anywhere</em> in the whole note, which on a note
 * like {@code "Invoice: 001365 ($100) paid, Paid $350 cash"} read the invoice number as the cash
 * amount instead of the real $350 figure later in the text — a business with real, deliberate partial-
 * cash notes ({@code cashew $138} written correctly next to the keyword) still needs that number
 * honored, so the fix is narrowing the search window, not removing it. A note can declare cash more
 * than once (e.g. a deposit in cash, then the balance in cash later at the same visit) — every
 * keyword occurrence's own nearby amount is summed, not just the first.
 *
 * <p>A note that matches no cash keyword returns empty and isn't counted — which is the incentive for
 * providers to write it the agreed way.
 */
@Component
public class CashNoteParser {

    // Russian "наличные / наличка / наличными …" share the stem "налич"; also accept English cash words.
    private static final Pattern CASH_KEYWORD =
            Pattern.compile("налич|cashew|\\bcash\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NALICH =
            Pattern.compile("налич", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CASHEW = Pattern.compile("cashew", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_CASH = Pattern.compile("\\bcash\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)");

    // How far to look for the dollar figure that belongs to a keyword match. Short enough that an
    // unrelated number elsewhere in the note (an invoice number, a day-of-month) can't wander into
    // range; generous enough to cover real phrasing on either side ("cashew $138" — amount follows
    // within a couple characters; "Paid $250 cash" — amount precedes by up to a short phrase).
    private static final int FORWARD_WINDOW = 15;
    private static final int BACKWARD_WINDOW = 25;

    /**
     * Whether the note declares a cash payment, and the amount if the provider wrote one (summed
     * across every keyword occurrence that has one nearby). An empty {@code amount} means "use the
     * appointment's service total".
     */
    public Optional<CashDeclaration> parse(String note) {
        if (note == null || note.isBlank()) return Optional.empty();
        if (!CASH_KEYWORD.matcher(note).find()) return Optional.empty();

        // The "наличные" style never carries a number — always fall back to the service total.
        if (NALICH.matcher(note).find()) return Optional.of(new CashDeclaration(Optional.empty()));

        BigDecimal total = BigDecimal.ZERO;
        boolean found = false;

        // "cashew" states its amount right after itself — the nearest number is the first one found
        // scanning forward from the keyword.
        Matcher cashew = CASHEW.matcher(note);
        while (cashew.find()) {
            Optional<BigDecimal> amount = firstNumber(note.substring(cashew.end(),
                    Math.min(note.length(), cashew.end() + FORWARD_WINDOW)));
            if (amount.isPresent()) {
                total = total.add(amount.get());
                found = true;
            }
        }

        // A bare "cash" is the tail of "Paid $NN cash" — its amount sits right before it, so the
        // nearest number is the last one found scanning the window backward from the keyword.
        Matcher bare = BARE_CASH.matcher(note);
        while (bare.find()) {
            Optional<BigDecimal> amount = lastNumber(note.substring(
                    Math.max(0, bare.start() - BACKWARD_WINDOW), bare.start()));
            if (amount.isPresent()) {
                total = total.add(amount.get());
                found = true;
            }
        }

        return Optional.of(new CashDeclaration(found ? Optional.of(total) : Optional.empty()));
    }

    private static Optional<BigDecimal> firstNumber(String slice) {
        Matcher m = NUMBER.matcher(slice);
        return m.find() ? Optional.of(new BigDecimal(m.group(1))) : Optional.empty();
    }

    private static Optional<BigDecimal> lastNumber(String slice) {
        Matcher m = NUMBER.matcher(slice);
        BigDecimal last = null;
        while (m.find()) last = new BigDecimal(m.group(1));
        return Optional.ofNullable(last);
    }

    /**
     * A detected cash payment. {@code amount} is present only when the provider wrote a number;
     * otherwise the caller uses the appointment's service total.
     */
    public record CashDeclaration(Optional<BigDecimal> amount) {}
}
