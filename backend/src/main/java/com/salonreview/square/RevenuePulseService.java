package com.salonreview.square;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.web.dto.RevenuePulseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Service
public class RevenuePulseService {

    /** Matches the "h:mm a" display time the aggregator stamps on each attributed service. */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final SquareClient square;
    private final RevenueForecastService forecaster;
    private final SquareMonthAggregator aggregator;
    private final SalonConfigRepository salonConfig;
    private final Clock clock;

    @Autowired
    public RevenuePulseService(SquareClient square, RevenueForecastService forecaster,
                               SquareMonthAggregator aggregator, SalonConfigRepository salonConfig) {
        this(square, forecaster, aggregator, salonConfig, Clock.systemUTC());
    }

    RevenuePulseService(SquareClient square, RevenueForecastService forecaster,
                        SquareMonthAggregator aggregator, SalonConfigRepository salonConfig, Clock clock) {
        this.square = square;
        this.forecaster = forecaster;
        this.aggregator = aggregator;
        this.salonConfig = salonConfig;
        this.clock = clock;
    }

    public RevenuePulseDto pulse(int year, int month) {
        ZoneId zone = resolveZone();
        ZonedDateTime now = clock.instant().atZone(zone);
        LocalDate today = now.toLocalDate();
        YearMonth ym = YearMonth.of(year, month);
        YearMonth priorYm = ym.minusMonths(1);

        boolean isCurrentMonth = today.getYear() == year && today.getMonthValue() == month;

        // Compare the same span of days on both sides (1 → endDay). When prior month is shorter the
        // ordinal is clamped (e.g. comparing through May 31 against Apr 30).
        int currentEndDay = isCurrentMonth ? today.getDayOfMonth() : ym.lengthOfMonth();
        int priorEndDay   = Math.min(currentEndDay, priorYm.lengthOfMonth());

        // For the CURRENT month, cut both windows at the same wall-clock time-of-day so we compare like
        // for like to the minute — this month through "now" against last month through the same point in
        // its day. Without it, today counts only partially while last month's matching day counted in
        // full, so early in the day the delta read far too negative (−100% before the first sale). Past
        // months compare full calendar days (no time cutoff).
        LocalTime cutoffTime = isCurrentMonth ? now.toLocalTime() : null;
        String asOfTime = isCurrentMonth ? now.toLocalTime().format(TIME_FMT) : null;

        // Card vs cash, attributed through the month aggregator (which includes cash notes — the
        // reason a raw Square-orders sum understated cash). Run the two months in parallel.
        BigDecimal cutoff = priceCutoff();
        var currentF = CompletableFuture.supplyAsync(() -> mtdSplit(year, month, currentEndDay, cutoffTime, cutoff));
        var priorF   = CompletableFuture.supplyAsync(
                () -> mtdSplit(priorYm.getYear(), priorYm.getMonthValue(), priorEndDay, cutoffTime, cutoff));

        // Upcoming bookings are only meaningful for the current or a future month.
        boolean fetchUpcoming = !today.isAfter(ym.atEndOfMonth());
        Instant nowInstant  = Instant.now();
        Instant endOfMonth  = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();
        var upcomingF = fetchUpcoming
                ? CompletableFuture.supplyAsync(() -> square.bookings(nowInstant, endOfMonth))
                : CompletableFuture.completedFuture(List.<SquareClient.Booking>of());

        Split current = currentF.join();
        Split prior   = priorF.join();
        BigDecimal deltaPct = delta(current.total(), prior.total());

        UpcomingResult upcoming = processUpcoming(upcomingF.join(), year, month, zone);
        BigDecimal naiveProjected = current.total().add(upcoming.gross()).setScale(2, RoundingMode.HALF_UP);

        // Smart forecast: blends pattern-match (from PeriodEntry history) and booking-ceiling
        // calibration (from revenue_snapshot rows). Falls back to naive when neither has enough data.
        ForecastResult forecast = forecaster.forecast(year, month, current.total(), upcoming.gross());

        // Project card vs cash by applying the recent card:cash mix to the forecast total. The current
        // month's mix is the most representative; fall back to the prior period's, since upcoming
        // bookings have no tender yet (the mix can only be estimated from realized revenue).
        Split projectedSplit = splitProjection(forecast.projectedMid(), current, prior);

        // "At this same point last month, where was the month pacing to end up?" — a pure run-rate
        // extrapolation of last month's MTD-through-the-same-cutoff (no pattern/calibration signal,
        // since that would need historical upcoming-bookings data we can no longer honestly
        // reconstruct — a booking's status today isn't necessarily what it was back then). Only
        // meaningful for the current month (there's no "now" to anchor a past month's view to).
        BigDecimal priorProjected = isCurrentMonth
                ? paceProjected(prior.total(), priorEndDay, cutoffTime, priorYm.lengthOfMonth())
                : null;
        BigDecimal projectedDeltaPct = isCurrentMonth ? delta(forecast.projectedMid(), priorProjected) : null;

        return new RevenuePulseDto(
                year, month, currentEndDay, currentEndDay, priorEndDay, asOfTime,
                current.total(), current.card(), current.cash(),
                prior.total(), prior.card(), prior.cash(), deltaPct,
                upcoming.count(), upcoming.gross(), naiveProjected,
                forecast.projectedMid(), projectedSplit.card(), projectedSplit.cash(),
                forecast.projectedLow(), forecast.projectedHigh(),
                forecast.calibrationDataPoints(), forecast.historyMonths(),
                ym.lengthOfMonth(), priorYm.lengthOfMonth(),
                priorProjected, projectedDeltaPct);
    }

    /**
     * Extrapolates a partial-month total to a full-month pace: {@code mtdTotal / elapsedDays *
     * daysInMonth}, where {@code elapsedDays} counts every day before {@code throughDay} as whole and
     * the {@code throughDay} itself as the wall-clock fraction of {@code cutoffTime} elapsed (assumes
     * revenue accrues roughly evenly through the day — a deliberately simple estimate, not a forecast).
     * Null when there's too little of the day elapsed to extrapolate sanely (avoids a single early sale
     * blowing up into an absurd "$50,000 projected" a few minutes into the month).
     */
    private static BigDecimal paceProjected(BigDecimal mtdTotal, int throughDay, LocalTime cutoffTime,
                                            int daysInMonth) {
        if (mtdTotal == null || throughDay <= 0 || daysInMonth <= 0) return null;
        double dayFraction = cutoffTime != null ? cutoffTime.toSecondOfDay() / 86400.0 : 1.0;
        double elapsedDays = (throughDay - 1) + dayFraction;
        if (elapsedDays < 0.1) return null; // under ~2.4h into the month — too little to extrapolate
        return mtdTotal.multiply(BigDecimal.valueOf(daysInMonth))
                .divide(BigDecimal.valueOf(elapsedDays), 2, RoundingMode.HALF_UP);
    }

    /** Split a forecast total into card/cash using the card share of {@code current}, else {@code prior}. */
    private static Split splitProjection(BigDecimal projectedMid, Split current, Split prior) {
        Split basis = current.total().signum() > 0 ? current : prior;
        if (projectedMid == null || basis.total().signum() == 0) {
            return new Split(BigDecimal.ZERO, BigDecimal.ZERO); // no realized revenue to infer the mix
        }
        BigDecimal card = projectedMid.multiply(basis.card())
                .divide(basis.total(), 2, RoundingMode.HALF_UP);
        BigDecimal cash = projectedMid.subtract(card).setScale(2, RoundingMode.HALF_UP);
        return new Split(card, cash);
    }

    /**
     * Card vs cash for {@code year}-{@code month}, days 1 → {@code throughDay}, via the month
     * aggregator. Cash = the CASH and CASH-NOTE channels (cash-tender Square orders + manual cash
     * notes), so it matches what the /overview and the daily snapshot report.
     */
    private Split mtdSplit(int year, int month, int throughDay, LocalTime cutoffTime, BigDecimal cutoff) {
        SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(year, month, cutoff);
        LocalDate cutoffDay = LocalDate.of(year, month, throughDay);
        BigDecimal card = BigDecimal.ZERO, cash = BigDecimal.ZERO;
        for (SquareMonthAggregator.AttributedService s : agg.services()) {
            LocalDate day = parseIso(s.date());
            if (day == null || day.isAfter(cutoffDay)) continue;
            // On the final day, honour the time-of-day cutoff so both months stop at the same minute.
            // A service with no known time counts as early in the day (included). Earlier days are whole.
            if (cutoffTime != null && day.isEqual(cutoffDay)) {
                LocalTime t = parseLocalTime(s.time());
                if (t != null && t.isAfter(cutoffTime)) continue;
            }
            if ("CASH".equals(s.channel()) || "CASH-NOTE".equals(s.channel())) {
                cash = cash.add(s.gross());
            } else {
                card = card.add(s.gross());
            }
        }
        return new Split(card.setScale(2, RoundingMode.HALF_UP), cash.setScale(2, RoundingMode.HALF_UP));
    }

    private static LocalDate parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse the aggregator's "h:mm a" display time back to a LocalTime; null if absent/unparseable. */
    private static LocalTime parseLocalTime(String display) {
        if (display == null || display.isBlank()) return null;
        try {
            return LocalTime.parse(display, TIME_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal priceCutoff() {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        return cfg.getServicePriceCutoff();
    }

    // --- upcoming bookings ---

    private UpcomingResult processUpcoming(List<SquareClient.Booking> bookings,
                                           int year, int month, ZoneId zone) {
        List<SquareClient.Booking> valid = bookings.stream()
                .filter(b -> isValidStatus(b.status()) && isInMonth(b.startAt(), year, month, zone))
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

    private record UpcomingResult(int count, BigDecimal gross) {}

    // --- helpers ---

    private static boolean isValidStatus(String status) {
        if (status == null) return false;
        return switch (status) {
            case "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW" -> false;
            default -> true;
        };
    }

    private static boolean isInMonth(String startAt, int year, int month, ZoneId zone) {
        if (startAt == null) return false;
        try {
            LocalDate day = Instant.parse(startAt).atZone(zone).toLocalDate();
            return day.getYear() == year && day.getMonthValue() == month;
        } catch (Exception e) {
            return false;
        }
    }

    /** Card + cash dollars (total is derived); used for both realized periods and the projection split. */
    private record Split(BigDecimal card, BigDecimal cash) {
        BigDecimal total() {
            return card.add(cash).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static BigDecimal delta(BigDecimal current, BigDecimal prior) {
        if (prior == null || prior.signum() == 0) return null;
        return current.subtract(prior)
                .multiply(BigDecimal.valueOf(100))
                .divide(prior, 1, RoundingMode.HALF_UP);
    }

    private ZoneId resolveZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
