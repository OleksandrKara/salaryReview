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
 * <p>Days are bucketed in the salon's local timezone (FIRST = 1-15, SECOND = 16-end). Walk-in
 * cancellations and no-shows produce no order, so they drop out on their own; but a <em>prepaid</em>
 * appointment leaves a completed order even after it's cancelled, so cancelled/declined/no-show
 * bookings are filtered out before matching (see {@link #didNotHappen}) and never paid on.
 */
@Service
public class SquareMonthAggregator {

    private static final String CASH = "CASH";

    private final SquareClient square;
    private final CashNoteParser cashNotes;
    private final com.salonreview.repo.OwnerCustomerRepository ownerCustomers;

    public SquareMonthAggregator(SquareClient square, CashNoteParser cashNotes,
                                 com.salonreview.repo.OwnerCustomerRepository ownerCustomers) {
        this.square = square;
        this.cashNotes = cashNotes;
        this.ownerCustomers = ownerCustomers;
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

        // Square customers who are owner(s)/family: services to them aren't charged (no order), but the
        // provider is still owed their commission — see the owner-comp pass below.
        java.util.Set<String> ownerCustomerIds = ownerCustomers.findAll().stream()
                .map(com.salonreview.domain.OwnerCustomer::getSquareCustomerId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        // --- Index booking segments by (customer|service) for fast order matching, this month only ---
        Map<String, List<Seg>> segIndex = new HashMap<>();
        List<String> variationIds = new ArrayList<>();
        List<CashBooking> cashEntries = new ArrayList<>();
        List<CompCandidate> compCandidates = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            // Cancelled / declined / no-show appointments must never be paid on — not even when a
            // prepaid order for them still exists. Skipping them here keeps their prepaid order from
            // matching a booking, so it falls through to "unmatched" instead of crediting a provider.
            if (didNotHappen(b.status())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;

            boolean ownerCustomer = b.customerId() != null && ownerCustomerIds.contains(b.customerId());
            String firstProvider = null;
            List<String> bookingServiceIds = new ArrayList<>();
            for (var s : b.appointmentSegments()) {
                if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId());
                if (s.teamMemberId() == null || b.customerId() == null || s.serviceVariationId() == null) continue;
                if (firstProvider == null) firstProvider = s.teamMemberId();
                bookingServiceIds.add(s.serviceVariationId());
                segIndex.computeIfAbsent(key(b.customerId(), s.serviceVariationId()), k -> new ArrayList<>())
                        .add(new Seg(s.teamMemberId(), day, b.id(), b.startAt(), instant(b.updatedAt())));
                if (ownerCustomer) {
                    compCandidates.add(new CompCandidate(s.teamMemberId(), day, s.serviceVariationId(),
                            b.id(), b.customerId(), b.startAt()));
                }
            }
            // Cash note ("cashew $nn" or Russian "наличные") → a cash service for the booking's provider.
            // The amount is what's written, or the appointment's catalog service total when omitted.
            var cash = cashNotes.parse(b.sellerNote()).or(() -> cashNotes.parse(b.customerNote()));
            if (cash.isPresent() && firstProvider != null) {
                cashEntries.add(new CashBooking(firstProvider, day, cash.get().amount(), bookingServiceIds,
                        b.id(), b.startAt(), b.customerId()));
            }
        }

        Map<String, BigDecimal> catalogPrice = square.catalogPrices(variationIds);

        Map<Key, Acc> accs = new LinkedHashMap<>();
        List<AttributedService> services = new ArrayList<>();
        List<UnmatchedLine> unmatched = new ArrayList<>();
        Diag diag = new Diag();
        diag.orders = orders.size();

        // Bookings that were actually checked out as Cash in Square (a matched completed cash order).
        // Their cash note (if any) is a duplicate of that checkout, so we skip it when folding notes.
        java.util.Set<String> cashCheckedOutBookings = new java.util.HashSet<>();
        // Bookings that had any order line matched to them — so the owner-comp pass doesn't also pay
        // on a booking the owner actually did get charged for.
        java.util.Set<String> paidBookings = new java.util.HashSet<>();

        // --- Attribute order money to providers ---
        for (Order o : orders) {
            String orderTs = o.closedAt() != null ? o.closedAt() : o.createdAt();
            LocalDate orderDay = localDate(orderTs, zone);
            Instant checkoutAt = instant(orderTs);
            boolean cashOrder = isCashOrder(o);
            Map<String, Half> providersOnOrder = new LinkedHashMap<>();

            if (o.lineItems() != null) {
                for (OrderLineItem li : o.lineItems()) {
                    if (li.catalogObjectId() == null) continue;
                    Match m = match(segIndex, o.customerId(), li.catalogObjectId(), orderDay, checkoutAt, diag);
                    if (m == null) {
                        diag.unmatchedLineItems++;
                        BigDecimal gross = lineRevenue(li);
                        diag.unmatchedRevenue = diag.unmatchedRevenue.add(gross);
                        unmatched.add(new UnmatchedLine(str(orderDay), li.name(), gross,
                                cashOrder ? "CASH" : "CARD", o.customerId(), null));
                        continue;
                    }
                    Seg seg = m.seg;
                    if (seg.bookingId != null) paidBookings.add(seg.bookingId);
                    diag.matchedLineItems++;
                    Half half = halfOf(seg.day);
                    Acc a = accs.computeIfAbsent(new Key(seg.providerId, half), k -> new Acc());
                    // Full menu price (gross): the salon absorbs Square discounts, the provider is
                    // paid on the listed price. Matches the salon's manual "Card" figure. The discount
                    // and net are kept for the trace view, not the payout.
                    BigDecimal revenue = lineRevenue(li);
                    BigDecimal discount = SquareClient.toDollars(li.totalDiscountMoney());
                    BigDecimal net = SquareClient.toDollars(li.totalMoney());
                    boolean counted = servicePrice(li, catalogPrice).compareTo(priceCutoff) >= 0;
                    if (cashOrder) {
                        a.cashGross = a.cashGross.add(revenue);   // menu price (commission basis)
                        a.cashCollected = a.cashCollected.add(net); // after discount (what was paid)
                        if (seg.bookingId != null) cashCheckedOutBookings.add(seg.bookingId);
                    } else {
                        a.card = a.card.add(revenue);
                    }
                    if (counted) a.counted++;
                    providersOnOrder.put(seg.providerId, half);
                    services.add(new AttributedService(seg.providerId, nameById.getOrDefault(seg.providerId, "?"),
                            str(seg.day), half.name(), li.name(), revenue, discount, net, counted,
                            counted ? 1 : 0, 1, m.prepaid,
                            cashOrder ? "CASH" : "CARD", localTime(seg.startAt, zone), seg.bookingId,
                            o.customerId(), null));
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
            // Skip the note if this appointment was already checked out as Cash in Square — the
            // completed cash order above already counted it, so the note would duplicate it.
            if (cb.bookingId() != null && cashCheckedOutBookings.contains(cb.bookingId())) {
                diag.cashNotesSkipped++;
                continue;
            }
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
            // Collected = what the provider wrote in the note (or the catalog total if no amount).
            // Gross (commission basis) = the menu price; the difference is a salon-absorbed discount.
            // If the note amount exceeds the catalog (or catalog didn't resolve), treat it as gross
            // with no discount, so we never undercount the provider.
            BigDecimal collected = cb.explicitAmount.orElse(serviceTotal);
            BigDecimal gross = serviceTotal.max(collected);
            BigDecimal discount = gross.subtract(collected);
            // If catalog prices didn't resolve but a cash amount is known, count it as one service.
            if (countedSegs == 0 && gross.compareTo(priceCutoff) >= 0) countedSegs = 1;

            a.cashGross = a.cashGross.add(gross);
            a.cashCollected = a.cashCollected.add(collected);
            a.counted += countedSegs;
            int totalSegs = Math.max(cb.serviceVariationIds.size(), countedSegs);
            services.add(new AttributedService(cb.providerId, nameById.getOrDefault(cb.providerId, "?"),
                    str(cb.day), half.name(), "cash note (" + countedSegs + " counted)", gross,
                    discount, collected, countedSegs > 0, countedSegs, totalSegs, false, "CASH-NOTE",
                    localTime(cb.startAt, zone), cb.bookingId, cb.customerId(), null));
        }

        // --- Owner comps: a service rendered to an owner/family customer is never charged, so Square
        // has no order for it. The provider who did the work is still paid their commission on the
        // catalog menu price. We credit only owner bookings with NO matching order (so a booking the
        // owner did pay for isn't double-counted) and only once the appointment has started (so a
        // future owner booking isn't paid early). ---
        Instant nowInstant = Instant.now();
        List<CompCandidate> dueComps = compCandidates.stream()
                .filter(c -> c.bookingId() == null || !paidBookings.contains(c.bookingId()))
                .filter(c -> { Instant st = instant(c.startAt()); return st == null || !st.isAfter(nowInstant); })
                .toList();
        Map<String, String> compNames = dueComps.isEmpty() ? Map.of()
                : square.catalogNames(dueComps.stream().map(CompCandidate::serviceVariationId).toList());
        for (CompCandidate c : dueComps) {
            BigDecimal menu = catalogPrice.getOrDefault(c.serviceVariationId(), BigDecimal.ZERO);
            if (menu.signum() <= 0) { diag.ownerCompsSkipped++; continue; } // no catalog price → can't value it
            Half half = halfOf(c.day());
            Acc a = accs.computeIfAbsent(new Key(c.providerId(), half), k -> new Acc());
            boolean counted = menu.compareTo(priceCutoff) >= 0;
            a.card = a.card.add(menu);   // paid like card: provider keeps their rate; salon absorbs the cost
            if (counted) a.counted++;
            diag.ownerComps++;
            services.add(new AttributedService(c.providerId(), nameById.getOrDefault(c.providerId(), "?"),
                    str(c.day()), half.name(), compNames.getOrDefault(c.serviceVariationId(), "owner comp"),
                    menu, BigDecimal.ZERO, menu, counted, counted ? 1 : 0, 1, false, "COMP",
                    localTime(c.startAt(), zone), c.bookingId(), c.customerId(), null));
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

        // Label the (small) set of unattributed lines with their customer name for the trace view.
        List<UnmatchedLine> namedUnmatched = withCustomerNames(unmatched);

        return new MonthAggregation(year, month, zone.getId(), providers, diag, services, namedUnmatched);
    }

    /** Resolve customer names for the unattributed lines (one bulk Square call); best-effort. */
    private List<UnmatchedLine> withCustomerNames(List<UnmatchedLine> lines) {
        if (lines.isEmpty()) return lines;
        Map<String, String> names;
        try {
            names = square.customerNames(lines.stream().map(UnmatchedLine::customerId).toList());
        } catch (RuntimeException e) {
            return lines; // names are a nicety; don't fail the whole report if the lookup hiccups
        }
        List<UnmatchedLine> out = new ArrayList<>(lines.size());
        for (UnmatchedLine u : lines) {
            out.add(new UnmatchedLine(u.date(), u.service(), u.gross(), u.channel(), u.customerId(),
                    names.get(u.customerId())));
        }
        return out;
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

    /**
     * Square booking statuses for appointments that did not take place. A prepaid invoice can leave a
     * completed order behind even after the booking is cancelled, so these must be filtered out before
     * matching, or the provider gets credited for a service that never happened. ACCEPTED/PENDING pass.
     */
    private static boolean didNotHappen(String status) {
        if (status == null) return false;
        return switch (status) {
            case "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW" -> true;
            default -> false;
        };
    }

    private static LocalDate localDate(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        return Instant.parse(iso).atZone(zone).toLocalDate();
    }

    /** Parse a Square ISO-8601 timestamp to an Instant, or null if absent/unparseable. */
    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static final java.time.format.DateTimeFormatter TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US);

    /** The appointment start time in the salon's local zone, e.g. "2:30 PM" (null if unknown). */
    private static String localTime(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        return Instant.parse(iso).atZone(zone).format(TIME_FMT);
    }

    private static String key(String customerId, String serviceVariationId) {
        return customerId + "|" + serviceVariationId;
    }

    private static String str(LocalDate d) {
        return d == null ? "?" : d.toString();
    }

    /**
     * Match an order line to the booking that produced it, keyed on customer + service, when the
     * booking is within 2 days of the payment (a normal same-visit checkout; the small window absorbs
     * timezone/day-boundary jitter). Off-day payments are NOT auto-matched: a payment far from any
     * booking is either a genuine prepaid invoice or a late/no-show checkout, and auto-attributing it
     * (the old "nearest booking anywhere this month" fallback) both mislabelled late checkouts as
     * prepaid and let a fabricated appointment get paid on. Such lines fall through to the unmatched
     * list for owner/manager review instead. True prepaid is handled by the reviewed prepaid-package
     * feature (see docs/ROADMAP.md), not by guessing here.
     */
    private static Match match(Map<String, List<Seg>> index, String customerId, String catalogObjectId,
                               LocalDate orderDay, Instant checkoutAt, Diag diag) {
        if (customerId == null || orderDay == null) return null;
        List<Seg> candidates = index.get(key(customerId, catalogObjectId));
        if (candidates == null) return null;

        Seg near = nearestUnused(candidates, orderDay, checkoutAt, 2);
        if (near != null) {
            near.used = true;
            return new Match(near, false);
        }
        return null;
    }

    /** A matched booking segment plus whether it was a prepaid-invoice match (reserved; always false now). */
    private record Match(Seg seg, boolean prepaid) {}

    /**
     * The booking this checkout paid for, or null if none is within {@code maxDays}.
     *
     * <p>Day proximity is the primary signal (a checkout matches the visit's day; the small window
     * absorbs timezone jitter). When several equally-near bookings share a customer + service — a
     * 4-hands visit where two providers each have a booking for the same SKU — the tie is broken by
     * <em>checkout skew</em>: Square stamps the booking that was actually checked out with an
     * {@code updated_at} at the checkout moment, so the booking whose {@code updatedAt} is closest to
     * the order's close time is the one that took payment. This mirrors what Square's own dashboard
     * shows (it credits the checked-out provider) without parsing any free-text note.
     */
    static Seg nearestUnused(List<Seg> candidates, LocalDate orderDay, Instant checkoutAt, long maxDays) {
        Seg best = null;
        long bestDist = Long.MAX_VALUE;
        long bestSkew = Long.MAX_VALUE;
        for (Seg s : candidates) {
            if (s.used) continue;
            long dist = Math.abs(s.day.toEpochDay() - orderDay.toEpochDay());
            if (dist > maxDays) continue;
            long skew = (checkoutAt != null && s.updatedAt != null)
                    ? Math.abs(s.updatedAt.toEpochMilli() - checkoutAt.toEpochMilli())
                    : Long.MAX_VALUE;
            if (dist < bestDist || (dist == bestDist && skew < bestSkew)) {
                best = s;
                bestDist = dist;
                bestSkew = skew;
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
        BigDecimal cashGross = BigDecimal.ZERO;      // commission basis (menu price)
        BigDecimal cashCollected = BigDecimal.ZERO;  // what the provider actually took in
        int counted = 0;

        HalfInput toInput() {
            return new HalfInput(counted, card, tips, cashGross, cashCollected, BigDecimal.ZERO);
        }
    }

    static final class Seg {
        final String providerId;
        final LocalDate day;
        final String bookingId;
        final String startAt;
        /** When Square last touched this booking. The checked-out booking is updated at checkout time,
         *  so this lets us tell which sibling (in a multi-provider 4-hands visit) actually took payment. */
        final Instant updatedAt;
        boolean used = false;

        Seg(String providerId, LocalDate day, String bookingId, String startAt, Instant updatedAt) {
            this.providerId = providerId;
            this.day = day;
            this.bookingId = bookingId;
            this.startAt = startAt;
            this.updatedAt = updatedAt;
        }
    }

    private record CashBooking(String providerId, LocalDate day, Optional<BigDecimal> explicitAmount,
                               List<String> serviceVariationIds, String bookingId, String startAt,
                               String customerId) {}

    /** A booking segment for an owner/family customer — a candidate owner comp (credited if unpaid). */
    private record CompCandidate(String providerId, LocalDate day, String serviceVariationId,
                                 String bookingId, String customerId, String startAt) {}

    // --- result types ---

    public record MonthAggregation(int year, int month, String timezone,
                                   List<ProviderMonth> providers, Diag diagnostics,
                                   List<AttributedService> services, List<UnmatchedLine> unmatched) {}

    public record AttributedService(String providerId, String providerName, String date, String half,
                                    String service, BigDecimal gross, BigDecimal discount, BigDecimal net,
                                    boolean counted, int countedUnits, int units, boolean prepaid, String channel,
                                    String time, String bookingId, String customerId, String customer) {
        /** A copy with the (short) customer name filled in — set by the detail service after lookup. */
        public AttributedService withCustomer(String c) {
            return new AttributedService(providerId, providerName, date, half, service, gross, discount, net,
                    counted, countedUnits, units, prepaid, channel, time, bookingId, customerId, c);
        }
    }

    public record UnmatchedLine(String date, String service, BigDecimal gross, String channel,
                                String customerId, String customerName) {}

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
        public int cashNotesSkipped = 0; // notes ignored because the appointment was checked out as cash
        public int ownerComps = 0;        // services to owner/family credited at menu price (no order)
        public int ownerCompsSkipped = 0; // owner bookings we couldn't value (no catalog price)

        public int getOrders() { return orders; }
        public int getMatchedLineItems() { return matchedLineItems; }
        public int getPrepaidMatches() { return prepaidMatches; }
        public int getUnmatchedLineItems() { return unmatchedLineItems; }
        public BigDecimal getUnmatchedRevenue() { return unmatchedRevenue; }
        public int getCashNotes() { return cashNotes; }
        public int getCashNotesSkipped() { return cashNotesSkipped; }
        public int getOwnerComps() { return ownerComps; }
        public int getOwnerCompsSkipped() { return ownerCompsSkipped; }
    }
}
