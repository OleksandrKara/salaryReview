package com.salonreview.marketing;

import com.salonreview.domain.AdSpend;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.AdSpendRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import com.salonreview.web.dto.MarketingAnalyticsDto.Segment;
import com.salonreview.web.dto.MarketingAnalyticsDto.UpcomingAppointment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
public class MarketingAnalyticsService {

    /** Every recognized traffic-source bucket — see {@link TrafficSourceSql}. Selecting exactly
     * this set ("All traffic") is what makes {@link #resolveAdsCustomers} pull every contact
     * unfiltered, rather than a SQL-level classification check. */
    public static final Set<String> ALL_SOURCES = TrafficSourceSql.ALL;

    private static final Segment EMPTY_SEGMENT =
            new Segment(0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    // A genuinely new customer's Square record can only be created at/after the moment the ad funnel
    // first captured them; a small grace window absorbs clock/event-ordering slack rather than
    // requiring an exact instant-for-instant comparison.
    private static final Duration FRESHNESS_GRACE = Duration.ofDays(1);

    // How far back to search Square for a customer's prior bookings when deciding fresh vs.
    // returning. SquareClient#bookingsForCustomer requires an explicit lower bound (Square's API
    // silently excludes everything before it), so an unbounded "their whole history" lookup isn't
    // free — this caps it at a generous window rather than scanning years back on every check. A
    // customer whose last visit predates this window is treated the same as one with no prior
    // booking at all (falls back to created_at) — an acceptable trade-off since a return visit
    // that far back reasonably counts as a fresh re-acquisition anyway.
    private static final Duration BOOKING_HISTORY_LOOKBACK = Duration.ofDays(400);

    private final MarketingContactsRepository contactsRepository;
    private final SquareMonthAggregator aggregator;
    private final SquareClient square;
    private final SalonConfigRepository salonConfig;
    private final AdSpendRepository adSpendRepository;
    private final java.time.Clock clock;

    @Autowired
    public MarketingAnalyticsService(
            MarketingContactsRepository contactsRepository,
            SquareMonthAggregator aggregator,
            SquareClient square,
            SalonConfigRepository salonConfig,
            AdSpendRepository adSpendRepository
    ) {
        this(contactsRepository, aggregator, square, salonConfig, adSpendRepository, java.time.Clock.systemUTC());
    }

    /** Test-only constructor — lets tests fix "today" instead of racing the real clock for the
     * current-month-to-date segment and ad spend lookup. */
    MarketingAnalyticsService(
            MarketingContactsRepository contactsRepository,
            SquareMonthAggregator aggregator,
            SquareClient square,
            SalonConfigRepository salonConfig,
            AdSpendRepository adSpendRepository,
            java.time.Clock clock
    ) {
        this.contactsRepository = contactsRepository;
        this.aggregator = aggregator;
        this.square = square;
        this.salonConfig = salonConfig;
        this.clock = clock;
        this.adSpendRepository = adSpendRepository;
    }

    /** A resolved ads-attributed Square customer id: the earliest moment our own ad funnel captured
     * this person, and which channel. Several Square customer ids can map back to the same contact
     * (see resolveAdsCustomers) — each still carries this contact's firstTouch/channel.
     */
    private record AdsCustomer(Instant firstTouch, String channel) {}

    /** Gross revenue, customer count, and service count for ads-attributed customers with a service
     * rendered in [from, to] inclusive, split into all / fresh-to-Square / already-existing segments
     * — plus every still-upcoming appointment for an ads-attributed customer (not bound to [from, to];
     * "what's coming" is inherently forward-looking) and the current month's ad spend/ROI inputs
     * (always the current calendar month, independent of [from, to]).
     */
    public MarketingAnalyticsDto analytics(LocalDate from, LocalDate to, Set<String> sources) {
        return analytics(from, to, sources, null);
    }

    /** Same as the 3-arg overload, optionally scoped to a single landing page slug (e.g. "home") —
     * {@code slug == null} pools every page together, identical to the original behavior. */
    public MarketingAnalyticsDto analytics(LocalDate from, LocalDate to, Set<String> sources, String slug) {
        Map<String, AdsCustomer> adsCustomers = resolveAdsCustomers(sources, slug);
        BigDecimal adSpend = currentAdSpend();
        if (adsCustomers.isEmpty()) {
            return new MarketingAnalyticsDto(
                    from, to, EMPTY_SEGMENT, EMPTY_SEGMENT, EMPTY_SEGMENT, List.of(), EMPTY_SEGMENT, adSpend);
        }

        Set<String> freshCustomerIds = freshCustomerIds(adsCustomers);
        BigDecimal cutoff = priceCutoff();

        List<AttributedService> inRange = collectServices(adsCustomers.keySet(), from, to, cutoff);
        Segment all = segment(inRange, id -> true);
        Segment fresh = segment(inRange, freshCustomerIds::contains);
        Segment returning = segment(inRange, id -> !freshCustomerIds.contains(id));

        LocalDate today = LocalDate.now(clock);
        List<AttributedService> monthToDate =
                collectServices(adsCustomers.keySet(), today.withDayOfMonth(1), today, cutoff);
        Segment currentMonthToDate = segment(monthToDate, id -> true);

        // Already-paid bookings are excluded from "upcoming" below — this month's aggregation already
        // tags each paid service with the booking that produced it, so this is free (no extra Square call).
        Set<String> paidBookingIds = monthToDate.stream()
                .map(AttributedService::bookingId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<UpcomingAppointment> upcoming =
                upcomingAppointments(adsCustomers.keySet(), freshCustomerIds, paidBookingIds, today);

        return new MarketingAnalyticsDto(from, to, all, fresh, returning, upcoming, currentMonthToDate, adSpend);
    }

    /** Save (upsert) this month's ad spend figure. */
    @Transactional
    public BigDecimal saveAdSpend(int year, int month, BigDecimal amount, String updatedBy) {
        AdSpend row = adSpendRepository.findByYearAndMonth(year, month)
                .orElseGet(() -> AdSpend.builder().year(year).month(month).build());
        row.setAmountSpent(amount.setScale(2, RoundingMode.HALF_UP));
        row.setUpdatedBy(updatedBy);
        adSpendRepository.save(row);
        return row.getAmountSpent();
    }

    private BigDecimal currentAdSpend() {
        LocalDate today = LocalDate.now(clock);
        return adSpendRepository.findByYearAndMonth(today.getYear(), today.getMonthValue())
                .map(AdSpend::getAmountSpent)
                .orElse(ZERO_MONEY);
    }

    /** Every Square customer id a channel-attributed contact resolves to, each tagged with that
     * contact's first touch and channel. A contact's originally-linked square_customer_id can go
     * stale (e.g. a follow-up appointment booked by phone gets matched or created against a
     * *different* Square profile for the same person), so every contact is also looked up by phone —
     * the union of both is what "belongs" to that contact. On the rare id collision between two
     * different contacts, the earlier firstTouch wins (the more conservative "first touch" reading).
     *
     * <p>sources equal to the full {@link #ALL_SOURCES} set ("All traffic") pulls every contact
     * unfiltered — including the rare edge case whose channel classifies to none of the five
     * buckets — via findAllAttributedContacts, the same "byte-for-byte the pre-filter query"
     * guarantee used by the Overview/Funnel repositories. Any narrower selection filters in SQL via
     * findAdsAttributedContacts(sources, slug), which only ever returns contacts already known to
     * classify into one of the requested buckets.
     */
    private Map<String, AdsCustomer> resolveAdsCustomers(Set<String> sources, String slug) {
        List<MarketingContactsRepository.AdsAttributedContact> contacts = sources.equals(ALL_SOURCES)
                ? contactsRepository.findAllAttributedContacts(slug)
                // slug == null calls the exact one-arg overload (not findAdsAttributedContacts(sources, null))
                // so the unscoped default path is untouched, including at the test-mock level.
                : (slug == null ? contactsRepository.findAdsAttributedContacts(sources) : contactsRepository.findAdsAttributedContacts(sources, slug));

        record Resolved(String customerId, AdsCustomer meta) {}
        List<Resolved> resolved = contacts.parallelStream()
                .flatMap(c -> {
                    Set<String> candidateIds = new LinkedHashSet<>();
                    if (c.squareCustomerId() != null && !c.squareCustomerId().isBlank()) {
                        candidateIds.add(c.squareCustomerId());
                    }
                    candidateIds.addAll(square.customerIdsForPhone(c.phoneNumber()));
                    AdsCustomer meta = new AdsCustomer(c.firstTouch(), c.channel());
                    return candidateIds.stream().map(id -> new Resolved(id, meta));
                })
                .toList();

        Map<String, AdsCustomer> byCustomerId = new LinkedHashMap<>();
        for (Resolved r : resolved) {
            byCustomerId.merge(r.customerId(), r.meta(),
                    (existing, incoming) -> existing.firstTouch().isBefore(incoming.firstTouch()) ? existing : incoming);
        }
        return byCustomerId;
    }

    /** A customer is "fresh" when nothing in Square shows them existing before the first moment the ad
     * funnel captured them (minus a small grace window) — the ad brought in a genuinely new customer
     * rather than winning back an existing one.
     *
     * <p>The primary signal is their earliest known booking (bookingsForCustomer returns full history,
     * not just this month) — not Square's own {@code created_at}, which a merge can silently rewrite to
     * an earlier date than when this specific customer relationship actually began. A real case: a
     * customer's contact was captured by an ad, but Square later merged her profile with a second,
     * separately-created one for the same person (e.g. from a front-desk phone booking) — the surviving
     * record's created_at ended up predating her actual first ad touch, even though she had never been a
     * Square customer before that touch. Her booking history has no such artifact: her earliest
     * appointment is still exactly when it happened. created_at is used only as a fallback when there's
     * no booking history at all to check (rare for anyone who shows up in analytics in the first place).
     * Genuinely unresolvable cases (no bookings, no creation date) are treated conservatively as
     * "returning" — we'd rather undercount a fresh win than overclaim one we can't verify.
     */
    private Set<String> freshCustomerIds(Map<String, AdsCustomer> adsCustomers) {
        Map<String, Instant> createdAtByCustomer = square.customerCreatedAts(adsCustomers.keySet());
        return adsCustomers.entrySet().parallelStream()
                .filter(e -> isFresh(e.getKey(), e.getValue().firstTouch(), createdAtByCustomer))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean isFresh(String customerId, Instant firstTouch, Map<String, Instant> createdAtByCustomer) {
        Instant cutoff = firstTouch.minus(FRESHNESS_GRACE);
        Instant earliestBooking = earliestBookingStart(customerId);
        if (earliestBooking != null) return !earliestBooking.isBefore(cutoff);
        Instant createdAt = createdAtByCustomer.get(customerId);
        return createdAt != null && !createdAt.isBefore(cutoff);
    }

    /** The start of this customer's earliest known booking (any status, past or future) — bookings
     * carry real transaction/appointment history unaffected by a later profile merge, unlike Square's
     * own created_at. Null if they have no bookings at all.
     */
    private Instant earliestBookingStart(String customerId) {
        return square.bookingsForCustomer(customerId, clock.instant().minus(BOOKING_HISTORY_LOOKBACK)).stream()
                .map(SquareClient.Booking::startAt)
                .map(MarketingAnalyticsService::parseInstantOrNull)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static Instant parseInstantOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private List<AttributedService> collectServices(
            Set<String> customerIds, LocalDate from, LocalDate to, BigDecimal cutoff) {
        List<AttributedService> inRange = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
            SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(ym.getYear(), ym.getMonthValue(), cutoff);
            for (AttributedService s : agg.services()) {
                if (!customerIds.contains(s.customerId())) continue;
                LocalDate day = parseIso(s.date());
                if (day == null || day.isBefore(from) || day.isAfter(to)) continue;
                inRange.add(s);
            }
        }
        return inRange;
    }

    private static Segment segment(List<AttributedService> services, Predicate<String> customerFilter) {
        List<AttributedService> matched = services.stream()
                .filter(s -> customerFilter.test(s.customerId())).toList();
        long customerCount = matched.stream().map(AttributedService::customerId).distinct().count();
        BigDecimal gross = matched.stream().map(AttributedService::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new Segment(customerCount, matched.size(), gross);
    }

    /** Every non-cancelled, not-yet-paid appointment for an ads-attributed customer dated today or
     * later, regardless of the requested [from, to] range. "Today or later" (by date, not exact
     * instant) rather than strictly still-in-the-future — an appointment scheduled for later today
     * whose start time has already ticked past is still unpaid and still exactly what this section is
     * for; excluding it the moment the clock passes its start time would drop it into a gap between
     * "upcoming" and "counted revenue" until someone rings it up, hours or a day later. Already-paid
     * bookings (paidBookingIds, from this month's aggregation) are excluded so a since-checked-out visit
     * doesn't double up here and in the revenue segments above. One row per booking; a multi-service
     * visit's segments are joined into one service name and one summed (menu list price) total, since
     * "an upcoming visit" — not "a line item" — is what the owner wants to see in this list.
     */
    private List<UpcomingAppointment> upcomingAppointments(
            Set<String> adsCustomerIds, Set<String> freshCustomerIds, Set<String> paidBookingIds, LocalDate today) {
        record FutureBooking(String customerId, SquareClient.Booking booking) {}
        // A day of slack behind "today" is enough here (isTodayOrLater does the exact date
        // filtering below); no need for the wider BOOKING_HISTORY_LOOKBACK this call doesn't need.
        Instant sinceYesterday = clock.instant().minus(Duration.ofDays(1));
        List<FutureBooking> future = adsCustomerIds.parallelStream()
                .flatMap(id -> square.bookingsForCustomer(id, sinceYesterday).stream()
                        .filter(MarketingAnalyticsService::didHappen)
                        .filter(b -> b.id() == null || !paidBookingIds.contains(b.id()))
                        .filter(b -> isTodayOrLater(b.startAt(), today))
                        .map(b -> new FutureBooking(id, b)))
                .toList();
        if (future.isEmpty()) return List.of();

        List<String> variationIds = future.stream()
                .flatMap(f -> f.booking().appointmentSegments() == null ? Stream.<String>empty()
                        : f.booking().appointmentSegments().stream()
                                .map(SquareClient.AppointmentSegment::serviceVariationId))
                .filter(Objects::nonNull)
                .toList();
        Map<String, BigDecimal> prices = square.catalogPrices(variationIds);
        Map<String, String> serviceNames = square.catalogNames(variationIds);
        Map<String, String> customerNames = square.customerNames(adsCustomerIds);

        List<UpcomingAppointment> result = new ArrayList<>();
        for (FutureBooking f : future) {
            var segs = f.booking().appointmentSegments();
            if (segs == null || segs.isEmpty()) continue;
            BigDecimal price = BigDecimal.ZERO;
            List<String> names = new ArrayList<>();
            for (var seg : segs) {
                price = price.add(prices.getOrDefault(seg.serviceVariationId(), BigDecimal.ZERO));
                String name = serviceNames.get(seg.serviceVariationId());
                if (name != null) names.add(name);
            }
            result.add(new UpcomingAppointment(
                    f.customerId(),
                    customerNames.getOrDefault(f.customerId(), "Customer"),
                    names.isEmpty() ? "Service" : String.join(" + ", names),
                    Instant.parse(f.booking().startAt()),
                    price.setScale(2, RoundingMode.HALF_UP),
                    freshCustomerIds.contains(f.customerId())
            ));
        }
        result.sort(Comparator.comparing(UpcomingAppointment::startAt));
        return result;
    }

    private static boolean didHappen(SquareClient.Booking b) {
        String status = b.status();
        if (status == null) return true;
        return switch (status) {
            case "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW" -> false;
            default -> true;
        };
    }

    private static boolean isTodayOrLater(String startAt, LocalDate today) {
        if (startAt == null || startAt.isBlank()) return false;
        try {
            LocalDate day = Instant.parse(startAt).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            return !day.isBefore(today);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private BigDecimal priceCutoff() {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        return cfg.getServicePriceCutoff();
    }

    private static LocalDate parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
