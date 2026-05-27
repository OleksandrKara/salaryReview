package com.salonreview.square;

import com.salonreview.commission.HalfInput;
import com.salonreview.domain.Half;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderLineItem;
import com.salonreview.square.SquareClient.TeamMember;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a month of raw Square data into the per-provider, per-half inputs the commission engine
 * needs, using the reconciliation validated by the spike:
 *
 * <ul>
 *   <li>Bookings give attribution: each appointment segment carries the provider (team member),
 *       the service, the customer, and the day.</li>
 *   <li>Orders give the money, but no provider — so each paid line item is matched back to a
 *       booking on (customer + service + within a day) to learn its provider and half.</li>
 *   <li>{@code cashew $nn} notes on bookings become cash services for that provider.</li>
 * </ul>
 *
 * <p>Days are bucketed in the salon's local timezone (FIRST = 1-15, SECOND = 16-end). No-shows and
 * cancellations never produce an order or a cash note, so they naturally don't count.
 */
@Service
public class SquareMonthAggregator {

    private static final String CASH = "CASH";

    private final SquareClient square;
    private final CashNoteParser cashNotes;

    public SquareMonthAggregator(SquareClient square, CashNoteParser cashNotes) {
        this.square = square;
        this.cashNotes = cashNotes;
    }

    public MonthAggregation aggregate(int year, int month, BigDecimal priceCutoff) {
        ZoneId zone = resolveZone();
        YearMonth ym = YearMonth.of(year, month);
        // Pad the query window by a day each side so timezone-boundary events aren't missed.
        Instant from = ym.atDay(1).minusDays(1).atStartOfDay(zone).toInstant();
        Instant to = ym.atEndOfMonth().plusDays(2).atStartOfDay(zone).toInstant();

        Map<String, String> nameById = new HashMap<>();
        for (TeamMember tm : square.allTeamMembers()) nameById.put(tm.id(), tm.fullName());

        List<Booking> bookings = square.bookings(from, to);
        List<Order> orders = square.completedOrders(from, to);

        // --- Index booking segments by (customer|service) for fast order matching, this month only ---
        Map<String, List<Seg>> segIndex = new HashMap<>();
        List<String> variationIds = new ArrayList<>();
        List<CashBooking> cashEntries = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;

            String firstProvider = null;
            List<String> bookingServiceIds = new ArrayList<>();
            for (var s : b.appointmentSegments()) {
                if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId());
                if (s.teamMemberId() == null || b.customerId() == null || s.serviceVariationId() == null) continue;
                if (firstProvider == null) firstProvider = s.teamMemberId();
                bookingServiceIds.add(s.serviceVariationId());
                segIndex.computeIfAbsent(key(b.customerId(), s.serviceVariationId()), k -> new ArrayList<>())
                        .add(new Seg(s.teamMemberId(), day));
            }
            // Cash note ("cashew $nn" or Russian "наличные") → a cash service for the booking's provider.
            // The amount is what's written, or the appointment's catalog service total when omitted.
            var cash = cashNotes.parse(b.sellerNote()).or(() -> cashNotes.parse(b.customerNote()));
            if (cash.isPresent() && firstProvider != null) {
                cashEntries.add(new CashBooking(firstProvider, day, cash.get().amount(), bookingServiceIds));
            }
        }

        Map<String, BigDecimal> catalogPrice = square.catalogPrices(variationIds);

        Map<Key, Acc> accs = new LinkedHashMap<>();
        List<AttributedService> services = new ArrayList<>();
        List<UnmatchedLine> unmatched = new ArrayList<>();
        Diag diag = new Diag();
        diag.orders = orders.size();

        // --- Attribute order money to providers ---
        for (Order o : orders) {
            LocalDate orderDay = localDate(o.closedAt() != null ? o.closedAt() : o.createdAt(), zone);
            boolean cashOrder = isCashOrder(o);
            Map<String, Half> providersOnOrder = new LinkedHashMap<>();

            if (o.lineItems() != null) {
                for (OrderLineItem li : o.lineItems()) {
                    if (li.catalogObjectId() == null) continue;
                    Seg seg = match(segIndex, o.customerId(), li.catalogObjectId(), orderDay, diag);
                    if (seg == null) {
                        diag.unmatchedLineItems++;
                        BigDecimal gross = lineRevenue(li);
                        diag.unmatchedRevenue = diag.unmatchedRevenue.add(gross);
                        unmatched.add(new UnmatchedLine(str(orderDay), li.name(), gross,
                                cashOrder ? "CASH" : "CARD", o.customerId()));
                        continue;
                    }
                    diag.matchedLineItems++;
                    Half half = halfOf(seg.day);
                    Acc a = accs.computeIfAbsent(new Key(seg.providerId, half), k -> new Acc());
                    // Full menu price (gross): the salon absorbs Square discounts, the provider is
                    // paid on the listed price. Matches the salon's manual "Card" figure.
                    BigDecimal revenue = lineRevenue(li);
                    boolean counted = servicePrice(li, catalogPrice).compareTo(priceCutoff) >= 0;
                    if (cashOrder) a.cash = a.cash.add(revenue);
                    else a.card = a.card.add(revenue);
                    if (counted) a.counted++;
                    providersOnOrder.put(seg.providerId, half);
                    services.add(new AttributedService(seg.providerId, nameById.getOrDefault(seg.providerId, "?"),
                            str(seg.day), half.name(), li.name(), revenue, counted, cashOrder ? "CASH" : "CARD"));
                }
            }

            // Tip split: equal across the distinct providers on the ticket.
            BigDecimal tip = SquareClient.toDollars(o.totalTipMoney());
            if (tip.signum() > 0 && !providersOnOrder.isEmpty()) {
                BigDecimal share = tip.divide(BigDecimal.valueOf(providersOnOrder.size()), 2, RoundingMode.HALF_UP);
                providersOnOrder.forEach((prov, half) ->
                        accs.computeIfAbsent(new Key(prov, half), k -> new Acc()).tips =
                                accs.get(new Key(prov, half)).tips.add(share));
            }
        }

        // --- Fold in cash-note services ---
        for (CashBooking cb : cashEntries) {
            diag.cashNotes++;
            Half half = halfOf(cb.day);
            Acc a = accs.computeIfAbsent(new Key(cb.providerId, half), k -> new Acc());

            // Service total from the catalog (used when no amount was written), and count of the
            // booking's services that clear the cutoff (add-ons under the cutoff don't count).
            BigDecimal serviceTotal = BigDecimal.ZERO;
            int countedSegs = 0;
            for (String svId : cb.serviceVariationIds) {
                BigDecimal price = catalogPrice.getOrDefault(svId, BigDecimal.ZERO);
                serviceTotal = serviceTotal.add(price);
                if (price.compareTo(priceCutoff) >= 0) countedSegs++;
            }
            BigDecimal amount = cb.explicitAmount.orElse(serviceTotal);
            // If catalog prices didn't resolve but a cash amount is known, count it as one service.
            if (countedSegs == 0 && amount.compareTo(priceCutoff) >= 0) countedSegs = 1;

            a.cash = a.cash.add(amount);
            a.counted += countedSegs;
            services.add(new AttributedService(cb.providerId, nameById.getOrDefault(cb.providerId, "?"),
                    str(cb.day), half.name(), "cash note (" + countedSegs + " counted)", amount,
                    countedSegs > 0, "CASH-NOTE"));
        }

        // --- Assemble per-provider month (both halves) ---
        Map<String, ProviderMonth> byProvider = new LinkedHashMap<>();
        accs.forEach((k, a) -> byProvider.computeIfAbsent(k.providerId,
                p -> new ProviderMonth(p, nameById.getOrDefault(p, "(unknown)"),
                        HalfInput.empty(), HalfInput.empty())));
        for (var e : accs.entrySet()) {
            ProviderMonth pm = byProvider.get(e.getKey().providerId);
            HalfInput hi = e.getValue().toInput();
            byProvider.put(e.getKey().providerId, e.getKey().half == Half.FIRST
                    ? pm.withFirst(hi) : pm.withSecond(hi));
        }
        List<ProviderMonth> providers = byProvider.values().stream()
                .sorted(Comparator.comparing(p -> p.name().toLowerCase()))
                .toList();

        return new MonthAggregation(year, month, zone.getId(), providers, diag, services, unmatched);
    }

    // --- helpers ---

    private ZoneId resolveZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static Half halfOf(LocalDate d) {
        return d.getDayOfMonth() <= 15 ? Half.FIRST : Half.SECOND;
    }

    private static LocalDate localDate(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        return Instant.parse(iso).atZone(zone).toLocalDate();
    }

    private static String key(String customerId, String serviceVariationId) {
        return customerId + "|" + serviceVariationId;
    }

    private static String str(LocalDate d) {
        return d == null ? "?" : d.toString();
    }

    /**
     * Match an order line to the booking that produced it, keyed on customer + service. Prefers a
     * booking within 2 days of the payment; if none (e.g. a prepaid invoice paid well before the
     * appointment) falls back to the nearest same-customer+service booking anywhere in the month, so
     * prepaid revenue is still attributed — to the service date, not the prepay date.
     */
    private static Seg match(Map<String, List<Seg>> index, String customerId, String catalogObjectId,
                             LocalDate orderDay, Diag diag) {
        if (customerId == null || orderDay == null) return null;
        List<Seg> candidates = index.get(key(customerId, catalogObjectId));
        if (candidates == null) return null;

        Seg near = nearestUnused(candidates, orderDay, 2);
        if (near != null) {
            near.used = true;
            return near;
        }
        Seg far = nearestUnused(candidates, orderDay, Long.MAX_VALUE); // prepaid: any date this month
        if (far != null) {
            far.used = true;
            diag.prepaidMatches++;
            return far;
        }
        return null;
    }

    /** Nearest unused segment whose day is within {@code maxDays} of the order, or null. */
    private static Seg nearestUnused(List<Seg> candidates, LocalDate orderDay, long maxDays) {
        Seg best = null;
        long bestDist = Long.MAX_VALUE;
        for (Seg s : candidates) {
            if (s.used) continue;
            long dist = Math.abs(s.day.toEpochDay() - orderDay.toEpochDay());
            if (dist <= maxDays && dist < bestDist) {
                best = s;
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean isCashOrder(Order o) {
        if (o.tenders() == null) return false;
        BigDecimal cash = BigDecimal.ZERO, other = BigDecimal.ZERO;
        for (var t : o.tenders()) {
            BigDecimal amt = SquareClient.toDollars(t.amountMoney());
            if (CASH.equals(t.type())) cash = cash.add(amt);
            else other = other.add(amt);
        }
        return cash.compareTo(other) > 0;
    }

    /** Price used for the tier cutoff: catalog list price, falling back to the charged line amount. */
    private static BigDecimal servicePrice(OrderLineItem li, Map<String, BigDecimal> catalogPrice) {
        BigDecimal p = catalogPrice.get(li.catalogObjectId());
        if (p != null) return p;
        return lineRevenue(li);
    }

    /** Full menu (gross) price of a line, before Square discounts; falls back to the net total. */
    private static BigDecimal lineRevenue(OrderLineItem li) {
        if (li.grossSalesMoney() != null) return SquareClient.toDollars(li.grossSalesMoney());
        return SquareClient.toDollars(li.totalMoney());
    }

    // --- internal mutable accumulators ---

    private record Key(String providerId, Half half) {}

    private static final class Acc {
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal tips = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        int counted = 0;

        HalfInput toInput() {
            return new HalfInput(counted, card, tips, cash, BigDecimal.ZERO);
        }
    }

    private static final class Seg {
        final String providerId;
        final LocalDate day;
        boolean used = false;

        Seg(String providerId, LocalDate day) {
            this.providerId = providerId;
            this.day = day;
        }
    }

    private record CashBooking(String providerId, LocalDate day, Optional<BigDecimal> explicitAmount,
                               List<String> serviceVariationIds) {}

    // --- result types ---

    public record MonthAggregation(int year, int month, String timezone,
                                   List<ProviderMonth> providers, Diag diagnostics,
                                   List<AttributedService> services, List<UnmatchedLine> unmatched) {}

    public record AttributedService(String providerId, String providerName, String date, String half,
                                    String service, BigDecimal gross, boolean counted, String channel) {}

    public record UnmatchedLine(String date, String service, BigDecimal gross, String channel, String customerId) {}

    public record ProviderMonth(String providerId, String name, HalfInput firstHalf, HalfInput secondHalf) {
        ProviderMonth withFirst(HalfInput h) { return new ProviderMonth(providerId, name, h, secondHalf); }
        ProviderMonth withSecond(HalfInput h) { return new ProviderMonth(providerId, name, firstHalf, h); }
    }

    public static final class Diag {
        public int orders = 0;
        public int matchedLineItems = 0;
        public int prepaidMatches = 0;
        public int unmatchedLineItems = 0;
        public BigDecimal unmatchedRevenue = BigDecimal.ZERO;
        public int cashNotes = 0;

        public int getOrders() { return orders; }
        public int getMatchedLineItems() { return matchedLineItems; }
        public int getPrepaidMatches() { return prepaidMatches; }
        public int getUnmatchedLineItems() { return unmatchedLineItems; }
        public BigDecimal getUnmatchedRevenue() { return unmatchedRevenue; }
        public int getCashNotes() { return cashNotes; }
    }
}
