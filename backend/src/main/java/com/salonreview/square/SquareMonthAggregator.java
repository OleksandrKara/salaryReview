package com.salonreview.square;

import com.salonreview.commission.HalfInput;
import com.salonreview.domain.Half;
import com.salonreview.square.SquareClient.AppliedDiscount;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Invoice;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderDiscount;
import com.salonreview.square.SquareClient.OrderLineItem;
import com.salonreview.square.SquareClient.Payment;
import com.salonreview.square.SquareClient.Tender;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.util.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Logger log = LoggerFactory.getLogger(SquareMonthAggregator.class);

    private final SquareClientProvider squareClientProvider;
    private final CashNoteParser cashNotes;
    private final com.salonreview.repo.OwnerCustomerRepository ownerCustomers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SalonConfigRepository salonConfig;
    // Phase 2f: the local Square mirror — read only by #aggregateFromMirror, the shadow-diff
    // twin of #aggregate (which still reads live Square). Never mixed within one computation.
    private final com.salonreview.repo.SquareBookingMirrorRepository bookingMirrorRepository;
    private final com.salonreview.repo.SquareOrderMirrorRepository orderMirrorRepository;
    private final com.salonreview.repo.SquarePaymentMirrorRepository paymentMirrorRepository;
    // Phase 2i cutover switch — see the class doc on SquareMirrorProperties itself for why this is a
    // live flag and not just a one-time code change.
    private final com.salonreview.config.SquareMirrorProperties mirrorProperties;

    // 12 different callers (settlements, suspicious/cancelled-booking detection, owner overview,
    // revenue pulse, provider-visit ingest, marketing ads-report/analytics) each independently call
    // aggregate() for what's very often the exact same (business, year, month, cutoff) — e.g.
    // OwnerOverviewService.fromSquare() calls aggregate() directly AND via
    // SettlementPreviewService.preview(), which calls it again. Nothing dedupes that. This cache
    // makes every one of those callers share a single computation instead of each redoing the full
    // matching/cash-note/discount/comp pipeline from scratch. Shorter than the marketing tabs' TTL
    // (money, not analytics) — same "Sync now" + mutation-invalidation wiring as
    // SettlementPreviewService's own cache; see #invalidateCache.
    private static final Duration CACHE_TTL = Duration.ofMinutes(3);
    private final TtlCache cache = new TtlCache();

    public SquareMonthAggregator(SquareClientProvider squareClientProvider, CashNoteParser cashNotes,
                                 com.salonreview.repo.OwnerCustomerRepository ownerCustomers,
                                 com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                 com.salonreview.repo.SalonConfigRepository salonConfig,
                                 com.salonreview.repo.SquareBookingMirrorRepository bookingMirrorRepository,
                                 com.salonreview.repo.SquareOrderMirrorRepository orderMirrorRepository,
                                 com.salonreview.repo.SquarePaymentMirrorRepository paymentMirrorRepository,
                                 com.salonreview.config.SquareMirrorProperties mirrorProperties) {
        this.squareClientProvider = squareClientProvider;
        this.cashNotes = cashNotes;
        this.ownerCustomers = ownerCustomers;
        this.currentBusinessContext = currentBusinessContext;
        this.salonConfig = salonConfig;
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.orderMirrorRepository = orderMirrorRepository;
        this.paymentMirrorRepository = paymentMirrorRepository;
        this.mirrorProperties = mirrorProperties;
    }

    /** Backs the global "Sync now" button (see SquareSyncController) and every mutation that can
     * change a settlement figure — same reasoning as every other {@code invalidateCache()} in this
     * codebase: only this business's own cached entries, so one business's action never forces
     * another's already-fresh cache to also recompute. {@link com.salonreview.square.SettlementPreviewService#invalidateCache()}
     * already calls this too, so every existing settlement-mutation call site gets this for free. */
    public void invalidateCache() {
        cache.invalidateWhere(k -> k.contains(":" + currentBusinessContext.id() + ":"));
    }

    public MonthAggregation aggregate(int year, int month, BigDecimal priceCutoff) {
        // "aggregate:" prefix is load-bearing, not decorative — invalidateCache() matches on
        // ":businessId:", which requires a leading colon *before* the id; a bare
        // "businessId:year:month:cutoff" key has no such leading colon and would silently never
        // match, leaving invalidateCache() a no-op (caught by this class's own cache tests).
        String key = "aggregate:" + currentBusinessContext.id() + ":" + year + ":" + month + ":" + priceCutoff;
        // Phase 2i cutover: the mirror path is the default (see SquareMirrorProperties) now that
        // Milestone 2g's shadow-diff came back clean across both businesses' full backfilled history.
        // isAggregateEnabled()==false is the emergency fallback to live Square during burn-in.
        return cache.get(key, CACHE_TTL, () -> mirrorProperties.isAggregateEnabled()
                ? aggregateFromMirror(year, month, priceCutoff)
                : computeAggregate(year, month, priceCutoff));
    }

    /** Mirror-backed twin of {@link #computeAggregate} (Phase 2f) — reads the local Square
     * booking/order/payment mirror (see the Phase 2 sync plan) instead of live Square for the three
     * raw lists; every other Square read (team members, catalog, canonicalization, customer names,
     * invoices) and every matching/cash-note/discount/comp/suspicious/cancellation rule is the exact
     * same shared code as the live path via {@link #computeAggregateFrom} — only where
     * bookings/orders/payments come from differs. Since Phase 2i this is {@link #aggregate}'s own
     * default path (see {@link com.salonreview.config.SquareMirrorProperties}); it's also called
     * directly, uncached, by the Milestone 2g shadow-diff comparator, which always wants a fresh
     * computation to compare against a fresh live one, not a cached result from either side.
     */
    public MonthAggregation aggregateFromMirror(int year, int month, BigDecimal priceCutoff) {
        long startedAtNanos = System.nanoTime();
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);
        ZoneId zone = resolveZone(square);
        YearMonth ym = YearMonth.of(year, month);
        Instant from = ym.atDay(1).minusDays(1).atStartOfDay(zone).toInstant();
        Instant to = ym.atEndOfMonth().plusDays(2).atStartOfDay(zone).toInstant();

        List<Booking> bookings = bookingMirrorRepository.findByBusinessIdAndStartAtBetween(businessId, from, to)
                .stream().map(SquareMonthAggregator::mirrorToBooking).toList();
        List<Order> orders = orderMirrorRepository.findByBusinessIdAndClosedAtBetween(businessId, from, to)
                .stream().map(SquareMonthAggregator::mirrorToOrder).toList();
        List<Payment> payments = paymentMirrorRepository.findByBusinessIdAndCreatedAtBetween(businessId, from, to)
                .stream().map(SquareMonthAggregator::mirrorToPayment).toList();

        return computeAggregateFrom(year, month, priceCutoff, businessId, square, zone,
                bookings, orders, payments, startedAtNanos, "mirror");
    }

    private MonthAggregation computeAggregate(int year, int month, BigDecimal priceCutoff) {
        long startedAtNanos = System.nanoTime();
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);
        ZoneId zone = resolveZone(square);
        YearMonth ym = YearMonth.of(year, month);
        // Pad the query window by a day each side so timezone-boundary events aren't missed.
        Instant from = ym.atDay(1).minusDays(1).atStartOfDay(zone).toInstant();
        Instant to = ym.atEndOfMonth().plusDays(2).atStartOfDay(zone).toInstant();

        // Bookings, orders, and payments are independent Square reads; fetch them concurrently to
        // halve cold latency. Payments (not just Orders) are needed to catch a charge taken directly
        // against a customer's card on file, bypassing the booking checkout and so never producing an
        // Order at all — see detectOrphanPayments().
        var bookingsF = java.util.concurrent.CompletableFuture.supplyAsync(() -> square.bookings(from, to));
        var ordersF = java.util.concurrent.CompletableFuture.supplyAsync(() -> square.completedOrders(from, to));
        var paymentsF = java.util.concurrent.CompletableFuture.supplyAsync(() -> square.payments(from, to));
        List<Booking> bookings = bookingsF.join();
        List<Order> orders = ordersF.join();
        List<Payment> payments = paymentsF.join();
        return computeAggregateFrom(year, month, priceCutoff, businessId, square, zone,
                bookings, orders, payments, startedAtNanos, "live");
    }

    /** Everything downstream of "here are this month's raw bookings/orders/payments" — shared,
     * byte-for-byte identical logic for both {@link #computeAggregate} (live) and {@link
     * #aggregateFromMirror} (local mirror, Phase 2f). Only the raw-fetch step above differs
     * between the two callers; nothing in this method knows or cares which one supplied its data. */
    private MonthAggregation computeAggregateFrom(int year, int month, BigDecimal priceCutoff, Long businessId,
            SquareClient square, ZoneId zone, List<Booking> bookings, List<Order> orders, List<Payment> payments,
            long startedAtNanos, String source) {
        Map<String, String> nameById = new HashMap<>();
        for (TeamMember tm : square.allTeamMembers()) nameById.put(tm.id(), tm.fullName());

        // Square customers who are owner(s)/family: services to them aren't charged (no order), but the
        // provider is still owed their commission — see the owner-comp pass below. Fetched before the
        // canonicalization pass so these ids get resolved right alongside every booking/order id.
        java.util.Set<String> rawOwnerCustomerIds = ownerCustomers.findAllByBusinessId(businessId).stream()
                .map(com.salonreview.domain.OwnerCustomer::getSquareCustomerId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        // Square can silently merge two duplicate customer profiles into one canonical id (e.g. one
        // profile from online booking, one from a register walk-in). An already-written Order keeps
        // whichever id was current when it was created, while a Booking for the very same real visit
        // can carry the other, now-stale-or-canonical id — so the same customer's booking and their
        // paid order can carry two different, permanently un-equal ids. Every customer-keyed lookup
        // below (order-to-booking matching, suspicious/cancellation suppression, owner-comp) assumes
        // equal ids mean the same person, so resolve every id we've seen — including the manually
        // configured owner/family ids, which can drift stale the same way — through Square's live
        // customer record once, up front, and rewrite both lists. This makes all of it merge-proof
        // for free.
        java.util.Set<String> allCustomerIds = new java.util.HashSet<>(rawOwnerCustomerIds);
        for (Booking b : bookings) if (b.customerId() != null) allCustomerIds.add(b.customerId());
        for (Order o : orders) if (o.customerId() != null) allCustomerIds.add(o.customerId());
        Map<String, String> canonicalCustomerId = square.canonicalCustomerIds(allCustomerIds);
        bookings = bookings.stream()
                .map(b -> b.customerId() == null ? b
                        : b.withCustomerId(canonicalCustomerId.getOrDefault(b.customerId(), b.customerId())))
                .toList();
        orders = orders.stream()
                .map(o -> o.customerId() == null ? o
                        : o.withCustomerId(canonicalCustomerId.getOrDefault(o.customerId(), o.customerId())))
                .toList();
        java.util.Set<String> ownerCustomerIds = rawOwnerCustomerIds.stream()
                .map(id -> canonicalCustomerId.getOrDefault(id, id))
                .collect(java.util.stream.Collectors.toSet());

        // --- Index booking segments by (customer|service) for fast order matching, this month only ---
        Map<String, List<Seg>> segIndex = new HashMap<>();
        List<String> variationIds = new ArrayList<>();
        List<CashBooking> cashEntries = new ArrayList<>();
        List<CompCandidate> compCandidates = new ArrayList<>();
        // Best-guess "which appointment might this belong to" hints for orphan-payment detection —
        // deliberately looser than segIndex (doesn't require a resolved service/provider), since a
        // suggestion is a starting point for a human to confirm via Manual Adjustment, not a payout.
        Map<String, List<BookingHint>> bookingHintsByCustomer = new HashMap<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            // Cancelled / declined / no-show appointments must never be paid on — not even when a
            // prepaid order for them still exists. Skipping them here keeps their prepaid order from
            // matching a booking, so it falls through to "unmatched" instead of crediting a provider.
            if (didNotHappen(b.status())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;

            if (b.customerId() != null) {
                String hintProvider = b.appointmentSegments().stream()
                        .map(s -> s.teamMemberId()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                bookingHintsByCustomer.computeIfAbsent(b.customerId(), k -> new ArrayList<>())
                        .add(new BookingHint(b.id(), hintProvider, day));
            }

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
            // Both fields, not just whichever one carried the cash keyword — an invoice reference
            // (see linkedInvoiceAmount below) can land in the other note field from the cash mention
            // itself (a provider's own note vs. whatever the customer wrote at booking time).
            String cashNoteText = java.util.stream.Stream.of(b.sellerNote(), b.customerNote())
                    .filter(java.util.Objects::nonNull).reduce("", (a, c) -> a + "\n" + c);
            if (cash.isPresent() && firstProvider != null) {
                cashEntries.add(new CashBooking(firstProvider, day, cash.get().amount(), bookingServiceIds,
                        b.id(), b.startAt(), b.customerId(), cashNoteText));
            }
        }

        Map<String, BigDecimal> catalogPrice = square.catalogPrices(variationIds);

        // Which Square order discounts the salon "absorbs" into the provider's commission basis —
        // false (the default) covers every discount, same as always. See SalonConfig's own doc.
        com.salonreview.domain.SalonConfig sc = salonConfig.findByBusinessId(businessId).orElse(null);
        boolean restrictDiscountCoverage = sc != null && sc.isRestrictDiscountCoverage();
        java.util.Set<String> coveredDiscountNames = restrictDiscountCoverage
                ? sc.coveredDiscountNameSubstrings() : java.util.Set.of();

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
            // Lines created for this order, so the order tip can be spread across them after the loop.
            List<LineRef> orderLineRefs = new ArrayList<>();
            // The one booking that best explains this whole ticket (if any) — see preferredBooking().
            String preferredBookingId = preferredBooking(segIndex, o, orderDay);

            if (o.lineItems() != null) {
                for (OrderLineItem li : o.lineItems()) {
                    // A line item with no catalog id at all (Square's "Custom Amount" / type-an-amount
                    // charge — e.g. a split-payment remainder rung up by hand instead of picking the
                    // actual service) can never be matched by SKU below — match() looks candidates up
                    // by (customer, catalogObjectId). Previously this was silently `continue`d before
                    // ever reaching the unmatched-tracking below, so the money vanished entirely —
                    // neither paid to a provider nor visible as "unattributed" for the owner to review.
                    // Confirmed against two real cases (a $19 custom-amount card charge, and a $45 +
                    // $65 pair) that had a genuine COMPLETED order and payment but no catalog line —
                    // routing them into the same unmatched path as a failed-SKU-match line fixes it.
                    Match m = li.catalogObjectId() == null ? null
                            : match(segIndex, o.customerId(), li.catalogObjectId(), orderDay, checkoutAt,
                                    preferredBookingId, diag);
                    if (m == null) {
                        // Only this month's unattributed sales. The order query is padded a couple of days
                        // each side so late checkouts / timezone-boundary orders still match a booking; but
                        // an unmatched line's only date is its order day, so off-month padding orders (last
                        // day of the prev month, first days of the next) must not show in this month's list.
                        if (orderDay != null && orderDay.getYear() == year && orderDay.getMonthValue() == month) {
                            diag.unmatchedLineItems++;
                            BigDecimal gross = lineRevenue(li);
                            diag.unmatchedRevenue = diag.unmatchedRevenue.add(gross);
                            String serviceName = li.name() != null ? li.name()
                                    : li.catalogObjectId() == null ? "Custom amount (no catalog item)" : "?";
                            unmatched.add(new UnmatchedLine(str(orderDay), serviceName, gross,
                                    cashOrder ? "CASH" : "CARD", o.customerId(), null));
                        }
                        continue;
                    }
                    Seg seg = m.seg;
                    if (seg.bookingId != null) paidBookings.add(seg.bookingId);
                    diag.matchedLineItems++;
                    Half half = halfOf(seg.day);
                    Acc a = accs.computeIfAbsent(new Key(seg.providerId, half), k -> new Acc());
                    // Full menu price (gross): by default the salon absorbs every Square discount, the
                    // provider is paid on the listed price, matching the salon's manual "Card" figure.
                    // When restrictDiscountCoverage is on, only discounts matching coveredDiscountNames
                    // are absorbed (e.g. a prepaid-deposit discount) — every other discount (ordinary
                    // promos, coupons) instead reduces the provider's commission basis down to what was
                    // actually collected (see SalonConfig#restrictDiscountCoverage's own doc).
                    BigDecimal net = SquareClient.toDollars(li.totalMoney());
                    BigDecimal revenue;
                    BigDecimal discount;
                    if (restrictDiscountCoverage) {
                        discount = coveredDiscountOn(o, li, coveredDiscountNames);
                        revenue = net.add(discount);
                    } else {
                        revenue = lineRevenue(li);
                        discount = SquareClient.toDollars(li.totalDiscountMoney());
                    }
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
                            str(seg.day), half.name(), li.name(), revenue, discount, net, BigDecimal.ZERO, counted,
                            counted ? 1 : 0, 1, m.prepaid,
                            cashOrder ? "CASH" : "CARD", localTime(seg.startAt, zone), seg.bookingId,
                            o.customerId(), null));
                    orderLineRefs.add(new LineRef(services.size() - 1, seg.providerId, revenue));
                }
            }

            // Tip split: equal across the distinct providers on the ticket (the payout basis), then each
            // provider's share is spread across their line(s) on the order for the per-transaction trace.
            BigDecimal tip = SquareClient.toDollars(o.totalTipMoney());
            if (tip.signum() > 0 && !providersOnOrder.isEmpty()) {
                BigDecimal share = tip.divide(BigDecimal.valueOf(providersOnOrder.size()), 2, RoundingMode.HALF_UP);
                providersOnOrder.forEach((prov, half) -> {
                    accs.computeIfAbsent(new Key(prov, half), k -> new Acc()).tips =
                            accs.get(new Key(prov, half)).tips.add(share);
                    allocateTip(services, orderLineRefs.stream().filter(r -> prov.equals(r.provider())).toList(), share);
                });
            }
        }

        // --- Fold in cash-note services ---
        // Cache of a customer's Square invoices, filled lazily as cash-note bookings reference one —
        // a customer with several cash-note visits referencing invoices this month would otherwise
        // re-fetch the same list once per booking.
        Map<String, List<Invoice>> invoicesByCustomer = new HashMap<>();
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
            // Cash written in a note can never exceed the service's own catalog price — if it does,
            // it's a typo (an extra zero, a misplaced decimal), not a real overpayment, so it's
            // capped at the catalog price rather than inflating the commission basis. Only applies
            // when the catalog price actually resolved (serviceTotal > 0); if it didn't, the note's
            // amount is the only signal available and is trusted as-is, unchanged from before.
            BigDecimal rawCollected = cb.explicitAmount.orElse(serviceTotal);
            boolean amountCapped = serviceTotal.signum() > 0 && rawCollected.compareTo(serviceTotal) > 0;
            BigDecimal collected = amountCapped ? serviceTotal : rawCollected;
            if (amountCapped) diag.cashNoteAmountCapped++;

            // A deposit already collected via a real, PAID Square Invoice referenced by number in
            // this note (e.g. "Invoice: 001365 ($100) paid") — the salon's own "deposit is covered"
            // policy means this portion was already paid by card, separately from whatever cash the
            // note declares for the remainder, and should be booked as CARD rather than folded into
            // the cash side just because the same visit's note also mentions cash. Resolved against
            // Square's own invoice record (not guessed from any other digit in the note).
            BigDecimal invoicePortion = linkedInvoiceAmount(cb, square, invoicesByCustomer);

            // Menu price (gross): the catalog price when it resolves, same as always. When it
            // doesn't — found live 2026-08-28, a deleted/no-longer-in-catalog service variation —
            // there's no independent "menu price" anchor left at all, so the note's own numbers are
            // the only signal: the cash figure plus any linked deposit, not just the cash figure
            // alone. Without adding the deposit back in here, a real, correctly-found invoice had no
            // "room" left above the cash amount to be credited into (gross - collected = 0), and got
            // silently capped to zero — the invoice link would resolve correctly but the money never
            // actually made it into anyone's commission.
            BigDecimal gross = serviceTotal.signum() > 0 ? serviceTotal : collected.add(invoicePortion);
            // If catalog prices didn't resolve but a cash amount is known, count it as one service.
            if (countedSegs == 0 && gross.compareTo(priceCutoff) >= 0) countedSegs = 1;

            // Capped at the room actually left after the cash figure, so a linked deposit can never
            // push the total booked for this visit above its own gross.
            BigDecimal invoiceCardPortion = invoicePortion.min(gross.subtract(collected).max(BigDecimal.ZERO));

            // If the note still leaves a gap after that (cash collected + any linked deposit is less
            // than the full price), look for the rest among this month's already-computed
            // unattributed sales — same customer, within a couple of days, amount matching the gap to
            // the cent. This is the same-day-split-payment pattern: a client splits card+cash, the
            // provider notes only the cash portion, and staff rings up the card portion by hand
            // (which the order-matcher can't tie to a specific service, so it lands in `unmatched`).
            // A match is reclassified as real revenue for this exact visit instead of a phantom
            // "salon discount" — the total gross is unchanged either way; this only fixes which
            // channel the money is booked under.
            BigDecimal gap = gross.subtract(collected).subtract(invoiceCardPortion);
            UnmatchedLine gapMatch = gap.signum() > 0 && cb.customerId() != null
                    ? findGapMatch(unmatched, cb.customerId(), cb.day, gap) : null;
            BigDecimal discount = gap;
            BigDecimal cardPortion = invoiceCardPortion;
            BigDecimal cashPortionCollected = collected;
            if (gapMatch != null) {
                unmatched.remove(gapMatch);
                diag.unmatchedLineItems--;
                diag.unmatchedRevenue = diag.unmatchedRevenue.subtract(gapMatch.gross());
                diag.cashNoteGapMatches++;
                discount = BigDecimal.ZERO;
                if ("CASH".equals(gapMatch.channel())) {
                    cashPortionCollected = cashPortionCollected.add(gapMatch.gross());
                } else {
                    cardPortion = cardPortion.add(gapMatch.gross());
                }
            }
            BigDecimal cashPortionGross = gross.subtract(cardPortion);
            // Whatever's still unexplained (no linked invoice, no same-day gap match): the legacy
            // default absorbs it — the provider is paid commission on the full remaining menu price
            // regardless, same as always. A restrictDiscountCoverage business instead treats it as a
            // real, uncovered discount that reduces the provider's commission basis down to what was
            // actually collected (see SalonConfig#restrictDiscountCoverage's own doc — same rule this
            // already applies to checked-out orders, now reaching cash-note bookings too).
            if (restrictDiscountCoverage && discount.signum() > 0) {
                cashPortionGross = cashPortionGross.subtract(discount).max(BigDecimal.ZERO);
            }

            a.cashGross = a.cashGross.add(cashPortionGross);
            a.cashCollected = a.cashCollected.add(cashPortionCollected);
            a.card = a.card.add(cardPortion);
            a.counted += countedSegs;
            int totalSegs = Math.max(cb.serviceVariationIds.size(), countedSegs);
            String label = "cash note (" + countedSegs + " counted)"
                    + (amountCapped ? " — note exceeded catalog price, capped" : "");
            services.add(new AttributedService(cb.providerId, nameById.getOrDefault(cb.providerId, "?"),
                    str(cb.day), half.name(), label, cashPortionGross,
                    discount, cashPortionCollected, BigDecimal.ZERO, countedSegs > 0, countedSegs, totalSegs,
                    false, "CASH-NOTE", localTime(cb.startAt, zone), cb.bookingId, cb.customerId(), null));
            if (invoiceCardPortion.signum() > 0) {
                // The linked-deposit portion, as its own line — never counted as an extra service
                // unit, the cash-note line above already counts this one visit toward the tier.
                services.add(new AttributedService(cb.providerId, nameById.getOrDefault(cb.providerId, "?"),
                        str(cb.day), half.name(), "Deposit invoice (auto-matched)",
                        invoiceCardPortion, BigDecimal.ZERO, invoiceCardPortion, BigDecimal.ZERO,
                        false, 0, 0, false, "CARD",
                        localTime(cb.startAt, zone), cb.bookingId, cb.customerId(), null));
            }
            if (gapMatch != null && !"CASH".equals(gapMatch.channel())) {
                // The card portion, as its own line — never counted as an extra service unit, the
                // cash-note line above already counts this one visit toward the tier. A CASH-channel
                // gap match instead folds straight into cashPortionCollected above with no separate
                // line, since it's still the same cash-note visit, just now fully accounted for.
                BigDecimal gapMatchAmount = gapMatch.gross();
                services.add(new AttributedService(cb.providerId, nameById.getOrDefault(cb.providerId, "?"),
                        str(cb.day), half.name(), gapMatch.service() + " (auto-matched to cash-note gap)",
                        gapMatchAmount, BigDecimal.ZERO, gapMatchAmount, BigDecimal.ZERO,
                        false, 0, 0, false, gapMatch.channel(),
                        localTime(cb.startAt, zone), cb.bookingId, cb.customerId(), null));
            }
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
                    menu, BigDecimal.ZERO, menu, BigDecimal.ZERO, counted, counted ? 1 : 0, 1, false, "COMP",
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
        List<UnmatchedLine> namedUnmatched = withCustomerNames(square, unmatched);

        // --- Orphan payments: completed Square payments with no linked Order at all, so the
        // order-based matching above never saw them (a card charged directly against a customer's
        // card on file, bypassing the booking checkout, leaves a Payment but no Order). Never folded
        // into revenue/commission automatically — see OrphanPayment's doc comment.
        java.util.Set<String> knownOrderIds = orders.stream().map(Order::id)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<OrphanPayment> orphanPayments = detectOrphanPayments(
                payments, knownOrderIds, bookingHintsByCustomer, nameById, zone, year, month);
        diag.orphanPayments = orphanPayments.size();
        for (OrphanPayment op : orphanPayments) diag.orphanPaymentRevenue = diag.orphanPaymentRevenue.add(op.amount());
        List<OrphanPayment> namedOrphanPayments = withOrphanCustomerNames(square, orphanPayments);

        // A completed order from the same customer near the appointment day means the visit WAS paid —
        // even when our strict payout matcher (customer + exact service SKU, within 2 days) couldn't tie
        // a line to this specific booking. That miss is common: the front desk rings up a custom amount,
        // a different SKU, or a line with no catalog id (which the matcher skips). Such appointments have
        // a money trail, so the owner shouldn't have to review them — index each customer's completed
        // order days to suppress them below (regardless of any note on the booking).
        Map<String, List<LocalDate>> orderDaysByCustomer = new HashMap<>();
        for (Order o : orders) {
            if (o.customerId() == null) continue;
            LocalDate od = localDate(o.closedAt() != null ? o.closedAt() : o.createdAt(), zone);
            if (od != null) orderDaysByCustomer.computeIfAbsent(o.customerId(), k -> new ArrayList<>()).add(od);
        }

        // --- Suspicious bookings: appointments that happened but have no money trail. Detection
        // happens after the order-matching, cash-note, and owner-comp passes are complete so we can
        // honestly say "this one slipped through all of them". A booking is suspicious when ALL of:
        //   1. Status is not CANCELLED/DECLINED/NO_SHOW.
        //   2. startAt is strictly in the past.
        //   3. Its booking ID is not in paidBookings (no order matched) AND the customer has no
        //      completed order within 2 days of the visit (no payment trail at all).
        //   4. It has no cash note in seller or customer note.
        //   5. The customer is not in the owner-customer list.
        // We emit one candidate per appointment segment that has provider + customer + service all set.
        Instant nowForSuspicious = Instant.now();
        List<SuspiciousCandidate> suspicious = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            if (didNotHappen(b.status())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;
            Instant startAt = instant(b.startAt());
            if (startAt == null || !startAt.isBefore(nowForSuspicious)) continue;
            if (b.id() != null && paidBookings.contains(b.id())) continue;
            if (b.customerId() != null
                    && hasPaymentWithinDays(orderDaysByCustomer.get(b.customerId()), day, 2)) continue;
            if (b.customerId() != null && ownerCustomerIds.contains(b.customerId())) continue;
            if (cashNotes.parse(b.sellerNote()).isPresent()
                    || cashNotes.parse(b.customerNote()).isPresent()) continue;

            Half half = halfOf(day);
            for (var seg : b.appointmentSegments()) {
                if (seg.teamMemberId() == null || b.customerId() == null
                        || seg.serviceVariationId() == null) continue;
                BigDecimal gross = catalogPrice.get(seg.serviceVariationId()); // nullable
                suspicious.add(new SuspiciousCandidate(
                        b.id(), b.customerId(), seg.teamMemberId(),
                        nameById.getOrDefault(seg.teamMemberId(), "?"),
                        seg.serviceVariationId(),
                        day, startAt, half, gross,
                        b.sellerNote(), b.customerNote()));
            }
        }

        // Customers we charged a "Cancelation Policy" (no-show) fee, by the fee's local day. A cancelled
        // appointment we already billed a fee on is accounted for — not a cash-fraud risk — so it's dropped
        // below. Fee detection is shared with NoShowFeeService (single source of truth for the fee shape).
        // Null feeAmount (Phase 4.4 — the no-show fee program is off for this business) means no order can
        // ever match, same as NoShowFeeService's own compute() short-circuiting entirely in that case.
        BigDecimal feeAmount = salonConfig.findByBusinessId(businessId)
                .map(com.salonreview.domain.SalonConfig::getNoShowFeeAmount).orElse(null);
        Map<String, List<LocalDate>> feeDaysByCustomer = new HashMap<>();
        for (Order o : orders) {
            if (o.customerId() == null || !NoShowFeeService.isCancellationFeeOrder(o, feeAmount)) continue;
            LocalDate fd = localDate(o.closedAt() != null ? o.closedAt() : o.createdAt(), zone);
            if (fd != null) feeDaysByCustomer.computeIfAbsent(o.customerId(), k -> new ArrayList<>()).add(fd);
        }

        // --- Cancelled appointments: appointments the salon marked CANCELLED_BY_SELLER that were cancelled
        // AFTER their start time — i.e. the slot's time had already arrived when it was voided. That's the
        // "service happened, then cancelled to hide cash" pattern; a cancellation made in advance is a normal
        // reschedule and is ignored. Surfaced (owner-only) so the owner can confirm on camera nothing was
        // done. We also drop cancellations we already charged a no-show/cancellation fee on (accounted for).
        // We emit every qualifying seller-cancelled segment regardless of the assigned team member's role;
        // the service layer drops those assigned to owner/manager staff (a non-fraud concern) since it, not
        // the aggregator, knows app roles. Customer-side cancellations aren't a provider action, so excluded. ---
        List<CancelledCandidate> cancellations = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            if (!"CANCELLED_BY_SELLER".equals(b.status())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;
            Instant startAt = instant(b.startAt());
            Instant cancelledAt = instant(b.updatedAt()); // last change on a cancelled booking ≈ the cancel
            // Only appointments cancelled after their start time (the slot already came). This also implies
            // the start is in the past, so no separate past-check is needed.
            if (startAt == null || cancelledAt == null || !cancelledAt.isAfter(startAt)) continue;
            LocalDate cancelDay = cancelledAt.atZone(zone).toLocalDate();
            if (hasFeeNear(feeDaysByCustomer.get(b.customerId()), day, cancelDay)) continue; // fee charged → skip
            Half half = halfOf(day);
            for (var seg : b.appointmentSegments()) {
                if (seg.teamMemberId() == null || b.customerId() == null
                        || seg.serviceVariationId() == null) continue;
                BigDecimal gross = catalogPrice.get(seg.serviceVariationId()); // nullable
                cancellations.add(new CancelledCandidate(
                        b.id(), b.customerId(), seg.teamMemberId(),
                        nameById.getOrDefault(seg.teamMemberId(), "?"),
                        seg.serviceVariationId(),
                        day, startAt, half, gross,
                        b.sellerNote(), b.customerNote()));
            }
        }

        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        log.info("aggregate({}, {}, source={}) took {}ms — {} bookings, {} orders, {} payments, {} matched services",
                year, month, source, elapsedMs, bookings.size(), orders.size(), payments.size(), services.size());
        return new MonthAggregation(year, month, zone.getId(), providers, diag, services,
                namedUnmatched, suspicious, cancellations, namedOrphanPayments);
    }

    /**
     * Completed payments in {@code year}/{@code month} with no linked Order the pipeline already
     * accounts for — either {@code orderId} is null (charged directly, no Order ever existed) or it
     * points at an Order outside this month's {@code knownOrderIds} (already handled through the
     * normal matched/unmatched path, so not double-listed here). For each, suggest the nearest booking
     * (same customer, within 2 days — the same tolerance the order matcher uses) as a starting point
     * for a human to confirm; never auto-attributed. Package-private (not private) for unit testing.
     */
    static List<OrphanPayment> detectOrphanPayments(List<Payment> payments,
            java.util.Set<String> knownOrderIds, Map<String, List<BookingHint>> bookingHintsByCustomer,
            Map<String, String> providerNameById, ZoneId zone, int year, int month) {
        List<OrphanPayment> out = new ArrayList<>();
        if (payments == null) return out;
        for (Payment p : payments) {
            if (p == null || !"COMPLETED".equals(p.status())) continue;
            if (p.orderId() != null && knownOrderIds.contains(p.orderId())) continue; // already accounted for
            LocalDate day = localDate(p.createdAt(), zone);
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;
            BigDecimal amount = SquareClient.toDollars(p.totalMoney());
            if (amount.signum() <= 0) continue;

            BookingHint best = null;
            long bestDist = Long.MAX_VALUE;
            if (p.customerId() != null) {
                List<BookingHint> hints = bookingHintsByCustomer.get(p.customerId());
                if (hints != null) {
                    for (BookingHint h : hints) {
                        long dist = Math.abs(h.day().toEpochDay() - day.toEpochDay());
                        if (dist <= 2 && dist < bestDist) { best = h; bestDist = dist; }
                    }
                }
            }
            String note = p.orderId() != null
                    ? "Linked to an order outside this month's paid orders (" + p.orderId() + ")"
                    : "No linked order — charged directly (e.g. card on file)";
            out.add(new OrphanPayment(str(day), amount, p.customerId(), null,
                    best == null ? null : best.providerId(),
                    best == null || best.providerId() == null ? null
                            : providerNameById.getOrDefault(best.providerId(), "?"),
                    best == null ? null : best.bookingId(), note));
        }
        return out;
    }

    /** Resolve customer names for orphan payments (one bulk Square call); best-effort. */
    private List<OrphanPayment> withOrphanCustomerNames(SquareClient square, List<OrphanPayment> payments) {
        if (payments.isEmpty()) return payments;
        Map<String, String> names;
        try {
            names = square.customerNames(payments.stream().map(OrphanPayment::customerId).toList());
        } catch (RuntimeException e) {
            return payments; // names are a nicety; don't fail the whole report if the lookup hiccups
        }
        List<OrphanPayment> out = new ArrayList<>(payments.size());
        for (OrphanPayment p : payments) {
            out.add(new OrphanPayment(p.date(), p.amount(), p.customerId(), names.get(p.customerId()),
                    p.suggestedProviderId(), p.suggestedProviderName(), p.suggestedBookingId(), p.note()));
        }
        return out;
    }

    /**
     * Find an already-detected unattributed sale that plausibly closes a cash-note's gap — same
     * customer, within 2 days of the note's day (the matching tolerance used elsewhere in this
     * class), and its amount equal to the gap to the cent. Exact-amount matching only — no fuzzy
     * tolerance — since the gap is itself computed from the note, a coincidental near-miss isn't
     * good enough evidence to reclassify real money. Picks the nearest day if more than one
     * candidate qualifies. Package-private (not private) for unit testing.
     */
    static UnmatchedLine findGapMatch(List<UnmatchedLine> unmatched, String customerId, LocalDate noteDay,
                                      BigDecimal gap) {
        UnmatchedLine best = null;
        long bestDist = Long.MAX_VALUE;
        for (UnmatchedLine u : unmatched) {
            if (!customerId.equals(u.customerId())) continue;
            if (u.gross().compareTo(gap) != 0) continue;
            LocalDate uDay;
            try {
                uDay = LocalDate.parse(u.date());
            } catch (RuntimeException e) {
                continue; // defensive — unmatched lines always carry a real date in practice
            }
            long dist = Math.abs(uDay.toEpochDay() - noteDay.toEpochDay());
            if (dist > 2) continue;
            if (dist < bestDist) { best = u; bestDist = dist; }
        }
        return best;
    }

    private static final Pattern INVOICE_KEYWORD = Pattern.compile("(?i)invoice");
    private static final Pattern DIGITS = Pattern.compile("(\\d{3,})");
    // How far from the word "invoice" to look for its number, in either direction — providers write
    // this both ways ("Invoice: 001365 ($100) paid" and "001821 invoice sent $100"), so unlike the
    // cash-amount windows above this isn't direction-anchored to one side.
    private static final int INVOICE_WINDOW = 20;

    /**
     * A deposit already collected via a real, PAID Square Invoice referenced by number in this cash
     * note's own text (e.g. {@code "Invoice: 001365 ($100) paid"} or {@code "001821 invoice sent
     * $100"}) — resolved against Square's own invoice record, never guessed from any other digit in
     * the note (see {@link CashNoteParser}'s own doc for the bug this avoided: an invoice number
     * misread as the cash amount itself).
     *
     * <p>Every number found near any "invoice" mention, on either side, is tried as a candidate
     * invoice number against Square's own record for this customer — not just the one number a
     * stricter pattern would have guessed. A false candidate (e.g. the deposit's own dollar amount,
     * sitting right next to "invoice" same as the real invoice number does) is harmless: it's an
     * exact-match lookup against this customer's real invoices, so nothing but the genuine invoice
     * number ever resolves to anything. Found live 2026-08-28 against real business-2 notes: a
     * forward-only, tightly-anchored pattern missed a real, correctly-PAID invoice just because the
     * provider happened to write the number before the keyword instead of after it.
     *
     * <p>Deliberately not gated behind {@code restrictDiscountCoverage} — unlike the discount-basis
     * reduction below, crediting a real, findable deposit as CARD instead of leaving it stuck in the
     * cash side (or, before this, in a permanently unmatched line from whichever earlier month it was
     * actually paid in) doesn't change the provider's total commission, only which channel it's
     * booked under — a correctness fix every business benefits from, not a commission policy choice.
     *
     * @return zero when the note has no invoice reference, none of the nearby numbers match a real
     *         invoice for this customer, or the match found isn't marked PAID.
     */
    private static BigDecimal linkedInvoiceAmount(CashBooking cb, SquareClient square,
                                                  Map<String, List<Invoice>> invoicesByCustomer) {
        if (cb.note() == null || cb.customerId() == null) return BigDecimal.ZERO;
        Matcher keyword = INVOICE_KEYWORD.matcher(cb.note());
        if (!keyword.find()) return BigDecimal.ZERO;
        List<Invoice> invoices = invoicesByCustomer.computeIfAbsent(cb.customerId(), square::invoicesForCustomer);
        if (invoices.isEmpty()) return BigDecimal.ZERO;

        keyword.reset();
        while (keyword.find()) {
            int from = Math.max(0, keyword.start() - INVOICE_WINDOW);
            int to = Math.min(cb.note().length(), keyword.end() + INVOICE_WINDOW);
            Matcher candidates = DIGITS.matcher(cb.note().substring(from, to));
            while (candidates.find()) {
                String number = candidates.group(1);
                for (Invoice inv : invoices) {
                    if (number.equals(inv.invoiceNumber()) && "PAID".equalsIgnoreCase(inv.status())) {
                        return inv.total();
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /** Resolve customer names for the unattributed lines (one bulk Square call); best-effort. */
    private List<UnmatchedLine> withCustomerNames(SquareClient square, List<UnmatchedLine> lines) {
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

    // --- Phase 2f: local-mirror -> SquareClient shape mappers ---
    //
    // Each mapper reconstructs the exact record shape #computeAggregateFrom already knows how to
    // read, so it can stay 100% oblivious to which source (live Square or the local mirror)
    // produced its input. Fields the mirror never stores because #computeAggregateFrom never reads
    // them (Order#locationId/#fulfillments, OrderLineItem#uid/#quantity/#basePriceMoney,
    // Tender#id) are filled with null/placeholder values — safe precisely because nothing
    // downstream looks at them; see the Phase 2 sync plan's own field-by-field audit for how that
    // was confirmed, not assumed.

    private static Booking mirrorToBooking(com.salonreview.domain.SquareBookingMirror m) {
        List<AppointmentSegment> segments = m.getAppointmentSegments() == null ? null
                : m.getAppointmentSegments().stream()
                        .map(s -> new AppointmentSegment(s.teamMemberId(), s.serviceVariationId(), s.durationMinutes()))
                        .toList();
        return new Booking(m.getSquareBookingId(), m.getStatus(),
                instantToIso(m.getStartAt()), instantToIso(m.getCreatedAt()), instantToIso(m.getUpdatedAt()),
                m.getLocationId(), m.getSquareCustomerId(), m.getSellerNote(), m.getCustomerNote(), segments);
    }

    private static Order mirrorToOrder(com.salonreview.domain.SquareOrderMirror m) {
        List<OrderLineItem> lineItems = m.getLineItems() == null ? null
                : m.getLineItems().stream().map(li -> new OrderLineItem(
                        null, li.name(), null, li.catalogObjectId(),
                        null, SquareClient.toMoney(li.grossSalesMoney()), SquareClient.toMoney(li.totalMoney()),
                        SquareClient.toMoney(li.totalDiscountMoney()),
                        li.appliedDiscounts() == null ? null : li.appliedDiscounts().stream()
                                .map(ad -> new AppliedDiscount(ad.uid(), ad.discountUid(), SquareClient.toMoney(ad.appliedMoney())))
                                .toList()))
                        .toList();
        List<Tender> tenders = m.getTenders() == null ? null
                : m.getTenders().stream().map(t -> new Tender(null, t.type(), SquareClient.toMoney(t.amount()))).toList();
        List<OrderDiscount> discounts = m.getDiscounts() == null ? null
                : m.getDiscounts().stream()
                        .map(d -> new OrderDiscount(d.uid(), d.name(), SquareClient.toMoney(d.appliedMoney())))
                        .toList();
        return new Order(m.getSquareOrderId(), null, m.getSquareCustomerId(), m.getState(),
                instantToIso(m.getClosedAt()), instantToIso(m.getCreatedAt()), lineItems,
                SquareClient.toMoney(m.getTotalTipMoney()), SquareClient.toMoney(m.getTotalDiscountMoney()),
                tenders, null, discounts);
    }

    private static Payment mirrorToPayment(com.salonreview.domain.SquarePaymentMirror m) {
        return new Payment(m.getSquarePaymentId(), m.getSquareOrderId(), m.getSquareCustomerId(), m.getStatus(),
                instantToIso(m.getCreatedAt()), SquareClient.toMoney(m.getTotalMoney()), SquareClient.toMoney(m.getTipMoney()));
    }

    private static String instantToIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // --- helpers ---

    private ZoneId resolveZone(SquareClient square) {
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

    /**
     * True if the customer has any completed order within {@code maxDays} of the appointment day — i.e.
     * the visit has a payment trail even when the strict line matcher couldn't tie the exact SKU to this
     * booking. Uses the same 2-day tolerance as the payout matcher (a normal same-visit checkout).
     */
    private static boolean hasPaymentWithinDays(List<LocalDate> orderDays, LocalDate day, long maxDays) {
        if (orderDays == null) return false;
        for (LocalDate od : orderDays) {
            if (Math.abs(od.toEpochDay() - day.toEpochDay()) <= maxDays) return true;
        }
        return false;
    }

    /**
     * True if a cancellation fee for this customer falls in the window around the cancellation — from 2
     * days before the appointment to 2 days after it was cancelled — i.e. we charged a fee for this
     * cancelled visit, so it's accounted for and shouldn't be flagged for review.
     */
    private static boolean hasFeeNear(List<LocalDate> feeDays, LocalDate apptDay, LocalDate cancelDay) {
        if (feeDays == null) return false;
        long lo = apptDay.toEpochDay() - 2;
        long hi = cancelDay.toEpochDay() + 2;
        for (LocalDate fd : feeDays) {
            long e = fd.toEpochDay();
            if (e >= lo && e <= hi) return true;
        }
        return false;
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
                               LocalDate orderDay, Instant checkoutAt, String preferredBookingId, Diag diag) {
        if (customerId == null || orderDay == null) return null;
        List<Seg> candidates = index.get(key(customerId, catalogObjectId));
        if (candidates == null) return null;

        Seg near = nearestUnused(candidates, orderDay, checkoutAt, 2, preferredBookingId);
        if (near != null) {
            near.used = true;
            return new Match(near, false);
        }
        return null;
    }

    /** A matched booking segment plus whether it was a prepaid-invoice match (reserved; always false now). */
    private record Match(Seg seg, boolean prepaid) {}

    /**
     * The single booking that best explains an entire order — the one whose segments cover the most of
     * the order's distinct catalog-object ids — or {@code null} when no one booking clearly dominates.
     *
     * <p>Real checkouts almost always trace back to exactly one booking: a customer's own booking can
     * share a SKU with a sibling booking for the same customer on the same day (e.g. a leftover stub
     * from a "4-hands" request that got split into two single-provider visits), and per-line matching
     * alone can then split a single ticket's revenue across two different providers by pure chance of
     * which sibling's {@code updated_at} happens to land closer to the checkout moment — both bookings
     * are routinely touched within the same second by whatever edit created the ticket, so that skew
     * tie-break isn't a reliable signal on its own. Precomputing the one booking that covers the most
     * (ideally all) of the order's lines and preferring it in {@link #nearestUnused} keeps a real
     * single-provider checkout attributed to one provider. A genuine multi-provider order (an actual
     * paid 4-hands ticket) has no single dominating booking, so this returns {@code null} and per-line
     * matching proceeds exactly as before.
     */
    private static String preferredBooking(Map<String, List<Seg>> index, Order o, LocalDate orderDay) {
        if (o.customerId() == null || o.lineItems() == null || orderDay == null) return null;
        java.util.Set<String> svids = new java.util.LinkedHashSet<>();
        for (OrderLineItem li : o.lineItems()) {
            if (li.catalogObjectId() != null) svids.add(li.catalogObjectId());
        }
        if (svids.size() < 2) return null; // nothing to disambiguate with a single line item
        Map<String, Integer> coverage = new HashMap<>();
        for (String svid : svids) {
            List<Seg> candidates = index.get(key(o.customerId(), svid));
            if (candidates == null) continue;
            java.util.Set<String> bookingsForThisSvid = new java.util.HashSet<>();
            for (Seg s : candidates) {
                if (s.used || s.bookingId == null) continue;
                if (Math.abs(s.day.toEpochDay() - orderDay.toEpochDay()) > 2) continue;
                bookingsForThisSvid.add(s.bookingId);
            }
            for (String bookingId : bookingsForThisSvid) coverage.merge(bookingId, 1, Integer::sum);
        }
        if (coverage.isEmpty()) return null;
        int max = java.util.Collections.max(coverage.values());
        if (max < 2) return null; // no single booking covers more than one line — nothing to prefer
        List<String> best = coverage.entrySet().stream().filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey).toList();
        return best.size() == 1 ? best.get(0) : null; // still tied — let normal matching decide
    }

    /**
     * The booking this checkout paid for, or null if none is within {@code maxDays}.
     *
     * <p>{@code preferredBookingId} (see {@link #preferredBooking}) wins outright when present among
     * the in-window candidates — it identifies the one booking that explains the whole order, which is
     * a stronger signal than any single line's own tie-break. Otherwise, day proximity is the primary
     * signal (a checkout matches the visit's day; the small window absorbs timezone jitter), and ties
     * are broken by <em>checkout skew</em>: Square stamps the booking that was actually checked out
     * with an {@code updated_at} at the checkout moment, so the booking whose {@code updatedAt} is
     * closest to the order's close time is the one that took payment.
     */
    static Seg nearestUnused(List<Seg> candidates, LocalDate orderDay, Instant checkoutAt, long maxDays) {
        return nearestUnused(candidates, orderDay, checkoutAt, maxDays, null);
    }

    static Seg nearestUnused(List<Seg> candidates, LocalDate orderDay, Instant checkoutAt, long maxDays,
                             String preferredBookingId) {
        Seg best = null;
        long bestDist = Long.MAX_VALUE;
        long bestSkew = Long.MAX_VALUE;
        for (Seg s : candidates) {
            if (s.used) continue;
            long dist = Math.abs(s.day.toEpochDay() - orderDay.toEpochDay());
            if (dist > maxDays) continue;
            if (preferredBookingId != null && preferredBookingId.equals(s.bookingId)) return s;
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
        return SquareClient.isCashOrder(o); // single source of truth — shared with the revenue pulse
    }

    /** Price used for the tier cutoff: catalog list price, falling back to the charged line amount. */
    private static BigDecimal servicePrice(OrderLineItem li, Map<String, BigDecimal> catalogPrice) {
        BigDecimal p = catalogPrice.get(li.catalogObjectId());
        if (p != null) return p;
        return lineRevenue(li);
    }

    /** A trace line created for an order, with its position + provider + gross, for spreading the tip. */
    private record LineRef(int index, String provider, BigDecimal gross) {}

    /**
     * Spread one provider's order-tip {@code share} across their line(s) on that order, proportional to
     * gross (the remainder lands on the last line so the rows sum exactly to the share). With the common
     * single-line ticket this just puts the whole share on that line.
     */
    private static void allocateTip(List<AttributedService> services, List<LineRef> lines, BigDecimal share) {
        if (lines.isEmpty()) return;
        BigDecimal totalGross = lines.stream().map(LineRef::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            LineRef r = lines.get(i);
            BigDecimal t;
            if (i == lines.size() - 1) {
                t = share.subtract(allocated);                 // remainder → exact sum
            } else if (totalGross.signum() > 0) {
                t = share.multiply(r.gross()).divide(totalGross, 2, RoundingMode.HALF_UP);
            } else {
                t = share.divide(BigDecimal.valueOf(lines.size()), 2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(t);
            services.set(r.index(), services.get(r.index()).withTip(t));
        }
    }

    /** Full menu (gross) price of a line, before Square discounts; falls back to the net total. */
    private static BigDecimal lineRevenue(OrderLineItem li) {
        if (li.grossSalesMoney() != null) return SquareClient.toDollars(li.grossSalesMoney());
        return SquareClient.toDollars(li.totalMoney());
    }

    /** This line item's own share of any order-level discount whose name contains one of {@code
     * coveredNameSubstrings} (case-insensitive) — only called when restrictDiscountCoverage is on.
     * Same matching approach PrepaidService used to use for its own now-reverted deposit-credit
     * logic (see its git history) — generalized here to an owner-configurable name list instead of
     * a hardcoded "deposit" substring. */
    private static BigDecimal coveredDiscountOn(Order order, OrderLineItem lineItem, java.util.Set<String> coveredNameSubstrings) {
        if (lineItem.appliedDiscounts() == null || order.discounts() == null || coveredNameSubstrings.isEmpty()) {
            return BigDecimal.ZERO;
        }
        java.util.Set<String> coveredUids = order.discounts().stream()
                .filter(d -> d.name() != null && coveredNameSubstrings.stream()
                        .anyMatch(n -> d.name().toLowerCase(java.util.Locale.ROOT).contains(n)))
                .map(SquareClient.OrderDiscount::uid)
                .collect(java.util.stream.Collectors.toSet());
        if (coveredUids.isEmpty()) return BigDecimal.ZERO;
        return lineItem.appliedDiscounts().stream()
                .filter(ad -> coveredUids.contains(ad.discountUid()))
                .map(ad -> SquareClient.toDollars(ad.appliedMoney()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
                               String customerId, String note) {}

    /** A booking segment for an owner/family customer — a candidate owner comp (credited if unpaid). */
    private record CompCandidate(String providerId, LocalDate day, String serviceVariationId,
                                 String bookingId, String customerId, String startAt) {}

    /** A loose "this customer had an appointment around here" hint for orphan-payment suggestions —
     * unlike {@link Seg}, doesn't require a resolved service, so it covers every booking.
     * Package-private (not private) so {@link #detectOrphanPayments} is unit-testable. */
    record BookingHint(String bookingId, String providerId, LocalDate day) {}

    // --- result types ---

    public record MonthAggregation(int year, int month, String timezone,
                                   List<ProviderMonth> providers, Diag diagnostics,
                                   List<AttributedService> services, List<UnmatchedLine> unmatched,
                                   List<SuspiciousCandidate> suspicious,
                                   List<CancelledCandidate> cancellations,
                                   List<OrphanPayment> orphanPayments) {
        /** Back-compat constructor for callers (and tests) that predate the orphan-payments list. */
        public MonthAggregation(int year, int month, String timezone, List<ProviderMonth> providers,
                                Diag diagnostics, List<AttributedService> services,
                                List<UnmatchedLine> unmatched, List<SuspiciousCandidate> suspicious,
                                List<CancelledCandidate> cancellations) {
            this(year, month, timezone, providers, diagnostics, services, unmatched, suspicious,
                    cancellations, List.of());
        }

        /** Back-compat constructor for callers (and tests) that predate the cancellations list. */
        public MonthAggregation(int year, int month, String timezone, List<ProviderMonth> providers,
                                Diag diagnostics, List<AttributedService> services,
                                List<UnmatchedLine> unmatched, List<SuspiciousCandidate> suspicious) {
            this(year, month, timezone, providers, diagnostics, services, unmatched, suspicious, List.of());
        }
    }

    /**
     * A past appointment that produced no order, has no cash note, and isn't an owner comp — i.e.,
     * an appointment that happened with no money trail. One candidate per booking-segment with all of
     * provider, customer, and service set. {@code gross} may be null when the catalog lookup didn't
     * resolve the variation's price. {@code sellerNote}/{@code customerNote} are passed through as-is
     * so the review page can show whatever context the salon/customer wrote on the appointment.
     */
    public record SuspiciousCandidate(String bookingId, String customerId,
                                      String providerId, String providerName,
                                      String serviceVariationId,
                                      LocalDate day, Instant startAt, Half half,
                                      BigDecimal gross,
                                      String sellerNote, String customerNote) {}

    /**
     * A past appointment the salon marked CANCELLED_BY_SELLER. Emitted one per booking-segment with
     * provider + customer + service set, so the owner-review page can show the service(s) and the
     * assigned provider. {@code providerId} is the Square team member; role filtering (excluding
     * owner/manager staff) is applied later in the service layer, which knows app roles.
     */
    public record CancelledCandidate(String bookingId, String customerId,
                                     String providerId, String providerName,
                                     String serviceVariationId,
                                     LocalDate day, Instant startAt, Half half,
                                     BigDecimal gross,
                                     String sellerNote, String customerNote) {}

    public record AttributedService(String providerId, String providerName, String date, String half,
                                    String service, BigDecimal gross, BigDecimal discount, BigDecimal net,
                                    BigDecimal tip,
                                    boolean counted, int countedUnits, int units, boolean prepaid, String channel,
                                    String time, String bookingId, String customerId, String customer) {
        /** A copy with the (short) customer name filled in — set by the detail service after lookup. */
        public AttributedService withCustomer(String c) {
            return new AttributedService(providerId, providerName, date, half, service, gross, discount, net,
                    tip, counted, countedUnits, units, prepaid, channel, time, bookingId, customerId, c);
        }

        /** A copy with this line's share of the transaction tip filled in (set after the tip split). */
        public AttributedService withTip(BigDecimal t) {
            return new AttributedService(providerId, providerName, date, half, service, gross, discount, net,
                    t, counted, countedUnits, units, prepaid, channel, time, bookingId, customerId, customer);
        }
    }

    public record UnmatchedLine(String date, String service, BigDecimal gross, String channel,
                                String customerId, String customerName) {}

    /**
     * A completed Square payment with no linked Order the reconciliation pipeline ever sees — e.g. a
     * card charged directly against a customer's card on file, bypassing the booking checkout. Unlike
     * {@link UnmatchedLine} (a paid order line that couldn't be tied to a booking), there is no order
     * here at all, so there's no service/catalog line to attribute — only a best-guess suggestion from
     * the nearest booking for the same customer. Never included in revenue/commission automatically;
     * surfaced for the owner/manager to confirm via a Manual Adjustment.
     */
    public record OrphanPayment(String date, BigDecimal amount, String customerId, String customerName,
                                String suggestedProviderId, String suggestedProviderName,
                                String suggestedBookingId, String note) {}

    /** What was actually collected for one booking, and how — CASH (checked out as cash in
     * Square), CARD, or CASH-NOTE (a provider's note, no Square checkout); COMP bookings are
     * excluded by {@link #paymentsByBookingId} since nothing was actually collected for them. */
    public record BookingPayment(String channel, BigDecimal collected, BigDecimal gross) {}

    /** Collapses a month's matched {@link AttributedService} lines to one payment summary per
     * booking — a multi-service appointment (e.g. mani + pedi on one ticket) is several lines here
     * but one booking, and the marketing Contacts/Analytics tabs want "what did this appointment
     * collect", not a per-line breakdown. All lines for a booking share the same channel (they come
     * from the same order or the same cash note), so the first line's channel is used.
     */
    public static Map<String, BookingPayment> paymentsByBookingId(List<AttributedService> services) {
        Map<String, List<AttributedService>> byBooking = services.stream()
                .filter(s -> s.bookingId() != null && !"COMP".equals(s.channel()))
                .collect(java.util.stream.Collectors.groupingBy(AttributedService::bookingId));
        Map<String, BookingPayment> out = new HashMap<>();
        for (var e : byBooking.entrySet()) {
            BigDecimal collected = e.getValue().stream().map(AttributedService::net).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gross = e.getValue().stream().map(AttributedService::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.put(e.getKey(), new BookingPayment(e.getValue().get(0).channel(), collected, gross));
        }
        return out;
    }

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
        public int orphanPayments = 0;              // completed payments with no linked Order at all
        public BigDecimal orphanPaymentRevenue = BigDecimal.ZERO;
        public int cashNoteAmountCapped = 0; // note's written amount exceeded the catalog price (likely typo)
        public int cashNoteGapMatches = 0;   // cash-note gaps auto-resolved against an unattributed sale

        public int getOrders() { return orders; }
        public int getMatchedLineItems() { return matchedLineItems; }
        public int getPrepaidMatches() { return prepaidMatches; }
        public int getUnmatchedLineItems() { return unmatchedLineItems; }
        public BigDecimal getUnmatchedRevenue() { return unmatchedRevenue; }
        public int getCashNotes() { return cashNotes; }
        public int getCashNotesSkipped() { return cashNotesSkipped; }
        public int getOwnerComps() { return ownerComps; }
        public int getOwnerCompsSkipped() { return ownerCompsSkipped; }
        public int getOrphanPayments() { return orphanPayments; }
        public BigDecimal getOrphanPaymentRevenue() { return orphanPaymentRevenue; }
        public int getCashNoteAmountCapped() { return cashNoteAmountCapped; }
        public int getCashNoteGapMatches() { return cashNoteGapMatches; }
    }
}
