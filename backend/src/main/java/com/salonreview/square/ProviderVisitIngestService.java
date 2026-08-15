package com.salonreview.square;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SalonConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Populates the {@link ProviderVisit} ledger from the month aggregator (the same source settlements
 * use). For a month it records one visit per (customer, provider, day) — anonymous services (no Square
 * customer) are skipped — and flags {@code rebookedSameDay} when the customer created a future booking
 * on the visit day. Idempotent: re-ingesting a month replaces that month's rows, so daily accrual of
 * the current month and the one-time historical backfill are both safe to re-run.
 */
@Service
public class ProviderVisitIngestService {

    private static final Logger log = LoggerFactory.getLogger(ProviderVisitIngestService.class);
    private static final int REBOOK_HORIZON_DAYS = 90; // how far ahead we look for a future booking

    private final SquareMonthAggregator aggregator;
    private final SquareClient square;
    private final SalonConfigRepository salonConfig;
    private final ProviderVisitRepository visits;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public ProviderVisitIngestService(SquareMonthAggregator aggregator, SquareClient square,
                                      SalonConfigRepository salonConfig, ProviderVisitRepository visits,
                                      com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.aggregator = aggregator;
        this.square = square;
        this.salonConfig = salonConfig;
        this.visits = visits;
        this.currentBusinessContext = currentBusinessContext;
    }

    /** Re-ingest one month: replace its visit rows with a fresh computation. */
    @Transactional
    public int ingestMonth(int year, int month) {
        ZoneId zone = salonZone();
        YearMonth ym = YearMonth.of(year, month);
        SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(year, month, priceCutoff());

        // Collapse attributed services to distinct (customer, provider, day) visits.
        Map<VisitKey, String> visitName = new LinkedHashMap<>(); // key -> provider display name
        for (SquareMonthAggregator.AttributedService s : agg.services()) {
            if (s.customerId() == null || s.customerId().isBlank()) continue; // anonymous — not tracked
            LocalDate day = parseDay(s.date());
            if (day == null || day.getYear() != year || day.getMonthValue() != month) continue;
            visitName.putIfAbsent(new VisitKey(s.customerId(), s.providerId(), day), s.providerName());
        }

        Map<String, Set<LocalDate>> rebookDays = sameDayRebookIndex(ym, zone);

        visits.deleteByServiceDateBetween(ym.atDay(1), ym.atEndOfMonth());
        List<ProviderVisit> rows = new ArrayList<>(visitName.size());
        for (var e : visitName.entrySet()) {
            VisitKey k = e.getKey();
            boolean rebooked = rebookDays.getOrDefault(k.customerId(), Set.of()).contains(k.day());
            rows.add(ProviderVisit.builder()
                    .customerId(k.customerId()).providerRef(k.providerRef()).providerName(e.getValue())
                    .serviceDate(k.day()).rebookedSameDay(rebooked).createdAt(Instant.now())
                    .build());
        }
        visits.saveAll(rows);
        log.info("provider_visit ingest {} — {} visits", ym, rows.size());
        return rows.size();
    }

    /** Re-ingest the current month (daily accrual). */
    public int ingestCurrentMonth() {
        YearMonth now = YearMonth.now(salonZone());
        return ingestMonth(now.getYear(), now.getMonthValue());
    }

    /** Backfill the last {@code months} months, skipping any already populated. Bounded, idempotent. */
    public void backfillHistory(int months) {
        YearMonth cursor = YearMonth.now(salonZone());
        for (int i = 0; i < months; i++) {
            YearMonth ym = cursor.minusMonths(i);
            if (visits.countByServiceDateBetween(ym.atDay(1), ym.atEndOfMonth()) > 0) continue;
            try {
                ingestMonth(ym.getYear(), ym.getMonthValue());
            } catch (RuntimeException ex) {
                log.warn("provider_visit backfill failed for {}: {}", ym, ex.toString());
            }
        }
    }

    // --- same-day rebook ---

    /**
     * customerId -> the set of days on which they created a future booking (within the horizon).
     *
     * <p>Keyed by canonical Square customer id, same as {@code agg.services()}'s {@code customerId}
     * (see {@link SquareMonthAggregator}'s own resolution) — Square can silently merge two duplicate
     * customer profiles into one, and an older booking here can still carry the pre-merge id even
     * after a newer one settles on the canonical id. Without resolving both to the same id space, a
     * real same-day rebook could go undetected simply because the two bookings disagree on which of
     * the customer's ids to use.
     */
    private Map<String, Set<LocalDate>> sameDayRebookIndex(YearMonth ym, ZoneId zone) {
        Instant from = ym.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = ym.atEndOfMonth().plusDays(REBOOK_HORIZON_DAYS + 1).atStartOfDay(zone).toInstant();
        List<SquareClient.Booking> bookings = square.bookings(from, to);
        java.util.Set<String> customerIds = new HashSet<>();
        for (SquareClient.Booking b : bookings) if (b.customerId() != null) customerIds.add(b.customerId());
        Map<String, String> canonical = square.canonicalCustomerIds(customerIds);
        Map<String, Set<LocalDate>> index = new HashMap<>();
        for (SquareClient.Booking b : bookings) {
            if (b.customerId() == null || b.createdAt() == null || b.startAt() == null) continue;
            LocalDate created = instantDay(b.createdAt(), zone);
            LocalDate start = instantDay(b.startAt(), zone);
            if (created == null || start == null || !start.isAfter(created)) continue; // a future booking
            String customerId = canonical.getOrDefault(b.customerId(), b.customerId());
            index.computeIfAbsent(customerId, k -> new HashSet<>()).add(created);
        }
        return index;
    }

    // --- helpers ---

    private record VisitKey(String customerId, String providerRef, LocalDate day) {}

    private static LocalDate parseDay(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.isBlank()) return null;
        try {
            return LocalDate.parse(yyyyMmDd);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate instantDay(String iso, ZoneId zone) {
        try {
            return Instant.parse(iso).atZone(zone).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal priceCutoff() {
        Long businessId = currentBusinessContext.id();
        SalonConfig cfg = salonConfig.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("Salon config for business " + businessId + " is missing"));
        return cfg.getServicePriceCutoff();
    }

    private ZoneId salonZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
