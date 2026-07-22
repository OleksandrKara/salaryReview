package com.salonreview.marketing;

import com.salonreview.domain.AdSpendEntry;
import com.salonreview.domain.SalonConfig;
import com.salonreview.marketing.MarketingContactsService.FollowUpAppointment;
import com.salonreview.repo.AdSpendEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.web.dto.MarketingAdsReportDto;
import com.salonreview.web.dto.MarketingAdsReportDto.PeriodRow;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import com.salonreview.web.dto.MarketingAnalyticsDto.CompletedAppointment;
import com.salonreview.web.dto.MarketingAnalyticsDto.Segment;
import com.salonreview.web.dto.MarketingAnalyticsDto.UpcomingAppointment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final Logger log = LoggerFactory.getLogger(MarketingAnalyticsService.class);

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
    private final MarketingContactsService contactsService;
    private final MarketingDashboardRepository dashboardRepository;
    private final SquareMonthAggregator aggregator;
    private final SquareClient square;
    private final SalonConfigRepository salonConfig;
    private final AdSpendEntryRepository adSpendEntryRepository;
    private final java.time.Clock clock;

    @Autowired
    public MarketingAnalyticsService(
            MarketingContactsRepository contactsRepository,
            MarketingContactsService contactsService,
            MarketingDashboardRepository dashboardRepository,
            SquareMonthAggregator aggregator,
            SquareClient square,
            SalonConfigRepository salonConfig,
            AdSpendEntryRepository adSpendEntryRepository
    ) {
        this(contactsRepository, contactsService, dashboardRepository, aggregator, square, salonConfig,
                adSpendEntryRepository, java.time.Clock.systemUTC());
    }

    /** Test-only constructor — lets tests fix "today" instead of racing the real clock for the
     * current-month-to-date segment and ad spend lookup. */
    MarketingAnalyticsService(
            MarketingContactsRepository contactsRepository,
            MarketingContactsService contactsService,
            MarketingDashboardRepository dashboardRepository,
            SquareMonthAggregator aggregator,
            SquareClient square,
            SalonConfigRepository salonConfig,
            AdSpendEntryRepository adSpendEntryRepository,
            java.time.Clock clock
    ) {
        this.contactsRepository = contactsRepository;
        this.contactsService = contactsService;
        this.dashboardRepository = dashboardRepository;
        this.aggregator = aggregator;
        this.square = square;
        this.salonConfig = salonConfig;
        this.clock = clock;
        this.adSpendEntryRepository = adSpendEntryRepository;
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
        LocalDate today = LocalDate.now(clock);
        // Ad spend is now tracked per landing page (see AdSpendEntry) — with no single page
        // selected there's nothing unambiguous to show, so this pooled-pages case reports zero
        // rather than guessing which page's spend to surface. Analytics itself is being retired
        // in favor of Ads Report's page-scoped drill-down (see openspec/changes/
        // ads-report-consolidation), where a slug is always present.
        BigDecimal adSpend = slug == null ? ZERO_MONEY : resolveSpend(slug, today.withDayOfMonth(1), today).amount();
        if (adsCustomers.isEmpty()) {
            return new MarketingAnalyticsDto(
                    from, to, EMPTY_SEGMENT, EMPTY_SEGMENT, EMPTY_SEGMENT, List.of(), List.of(), EMPTY_SEGMENT, adSpend);
        }

        Map<String, List<SquareClient.Booking>> bookingHistory = bookingHistoryByCustomer(adsCustomers.keySet());
        Set<String> freshCustomerIds = freshCustomerIds(adsCustomers, bookingHistory);
        BigDecimal cutoff = priceCutoff();

        List<AttributedService> inRange = collectServices(adsCustomers.keySet(), from, to, cutoff);
        Segment all = segment(inRange, id -> true);
        Segment fresh = segment(inRange, freshCustomerIds::contains);
        Segment returning = segment(inRange, id -> !freshCustomerIds.contains(id));
        List<CompletedAppointment> completed = new ArrayList<>(buildCompletedAppointments(inRange, freshCustomerIds));

        List<AttributedService> monthToDate =
                collectServices(adsCustomers.keySet(), today.withDayOfMonth(1), today, cutoff);
        Segment currentMonthToDate = segment(monthToDate, id -> true);

        // Already-paid bookings are excluded from "upcoming" below — this month's aggregation already
        // tags each paid service with the booking that produced it, so this is free (no extra Square call).
        Set<String> paidBookingIds = monthToDate.stream()
                .map(AttributedService::bookingId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        UpcomingResult upcomingResult =
                upcomingAppointments(adsCustomers.keySet(), freshCustomerIds, paidBookingIds, today, bookingHistory);
        List<UpcomingAppointment> upcoming = new ArrayList<>(upcomingResult.appointments());

        // Folds manager-follow-up appointments into the completed/upcoming lists (not into the
        // all/fresh/returning segment stats above, which stay scoped to the tracked flow's own
        // AttributedService rows) — see design.md D6, so Ads Report's drill-down agrees with the
        // adsReport summary numbers above it rather than silently undercounting. alreadyCountedBookingIds
        // (every booking the tracked flow already surfaced, completed or upcoming) keeps a follow-up
        // from re-adding the same visit a second time under the same customer.
        Set<String> alreadyCountedBookingIds = bookingIdsOf(inRange, upcomingResult.bookingIds());
        mergeFollowUpsInto(resolveFollowUps(slug), freshCustomerIds, today, completed, upcoming, alreadyCountedBookingIds);

        return new MarketingAnalyticsDto(from, to, all, fresh, returning, upcoming, completed, currentMonthToDate, adSpend);
    }

    /** Collapses [from, to]'s matched payroll lines (inRange — the same list the segments above are
     * summed from) to one row per booking, for appointments that actually collected money. Owner/family
     * comps are excluded: nothing was collected for them, so they don't belong in a "what was collected"
     * list. Requires no extra Square calls — inRange is already fetched for the segment totals.
     */
    private List<CompletedAppointment> buildCompletedAppointments(
            List<AttributedService> inRange, Set<String> freshCustomerIds) {
        Map<String, List<AttributedService>> byBooking = inRange.stream()
                .filter(s -> s.bookingId() != null && !"COMP".equals(s.channel()))
                .collect(java.util.stream.Collectors.groupingBy(AttributedService::bookingId, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        if (byBooking.isEmpty()) return List.of();

        Set<String> customerIds = byBooking.values().stream()
                .map(group -> group.get(0).customerId()).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> customerNames = square.customerNames(customerIds);

        List<CompletedAppointment> result = new ArrayList<>();
        for (List<AttributedService> group : byBooking.values()) {
            AttributedService first = group.get(0);
            BigDecimal collected = group.stream().map(AttributedService::net)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            String serviceName = group.stream().map(AttributedService::service)
                    .filter(n -> n != null && !n.isBlank()).distinct()
                    .collect(java.util.stream.Collectors.joining(" + "));
            result.add(new CompletedAppointment(
                    first.customerId(),
                    customerNames.getOrDefault(first.customerId(), "Customer"),
                    serviceName.isBlank() ? "Service" : serviceName,
                    parseIso(first.date()),
                    collected,
                    first.channel(),
                    freshCustomerIds.contains(first.customerId())
            ));
        }
        result.sort(Comparator.comparing(CompletedAppointment::date).reversed());
        return result;
    }

    /** Which grain {@link #adsReport} buckets into — see
     * openspec/changes/ads-report-consolidation/design.md D3. WEEK/MONTH may return several
     * historical rows (a trend, e.g. for the Full Month chart); MONTH_TO_DATE and CUSTOM always
     * return exactly one, unbucketed row for the exact range requested.
     */
    public enum PeriodKind { WEEK, MONTH, MONTH_TO_DATE, CUSTOM }

    /** Ad spend, ROI inputs, and volume metrics for the Ads Report tab, bucketed into one row per
     * week or per month (WEEK/MONTH) or a single row for the exact requested range
     * (MONTH_TO_DATE/CUSTOM) — see {@link MarketingAdsReportDto}. Reuses the exact same customer
     * resolution, freshness check, and payroll matching {@link #analytics} does; the only new
     * work here is bucketing those already-fetched results by period instead of summing them into
     * one aggregate, folding in manager-follow-up appointments (see
     * MarketingContactsService#followUpAppointments), and resolving ad spend per page from the
     * flexible {@code ad_spend_entries} ledger via {@link AdSpendResolver}.
     *
     * <p>Period boundaries are computed first and the underlying Square data is fetched over the
     * full aligned range (the first period's start through the last period's end) — not the raw
     * from/to — since a week or month may extend a few days beyond whatever the caller asked for;
     * fetching the narrower raw range would silently drop those edge days from their bucket.
     */
    public MarketingAdsReportDto adsReport(LocalDate from, LocalDate to, Set<String> sources, String slug, PeriodKind periodKind) {
        String periodType = periodKind.name();
        LocalDate today = LocalDate.now(clock);
        List<LocalDate[]> periods = switch (periodKind) {
            case WEEK -> buildPeriods(from, to, true);
            case MONTH -> buildPeriods(from, to, false);
            case MONTH_TO_DATE -> List.<LocalDate[]>of(new LocalDate[]{today.withDayOfMonth(1), today});
            case CUSTOM -> (from == null || to == null || to.isBefore(from))
                    ? List.<LocalDate[]>of() : List.<LocalDate[]>of(new LocalDate[]{from, to});
        };
        if (periods.isEmpty()) {
            PeriodRow empty = new PeriodRow(from, to, ZERO_MONEY, false, ZERO_MONEY, ZERO_MONEY, 0, ZERO_MONEY, 0, 0, false);
            return new MarketingAdsReportDto(periodType, List.of(), empty);
        }
        LocalDate alignedFrom = periods.get(0)[0];
        LocalDate alignedTo = periods.get(periods.size() - 1)[1];

        Map<String, AdsCustomer> adsCustomers = resolveAdsCustomers(sources, slug);
        Map<String, List<SquareClient.Booking>> bookingHistory =
                adsCustomers.isEmpty() ? Map.of() : bookingHistoryByCustomer(adsCustomers.keySet());
        Set<String> freshCustomerIds = adsCustomers.isEmpty() ? Set.of() : freshCustomerIds(adsCustomers, bookingHistory);
        List<AttributedService> inRange = adsCustomers.isEmpty()
                ? List.of() : collectServices(adsCustomers.keySet(), alignedFrom, alignedTo, priceCutoff());
        List<CompletedAppointment> completed = new ArrayList<>(buildCompletedAppointments(inRange, freshCustomerIds));
        Set<String> paidBookingIds = inRange.stream()
                .map(AttributedService::bookingId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        UpcomingResult upcomingResult = adsCustomers.isEmpty()
                ? new UpcomingResult(List.of(), Set.of())
                : upcomingAppointments(adsCustomers.keySet(), freshCustomerIds, paidBookingIds, today, bookingHistory);
        List<UpcomingAppointment> upcoming = new ArrayList<>(upcomingResult.appointments());

        List<FollowUpAppointment> followUps = resolveFollowUps(slug);
        Set<String> alreadyCountedBookingIds = bookingIdsOf(inRange, upcomingResult.bookingIds());
        mergeFollowUpsInto(followUps, freshCustomerIds, today, completed, upcoming, alreadyCountedBookingIds);

        List<PeriodRow> rows = new ArrayList<>();
        for (LocalDate[] p : periods) {
            rows.add(buildPeriodRow(p[0], p[1], slug, inRange, completed, upcoming, freshCustomerIds, followUps, today));
        }
        // Same formula as each row above, just against the full aligned span rather than one row's
        // own period — "outside the whole displayed window", not a sum of each row's own figure
        // (which would double-count appointments outside one row's period but inside another's).
        BigDecimal totalsAnticipatedOutsidePeriod = sumOutsidePeriod(upcoming, alignedFrom, alignedTo);
        PeriodRow totals = totalsRow(alignedFrom, alignedTo, rows, totalsAnticipatedOutsidePeriod);

        List<PeriodRow> mostRecentFirst = new ArrayList<>(rows);
        Collections.reverse(mostRecentFirst);
        return new MarketingAdsReportDto(periodType, mostRecentFirst, totals);
    }

    /** Every real, non-cancelled Square appointment for this page's ads-attributed contacts that
     * {@code marketing.attribution} doesn't know about — empty (rather than an error) when no
     * page is selected or the slug doesn't resolve to a real landing page, since follow-up
     * detection is inherently page-scoped (see design.md D1/D4). */
    private List<FollowUpAppointment> resolveFollowUps(String slug) {
        if (slug == null) return List.of();
        return dashboardRepository.findLandingPageId(slug)
                .map(pageId -> contactsService.followUpAppointments(
                        slug, null, dashboardRepository.findAttributedBookingIds(pageId, null)))
                .orElse(List.of());
    }

    /** Every bookingId the tracked ads flow already surfaced for this report — from {@code
     * collectServices}' payroll match ({@code inRange}, the same source {@code
     * buildCompletedAppointments} groups by booking) and from the upcoming-appointments scan.
     * {@link #resolveFollowUps} only knows to skip a booking that has a row in {@code
     * marketing.attribution}, which is a narrower, separate notion of "already known" — a booking
     * can be picked up here (the customer is ads-attributed and Square shows the payment/visit)
     * without ever getting an attribution row, and without this set, {@code mergeFollowUpsInto}
     * would add it a second time as a "manager follow-up" for the same customer.
     */
    private static Set<String> bookingIdsOf(List<AttributedService> inRange, Set<String> upcomingBookingIds) {
        Set<String> ids = inRange.stream()
                .map(AttributedService::bookingId).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        ids.addAll(upcomingBookingIds);
        return ids;
    }

    /** Classifies each follow-up appointment as completed (a real matched payment) or upcoming
     * (not yet happened) using the exact same rule the tracked-flow path already applies, and
     * appends it to the mutable lists in place — a follow-up appointment with neither (a past
     * visit with no matched payment) is silently dropped, matching {@code
     * buildCompletedAppointments}' own "no match, not shown" convention. A follow-up whose
     * bookingId is already in {@code alreadyCountedBookingIds} is skipped outright — it's already
     * represented in {@code completed}/{@code upcoming} via the tracked flow, and adding it again
     * here would show the same customer and visit twice in the breakdown.
     */
    private void mergeFollowUpsInto(
            List<FollowUpAppointment> followUps, Set<String> freshCustomerIds, LocalDate today,
            List<CompletedAppointment> completed, List<UpcomingAppointment> upcoming,
            Set<String> alreadyCountedBookingIds) {
        if (followUps.isEmpty()) return;
        Set<String> customerIds = followUps.stream()
                .map(FollowUpAppointment::customerId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<String, String> customerNames = customerIds.isEmpty() ? Map.of() : square.customerNames(customerIds);

        for (FollowUpAppointment f : followUps) {
            var a = f.appointment();
            if (a.startAt() == null) continue;
            if (a.bookingId() != null && alreadyCountedBookingIds.contains(a.bookingId())) continue;
            String customerId = f.customerId();
            String customerName = customerNames.getOrDefault(customerId, "Customer");
            String serviceName = a.serviceName() == null || a.serviceName().isBlank() ? "Service" : a.serviceName();
            boolean fresh = customerId != null && freshCustomerIds.contains(customerId);
            LocalDate date = a.startAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            if (a.collectedAmount() != null) {
                completed.add(new CompletedAppointment(customerId, customerName, serviceName, date,
                        a.collectedAmount().setScale(2, RoundingMode.HALF_UP), a.paymentChannel(), fresh));
            } else if (!date.isBefore(today)) {
                upcoming.add(new UpcomingAppointment(customerId, customerName, serviceName, a.startAt(),
                        a.price() == null ? ZERO_MONEY : a.price().setScale(2, RoundingMode.HALF_UP), fresh));
            }
        }
    }

    private PeriodRow buildPeriodRow(
            LocalDate periodStart, LocalDate periodEnd, String slug,
            List<AttributedService> inRange, List<CompletedAppointment> completed,
            List<UpcomingAppointment> upcoming, Set<String> freshCustomerIds,
            List<FollowUpAppointment> followUps, LocalDate today) {
        List<AttributedService> bucket = inRange.stream()
                .filter(s -> withinPeriod(parseIso(s.date()), periodStart, periodEnd))
                .toList();
        long customersCreated = segment(bucket, freshCustomerIds::contains).customerCount();

        List<CompletedAppointment> bucketCompleted = completed.stream()
                .filter(c -> withinPeriod(c.date(), periodStart, periodEnd))
                .toList();
        BigDecimal revenueCollected = bucketCompleted.stream().map(CompletedAppointment::collected)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        BigDecimal anticipatedRevenue = upcoming.stream()
                .filter(u -> withinPeriod(u.startAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), periodStart, periodEnd))
                .map(UpcomingAppointment::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal anticipatedRevenueOutsidePeriod = sumOutsidePeriod(upcoming, periodStart, periodEnd);

        long customersFollowedUp = followUps.stream()
                .filter(f -> f.appointment().startAt() != null && withinPeriod(
                        f.appointment().startAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), periodStart, periodEnd))
                .map(FollowUpAppointment::customerId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        AdSpendResolver.Resolved spend = slug == null
                ? new AdSpendResolver.Resolved(ZERO_MONEY, false)
                : resolveSpend(slug, periodStart, periodEnd);

        boolean monthInProgress = periodEnd.isAfter(today);

        return new PeriodRow(periodStart, periodEnd, spend.amount(), spend.estimated(), revenueCollected,
                anticipatedRevenue, customersCreated, anticipatedRevenueOutsidePeriod, bucketCompleted.size(),
                customersFollowedUp, monthInProgress);
    }

    private static boolean withinPeriod(LocalDate date, LocalDate periodStart, LocalDate periodEnd) {
        return date != null && !date.isBefore(periodStart) && !date.isAfter(periodEnd);
    }

    /** Every still-upcoming appointment's price whose date falls outside [periodStart, periodEnd]
     * — the complement of anticipatedRevenue's own within-period filter, so the two never overlap
     * and Collected + Anticipated (this period) + this sums to the full forward-booked pipeline. */
    private static BigDecimal sumOutsidePeriod(List<UpcomingAppointment> upcoming, LocalDate periodStart, LocalDate periodEnd) {
        return upcoming.stream()
                .filter(u -> !withinPeriod(u.startAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), periodStart, periodEnd))
                .map(UpcomingAppointment::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private PeriodRow totalsRow(LocalDate alignedFrom, LocalDate alignedTo, List<PeriodRow> rows,
            BigDecimal anticipatedRevenueOutsidePeriod) {
        BigDecimal adSpend = rows.stream().map(PeriodRow::adSpend).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        boolean adSpendEstimated = rows.stream().anyMatch(PeriodRow::adSpendEstimated);
        BigDecimal revenue = rows.stream().map(PeriodRow::revenueCollected).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal anticipated = rows.stream().map(PeriodRow::anticipatedRevenue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        long customersCreated = rows.stream().mapToLong(PeriodRow::customersCreated).sum();
        long completedAppointments = rows.stream().mapToLong(PeriodRow::completedAppointments).sum();
        long customersFollowedUp = rows.stream().mapToLong(PeriodRow::customersFollowedUp).sum();
        boolean monthInProgress = rows.stream().anyMatch(PeriodRow::monthInProgress);
        // Not a sum of each row's own anticipatedRevenueOutsidePeriod — that would double-count an
        // appointment outside row A's period but inside row B's. Computed once by the caller against
        // the full aligned span instead (see sumOutsidePeriod), passed straight through here.
        return new PeriodRow(alignedFrom, alignedTo, adSpend, adSpendEstimated, revenue, anticipated,
                customersCreated, anticipatedRevenueOutsidePeriod, completedAppointments, customersFollowedUp, monthInProgress);
    }

    /** Splits [from, to] into whole calendar weeks (Monday–Sunday) or whole calendar months that
     * together cover it — the returned periods may extend a little before {@code from} or after
     * {@code to} to stay aligned to real week/month boundaries. Ascending order (oldest first);
     * callers that want most-recent-first reverse the result themselves.
     */
    private static List<LocalDate[]> buildPeriods(LocalDate from, LocalDate to, boolean weekly) {
        List<LocalDate[]> periods = new ArrayList<>();
        if (from == null || to == null || to.isBefore(from)) return periods;
        if (weekly) {
            LocalDate cursor = from.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            while (!cursor.isAfter(to)) {
                periods.add(new LocalDate[]{cursor, cursor.plusDays(6)});
                cursor = cursor.plusWeeks(1);
            }
        } else {
            YearMonth cursor = YearMonth.from(from);
            YearMonth last = YearMonth.from(to);
            while (!cursor.isAfter(last)) {
                periods.add(new LocalDate[]{cursor.atDay(1), cursor.atEndOfMonth()});
                cursor = cursor.plusMonths(1);
            }
        }
        return periods;
    }

    /** Resolves ad spend for [from, to] on one landing page from the flexible {@code
     * ad_spend_entries} ledger — see {@link AdSpendResolver}. */
    private AdSpendResolver.Resolved resolveSpend(String slug, LocalDate from, LocalDate to) {
        List<AdSpendEntry> entries = adSpendEntryRepository.findOverlapping(slug, from, to);
        return AdSpendResolver.resolve(entries, from, to);
    }

    /** Records a new ad-spend-entry row for one page and period — never upserts; a corrected
     * re-entry is kept alongside the original so spend history stays auditable (see
     * {@link AdSpendResolver}'s handling of overlapping entries). */
    @Transactional
    public AdSpendEntry createAdSpendEntry(String slug, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount, String enteredBy) {
        AdSpendEntry entry = AdSpendEntry.builder()
                .landingPageSlug(slug)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .amountSpent(amount.setScale(2, RoundingMode.HALF_UP))
                .enteredBy(enteredBy)
                .build();
        return adSpendEntryRepository.save(entry);
    }

    /** Every entered spend row for one page, most recent period first — for the ad-spend-entry
     * management UI (a simple list, not a report). */
    public List<AdSpendEntry> listAdSpendEntries(String slug) {
        return adSpendEntryRepository.findByLandingPageSlugOrderByPeriodStartDesc(slug);
    }

    /** Edits an existing entry in place (landingPageSlug is fixed — the management UI is always
     * scoped to one page, and re-pointing an entry at a different page has no real use case). Unlike
     * {@link #createAdSpendEntry}'s "never overwrite" append-only default, this is for fixing an
     * outright mistake (wrong amount/dates) without leaving a confusing extra row behind — enter a
     * new row instead if the intent is a genuine, auditable revision to a period that already
     * reported correctly. Empty (not thrown) if the id doesn't exist, mirroring
     * {@link #deleteAdSpendEntry}'s not-found handling.
     */
    @Transactional
    public java.util.Optional<AdSpendEntry> updateAdSpendEntry(
            Long id, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {
        return adSpendEntryRepository.findById(id).map(entry -> {
            entry.setPeriodStart(periodStart);
            entry.setPeriodEnd(periodEnd);
            entry.setAmountSpent(amount.setScale(2, RoundingMode.HALF_UP));
            return adSpendEntryRepository.save(entry);
        });
    }

    /** Removes an outright mistaken entry (duplicate, wrong page/amount typed in) — false if the id
     * doesn't exist, so the controller can 404 rather than silently no-op. */
    @Transactional
    public boolean deleteAdSpendEntry(Long id) {
        if (!adSpendEntryRepository.existsById(id)) return false;
        adSpendEntryRepository.deleteById(id);
        return true;
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
                    try {
                        candidateIds.addAll(square.customerIdsForPhone(c.phoneNumber()));
                    } catch (RuntimeException ex) {
                        log.warn("Failed to resolve Square customer ids by phone (channel={}); continuing with "
                                + "only this contact's stored square_customer_id, if any", c.channel(), ex);
                    }
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
     * "returning" — we'd rather undercount a fresh win than overclaim one we can't verify. A failed
     * Square lookup (e.g. a rate-limit error) is handled identically to "no data available."
     */
    /** Every ads-attributed customer's full booking history (any status, past or future), fetched
     * once per customer and shared by both {@link #freshCustomerIds} and {@link #upcomingAppointments}
     * — they used to each fetch it separately with a different {@code since} (400-day lookback vs.
     * "yesterday"), which are different {@code SquareClient} cache keys, so every report load paid
     * two full live-Square sweeps across every ads customer instead of one. The 400-day lookback
     * window is a superset of what "upcoming" needs (it only cares about today-or-later, filtered
     * downstream), so one fetch now serves both.
     */
    private Map<String, List<SquareClient.Booking>> bookingHistoryByCustomer(Set<String> customerIds) {
        // Truncated to the day: SquareClient caches bookingsForCustomer by (customerId, since), so
        // a "since" that carries millisecond precision (clock.instant() called fresh every time)
        // is a different cache key on literally every call — the 2-minute cache never actually
        // hits, and repeat navigation between tabs pays the full multi-second Square round trip
        // every single time. Day-level granularity is more than precise enough for a 400-day
        // lookback anyway.
        Instant since = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minus(BOOKING_HISTORY_LOOKBACK);
        return customerIds.parallelStream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> bookingsOrEmpty(id, since)));
    }

    private Set<String> freshCustomerIds(Map<String, AdsCustomer> adsCustomers,
            Map<String, List<SquareClient.Booking>> bookingHistory) {
        final Map<String, Instant> createdAtByCustomer = fetchCustomerCreatedAtsOrEmpty(adsCustomers.keySet());
        return adsCustomers.entrySet().parallelStream()
                .filter(e -> isFresh(e.getKey(), e.getValue().firstTouch(), createdAtByCustomer, bookingHistory))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Map<String, Instant> fetchCustomerCreatedAtsOrEmpty(Set<String> customerIds) {
        try {
            return square.customerCreatedAts(customerIds);
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch Square customer creation dates; falling back to booking-history-only "
                    + "freshness check", ex);
            return Map.of();
        }
    }

    private boolean isFresh(String customerId, Instant firstTouch, Map<String, Instant> createdAtByCustomer,
            Map<String, List<SquareClient.Booking>> bookingHistory) {
        Instant cutoff = firstTouch.minus(FRESHNESS_GRACE);
        Instant earliestBooking = earliestBookingStart(customerId, bookingHistory);
        if (earliestBooking != null) return !earliestBooking.isBefore(cutoff);
        Instant createdAt = createdAtByCustomer.get(customerId);
        return createdAt != null && !createdAt.isBefore(cutoff);
    }

    /** The start of this customer's earliest known booking (any status, past or future) — bookings
     * carry real transaction/appointment history unaffected by a later profile merge, unlike Square's
     * own created_at. Null if they have no bookings at all.
     */
    private Instant earliestBookingStart(String customerId, Map<String, List<SquareClient.Booking>> bookingHistory) {
        return bookingHistory.getOrDefault(customerId, List.of()).stream()
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
    private record UpcomingResult(List<UpcomingAppointment> appointments, Set<String> bookingIds) {}

    private UpcomingResult upcomingAppointments(
            Set<String> adsCustomerIds, Set<String> freshCustomerIds, Set<String> paidBookingIds, LocalDate today,
            Map<String, List<SquareClient.Booking>> bookingHistory) {
        record FutureBooking(String customerId, SquareClient.Booking booking) {}
        // Filters the same booking history freshCustomerIds already fetched (400-day lookback,
        // which already extends through Square's FUTURE_BOOKING_HORIZON regardless of since) —
        // this used to be a second live Square sweep per customer with its own "since=yesterday"
        // cache key, doubling every report's Square round trips for no reason.
        List<FutureBooking> future = adsCustomerIds.parallelStream()
                .flatMap(id -> bookingHistory.getOrDefault(id, List.of()).stream()
                        .filter(MarketingAnalyticsService::didHappen)
                        .filter(b -> b.id() == null || !paidBookingIds.contains(b.id()))
                        .filter(b -> isTodayOrLater(b.startAt(), today))
                        .map(b -> new FutureBooking(id, b)))
                .toList();
        if (future.isEmpty()) return new UpcomingResult(List.of(), Set.of());

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
        Set<String> bookingIds = future.stream()
                .map(f -> f.booking().id()).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return new UpcomingResult(result, bookingIds);
    }

    /** Best-effort: a customer whose Square booking lookup fails is simply excluded from the
     * upcoming-appointments list rather than failing the whole analytics response. */
    private List<SquareClient.Booking> bookingsOrEmpty(String customerId, Instant since) {
        try {
            return square.bookingsForCustomer(customerId, since);
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch upcoming bookings for customer {}; excluding from the upcoming list",
                    customerId, ex);
            return List.of();
        }
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
