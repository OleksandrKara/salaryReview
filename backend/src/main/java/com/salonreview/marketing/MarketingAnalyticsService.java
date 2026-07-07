package com.salonreview.marketing;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import com.salonreview.web.dto.MarketingAnalyticsDto.Segment;
import com.salonreview.web.dto.MarketingAnalyticsDto.UpcomingAppointment;
import org.springframework.stereotype.Service;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
public class MarketingAnalyticsService {

    private static final Segment EMPTY_SEGMENT =
            new Segment(0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

    // A genuinely new customer's Square record can only be created at/after the moment the ad funnel
    // first captured them; a small grace window absorbs clock/event-ordering slack rather than
    // requiring an exact instant-for-instant comparison.
    private static final Duration FRESHNESS_GRACE = Duration.ofDays(1);

    private final MarketingContactsRepository contactsRepository;
    private final SquareMonthAggregator aggregator;
    private final SquareClient square;
    private final SalonConfigRepository salonConfig;

    public MarketingAnalyticsService(
            MarketingContactsRepository contactsRepository,
            SquareMonthAggregator aggregator,
            SquareClient square,
            SalonConfigRepository salonConfig
    ) {
        this.contactsRepository = contactsRepository;
        this.aggregator = aggregator;
        this.square = square;
        this.salonConfig = salonConfig;
    }

    /** Gross revenue, customer count, and service count for ads-attributed customers with a service
     * rendered in [from, to] inclusive, split into all / fresh-to-Square / already-existing segments —
     * plus every still-upcoming appointment for an ads-attributed customer (not bound to [from, to];
     * "what's coming" is inherently forward-looking). Aggregates one calendar month at a time (the
     * only granularity SquareMonthAggregator offers) and concatenates, since a custom range can span
     * more than one month.
     */
    public MarketingAnalyticsDto analytics(LocalDate from, LocalDate to) {
        Map<String, Instant> firstTouchByCustomer = contactsRepository.findAdsAttributedCustomersWithFirstTouch();
        if (firstTouchByCustomer.isEmpty()) {
            return new MarketingAnalyticsDto(from, to, EMPTY_SEGMENT, EMPTY_SEGMENT, EMPTY_SEGMENT, List.of());
        }

        Set<String> freshCustomerIds = freshCustomerIds(firstTouchByCustomer);

        BigDecimal cutoff = priceCutoff();
        List<AttributedService> inRange = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
            SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(ym.getYear(), ym.getMonthValue(), cutoff);
            for (AttributedService s : agg.services()) {
                if (!firstTouchByCustomer.containsKey(s.customerId())) continue;
                LocalDate day = parseIso(s.date());
                if (day == null || day.isBefore(from) || day.isAfter(to)) continue;
                inRange.add(s);
            }
        }

        Segment all = segment(inRange, id -> true);
        Segment fresh = segment(inRange, freshCustomerIds::contains);
        Segment returning = segment(inRange, id -> !freshCustomerIds.contains(id));

        List<UpcomingAppointment> upcoming =
                upcomingAppointments(firstTouchByCustomer.keySet(), freshCustomerIds);

        return new MarketingAnalyticsDto(from, to, all, fresh, returning, upcoming);
    }

    /** A customer is "fresh" when their Square record was created at/after the first moment the ad
     * funnel captured them (minus a small grace window) — i.e. Square has no history of them predating
     * this ad touch, so the ad brought in a genuinely new customer rather than winning back an existing
     * one. Unknown creation dates (lookup failure) are treated conservatively as "returning", since we'd
     * rather undercount a fresh win than overclaim one we can't actually verify.
     */
    private Set<String> freshCustomerIds(Map<String, Instant> firstTouchByCustomer) {
        Map<String, Instant> createdAtByCustomer = square.customerCreatedAts(firstTouchByCustomer.keySet());
        Set<String> fresh = new HashSet<>();
        for (var e : firstTouchByCustomer.entrySet()) {
            Instant createdAt = createdAtByCustomer.get(e.getKey());
            if (createdAt != null && !createdAt.isBefore(e.getValue().minus(FRESHNESS_GRACE))) {
                fresh.add(e.getKey());
            }
        }
        return fresh;
    }

    private static Segment segment(List<AttributedService> services, Predicate<String> customerFilter) {
        List<AttributedService> matched = services.stream()
                .filter(s -> customerFilter.test(s.customerId())).toList();
        long customerCount = matched.stream().map(AttributedService::customerId).distinct().count();
        BigDecimal gross = matched.stream().map(AttributedService::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new Segment(customerCount, matched.size(), gross);
    }

    /** Every still-future, non-cancelled appointment for an ads-attributed customer, regardless of the
     * requested [from, to] range. One row per booking; a multi-service visit's segments are joined into
     * one service name and one summed (menu list price) total, since "an upcoming visit" — not "a line
     * item" — is what the owner wants to see in a forward-looking list.
     */
    private List<UpcomingAppointment> upcomingAppointments(Set<String> adsCustomerIds, Set<String> freshCustomerIds) {
        Instant now = Instant.now();
        record FutureBooking(String customerId, SquareClient.Booking booking) {}
        List<FutureBooking> future = adsCustomerIds.parallelStream()
                .flatMap(id -> square.bookingsForCustomer(id).stream()
                        .filter(MarketingAnalyticsService::didHappen)
                        .filter(b -> isFuture(b.startAt(), now))
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

    private static boolean isFuture(String startAt, Instant now) {
        if (startAt == null || startAt.isBlank()) return false;
        try {
            return Instant.parse(startAt).isAfter(now);
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
