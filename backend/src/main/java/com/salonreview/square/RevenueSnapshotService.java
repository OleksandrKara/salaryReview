package com.salonreview.square;

import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.RevenueSnapshot;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.RevenueSnapshotRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.web.dto.RevenueDayDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures one {@link RevenueSnapshot} per day (MTD revenue, MTD card/cash, services count, and the
 * value of upcoming bookings remaining in the month). Idempotent on {@code snapshot_date} — re-running
 * the same date is a no-op. {@code fillMonthEndActualsFor} closes the loop by writing the final
 * month-end revenue onto past snapshots once a month has settled, so each row becomes a complete
 * {@code (prediction-inputs, outcome)} pair for the forecaster's calibration step.
 */
@Service
public class RevenueSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(RevenueSnapshotService.class);

    private final RevenueSnapshotRepository repo;
    private final SquareMonthAggregator aggregator;
    private final SquareClient square;
    private final SalonConfigRepository salonConfig;
    private final PayPeriodRepository payPeriods;
    private final PeriodEntryRepository entries;
    private final RevenueForecastService forecaster;
    private final ManualAdjustmentService manualAdjustments;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public RevenueSnapshotService(RevenueSnapshotRepository repo,
                                  SquareMonthAggregator aggregator,
                                  SquareClient square,
                                  SalonConfigRepository salonConfig,
                                  PayPeriodRepository payPeriods,
                                  PeriodEntryRepository entries,
                                  RevenueForecastService forecaster,
                                  ManualAdjustmentService manualAdjustments,
                                  com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.repo = repo;
        this.aggregator = aggregator;
        this.square = square;
        this.salonConfig = salonConfig;
        this.currentBusinessContext = currentBusinessContext;
        this.payPeriods = payPeriods;
        this.entries = entries;
        this.forecaster = forecaster;
        this.manualAdjustments = manualAdjustments;
    }

    /**
     * Capture a snapshot for {@code date} — MTD totals (1st → {@code date} inclusive) and the
     * upcoming-booking pipeline for ({@code date+1} → end-of-month). Idempotent: if a row already
     * exists for that date, do nothing.
     */
    @Transactional
    public void captureFor(LocalDate date) {
        if (repo.findBySnapshotDate(date).isPresent()) {
            log.debug("revenue_snapshot already exists for {}, skipping", date);
            return;
        }

        BigDecimal priceCutoff = priceCutoff();
        SquareMonthAggregator.MonthAggregation agg =
                aggregator.aggregate(date.getYear(), date.getMonthValue(), priceCutoff);

        BigDecimal mtdCard = BigDecimal.ZERO, mtdCash = BigDecimal.ZERO;
        int mtdServices = 0;
        for (SquareMonthAggregator.AttributedService s : agg.services()) {
            LocalDate svcDay = parseIso(s.date());
            if (svcDay == null || svcDay.isAfter(date)) continue;
            if ("CASH".equals(s.channel()) || "CASH-NOTE".equals(s.channel())) {
                mtdCash = mtdCash.add(s.gross());
            } else {
                mtdCard = mtdCard.add(s.gross());
            }
            mtdServices += s.countedUnits();
        }
        // Manual adjustments (credits or deductions like a refund) aren't Square orders, so the
        // aggregator above never sees them — fold them in the same way OwnerOverviewService and
        // SettlementPreviewService do, so this MTD figure isn't silently stale.
        mtdCard = mtdCard.add(manualAdjustments.totalGrossThrough(date));
        mtdServices += manualAdjustments.countedUnitDeltaThrough(date, priceCutoff);
        BigDecimal mtdRevenue = mtdCard.add(mtdCash).setScale(2, RoundingMode.HALF_UP);

        UpcomingResult upcoming = computeUpcoming(date);

        repo.save(RevenueSnapshot.builder()
                .businessId(currentBusinessContext.id())
                .snapshotDate(date)
                .mtdRevenue(mtdRevenue)
                .mtdCard(mtdCard.setScale(2, RoundingMode.HALF_UP))
                .mtdCash(mtdCash.setScale(2, RoundingMode.HALF_UP))
                .mtdServices(mtdServices)
                .upcomingCount(upcoming.count)
                .upcomingGross(upcoming.gross)
                .createdAt(Instant.now())
                .build());

        log.info("revenue_snapshot captured for {} — MTD ${}, upcoming {} bookings worth ${}",
                date, mtdRevenue, upcoming.count, upcoming.gross);
    }

    /**
     * Capture any missing snapshots for the last 3 days (ending yesterday). Used by the startup hook
     * so a short server outage doesn't leave gaps; bounded to 3 days so a long outage doesn't trigger
     * a runaway backfill loop.
     */
    @Transactional
    public void backfillRecent() {
        ZoneId zone = salonZone();
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        for (int offset = 2; offset >= 0; offset--) {
            LocalDate d = yesterday.minusDays(offset);
            captureFor(d);
        }
    }

    /**
     * Fill {@code month_end_actual} on every snapshot row whose {@code snapshot_date} falls in
     * {@code month}. The actual is the sum of all {@link PeriodEntry} card+cash totals for that month.
     * If no entries exist yet, log a warning and leave the rows untouched.
     */
    @Transactional
    public int fillMonthEndActualsFor(YearMonth month) {
        BigDecimal actual = BigDecimal.ZERO;
        boolean anyEntry = false;
        for (PayPeriod pp : payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(
                currentBusinessContext.id(), month.getYear())) {
            if (pp.getMonth() != month.getMonthValue()) continue;
            for (PeriodEntry e : entries.findAllByPayPeriodId(pp.getId())) {
                anyEntry = true;
                actual = actual.add(e.getCardTotal()).add(e.getCashTotal());
            }
        }
        if (!anyEntry) {
            log.warn("fillMonthEndActualsFor({}): no PeriodEntry rows yet — leaving snapshots untouched", month);
            return 0;
        }
        BigDecimal finalActual = actual.setScale(2, RoundingMode.HALF_UP);

        List<RevenueSnapshot> rows = repo.findAllBySnapshotDateBetween(
                month.atDay(1), month.atEndOfMonth());
        for (RevenueSnapshot r : rows) {
            r.setMonthEndActual(finalActual);
        }
        log.info("fillMonthEndActualsFor({}): wrote ${} to {} snapshot rows", month, finalActual, rows.size());
        return rows.size();
    }

    /**
     * What was known and projected as of {@code date} — the frozen {@link RevenueSnapshot} row
     * (MTD revenue/card/cash/services, upcoming pipeline), plus the month-end forecast recomputed
     * live from that day's own inputs via {@link RevenueForecastService}. If the month has since
     * closed, {@code monthEndActual} lets the caller compare projection vs. reality directly.
     *
     * <p>The forecast isn't literally frozen from that day (it re-runs the pattern/calibration
     * signals against whatever history exists now, which only grows over time) — in practice this
     * is a non-issue for a settled month (the actual is what it is regardless), and a reasonable
     * best-effort answer for a still-open month.
     *
     * <p>Returns {@code hasSnapshot=false} with everything else null/zero when no snapshot was ever
     * captured for that date (before the feature existed, or a gap the backfill didn't cover).
     */
    public RevenueDayDetailDto dayDetail(LocalDate date) {
        Optional<RevenueSnapshot> row = repo.findBySnapshotDate(date);
        if (row.isEmpty()) {
            return RevenueDayDetailDto.noSnapshot(date);
        }
        RevenueSnapshot s = row.get();
        ForecastResult forecast = forecaster.forecast(date.getYear(), date.getMonthValue(), s.getMtdRevenue(), s.getUpcomingGross());
        return new RevenueDayDetailDto(
                date, true,
                s.getMtdRevenue(), s.getMtdCard(), s.getMtdCash(), s.getMtdServices(),
                s.getUpcomingCount(), s.getUpcomingGross(),
                forecast.projectedMid(), forecast.projectedLow(), forecast.projectedHigh(),
                s.getMonthEndActual());
    }

    // --- internals ---

    private record UpcomingResult(int count, BigDecimal gross) {}

    /** Future bookings remaining in {@code asOf}'s month, after {@code asOf} — valued at catalog price. */
    private UpcomingResult computeUpcoming(LocalDate asOf) {
        ZoneId zone = salonZone();
        YearMonth ym = YearMonth.from(asOf);
        Instant from = asOf.plusDays(1).atStartOfDay(zone).toInstant();
        Instant to   = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();

        List<SquareClient.Booking> bookings = square.bookings(from, to);
        // Non-cancelled bookings whose start is within asOf's month, after asOf.
        List<SquareClient.Booking> valid = bookings.stream()
                .filter(b -> isLiveStatus(b.status()))
                .filter(b -> startsInRange(b.startAt(), zone, asOf.plusDays(1), ym.atEndOfMonth()))
                .toList();

        List<String> varIds = valid.stream()
                .filter(b -> b.appointmentSegments() != null)
                .flatMap(b -> b.appointmentSegments().stream())
                .map(SquareClient.AppointmentSegment::serviceVariationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, BigDecimal> prices = varIds.isEmpty() ? Map.of() : square.catalogPrices(varIds);

        BigDecimal gross = BigDecimal.ZERO;
        for (SquareClient.Booking b : valid) {
            if (b.appointmentSegments() == null) continue;
            for (var seg : b.appointmentSegments()) {
                gross = gross.add(prices.getOrDefault(seg.serviceVariationId(), BigDecimal.ZERO));
            }
        }
        return new UpcomingResult(valid.size(), gross.setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean isLiveStatus(String status) {
        if (status == null) return false;
        return switch (status) {
            case "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW" -> false;
            default -> true;
        };
    }

    private static boolean startsInRange(String iso, ZoneId zone, LocalDate fromInclusive, LocalDate toInclusive) {
        if (iso == null) return false;
        try {
            LocalDate d = Instant.parse(iso).atZone(zone).toLocalDate();
            return !d.isBefore(fromInclusive) && !d.isAfter(toInclusive);
        } catch (Exception e) {
            return false;
        }
    }

    private static LocalDate parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
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
