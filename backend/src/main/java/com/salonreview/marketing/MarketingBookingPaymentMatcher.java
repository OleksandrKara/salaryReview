package com.salonreview.marketing;

import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareOrderMirror;
import com.salonreview.repo.SquareOrderMirrorRepository;
import com.salonreview.square.CashNoteParser;
import com.salonreview.square.SquareMonthAggregator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Answers "what did this specific booking collect" from the local {@code square_order} mirror (and
 * the booking's own cash-note, via {@link CashNoteParser}) — the marketing-only replacement for
 * {@code MarketingContactsService#paymentsForBookings}, which used to call {@code
 * SquareMonthAggregator#aggregate} (the same heavy monthly payroll computation {@code /reports}
 * uses) once per distinct month touched by a contact's bookings.
 *
 * <p>Deliberately <b>not</b> shared matching logic with {@code SquareMonthAggregator} — no
 * gap-matching, no discount-coverage policy, no owner-comp handling, none of the subtlety that
 * logic has earned over many real production incidents. (It does reuse {@code
 * SquareMonthAggregator.BookingPayment} as a plain, logic-free return-value shape — importing a
 * 3-field record isn't importing the aggregator's behavior.) This is acceptable here because the
 * marketing UI's own {@code Appointment} doc already documents this figure as best-effort ("current
 * catalog list price... not a payroll figure"), never read by any commission calculation.
 */
@Component
public class MarketingBookingPaymentMatcher {

    // How far from the booking's own start time to look for the order that paid for it — a client
    // splitting payment across visits, or staff ringing it up a day late, both still land here.
    private static final Duration MATCH_WINDOW = Duration.ofDays(2);

    private final SquareOrderMirrorRepository orderRepository;
    private final CashNoteParser cashNoteParser;

    public MarketingBookingPaymentMatcher(SquareOrderMirrorRepository orderRepository, CashNoteParser cashNoteParser) {
        this.orderRepository = orderRepository;
        this.cashNoteParser = cashNoteParser;
    }

    /** {@code canonicalCustomerId} must already be resolved (see {@code
     * SquareClient#canonicalCustomerIds}) — this class only reads the local mirror, it never talks
     * to Square itself. {@code catalogPrices} is used only for the cash-note fallback's {@code
     * gross} figure (no order to read a real menu price off of); pass whatever the caller already
     * resolved for the booking's own service variations. */
    public Optional<SquareMonthAggregator.BookingPayment> match(Long businessId, String canonicalCustomerId,
                                                                 SquareBookingMirror booking,
                                                                 Map<String, BigDecimal> catalogPrices) {
        if (canonicalCustomerId == null || booking.getStartAt() == null) return matchFromCashNote(booking, catalogPrices);

        Instant from = booking.getStartAt().minus(MATCH_WINDOW);
        Instant to = booking.getStartAt().plus(MATCH_WINDOW);
        List<SquareOrderMirror> candidates =
                orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(businessId, canonicalCustomerId, from, to);

        Set<String> variationIds = variationIds(booking);
        SquareOrderMirror best = null;
        Duration bestDistance = null;
        for (SquareOrderMirror order : candidates) {
            if (order.getLineItems() == null) continue;
            boolean matchesThisBooking = order.getLineItems().stream()
                    .anyMatch(li -> li.catalogObjectId() != null && variationIds.contains(li.catalogObjectId()));
            if (!matchesThisBooking || order.getClosedAt() == null) continue;
            Duration distance = Duration.between(order.getClosedAt(), booking.getStartAt()).abs();
            if (best == null || distance.compareTo(bestDistance) < 0) {
                best = order;
                bestDistance = distance;
            }
        }
        if (best == null) return matchFromCashNote(booking, catalogPrices);

        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        for (SquareOrderMirror.LineItem li : best.getLineItems()) {
            if (li.catalogObjectId() == null || !variationIds.contains(li.catalogObjectId())) continue;
            collected = collected.add(li.totalMoney() == null ? BigDecimal.ZERO : li.totalMoney());
            gross = gross.add(li.grossSalesMoney() == null ? BigDecimal.ZERO : li.grossSalesMoney());
        }
        String channel = isCashTender(best) ? "CASH" : "CARD";
        return Optional.of(new SquareMonthAggregator.BookingPayment(channel, collected, gross));
    }

    private Optional<SquareMonthAggregator.BookingPayment> matchFromCashNote(
            SquareBookingMirror booking, Map<String, BigDecimal> catalogPrices) {
        Optional<CashNoteParser.CashDeclaration> cash =
                cashNoteParser.parse(booking.getSellerNote()).or(() -> cashNoteParser.parse(booking.getCustomerNote()));
        if (cash.isEmpty()) return Optional.empty();

        BigDecimal serviceTotal = variationIds(booking).stream()
                .map(id -> catalogPrices.getOrDefault(id, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = cash.get().amount().orElse(serviceTotal);
        if (serviceTotal.signum() > 0 && collected.compareTo(serviceTotal) > 0) collected = serviceTotal; // typo guard
        BigDecimal gross = serviceTotal.signum() > 0 ? serviceTotal : collected;
        return Optional.of(new SquareMonthAggregator.BookingPayment("CASH-NOTE", collected, gross));
    }

    private static boolean isCashTender(SquareOrderMirror order) {
        if (order.getTenders() == null) return false;
        return order.getTenders().stream().anyMatch(t -> "CASH".equalsIgnoreCase(t.type()));
    }

    private static Set<String> variationIds(SquareBookingMirror booking) {
        if (booking.getAppointmentSegments() == null) return Set.of();
        return booking.getAppointmentSegments().stream()
                .map(SquareBookingMirror.Segment::serviceVariationId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }
}
