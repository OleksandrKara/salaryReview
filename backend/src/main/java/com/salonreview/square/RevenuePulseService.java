package com.salonreview.square;

import com.salonreview.web.dto.RevenuePulseDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private final SquareClient square;
    private final RevenueForecastService forecaster;

    public RevenuePulseService(SquareClient square, RevenueForecastService forecaster) {
        this.square = square;
        this.forecaster = forecaster;
    }

    public RevenuePulseDto pulse(int year, int month) {
        ZoneId zone = resolveZone();
        ZonedDateTime nowZoned = ZonedDateTime.now(zone);
        LocalDate today = nowZoned.toLocalDate();
        YearMonth ym = YearMonth.of(year, month);
        YearMonth priorYm = ym.minusMonths(1);

        boolean isCurrentMonth = today.getYear() == year && today.getMonthValue() == month;

        LocalDate curStart   = ym.atDay(1);
        LocalDate priorStart = priorYm.atDay(1);
        Instant curFrom   = curStart.atStartOfDay(zone).toInstant();
        Instant priorFrom = priorStart.atStartOfDay(zone).toInstant();

        // Window upper bounds + labelling fields:
        //  - Current month: truncate both sides at the same wall-clock time-of-day. Comparing
        //    "Jun 1 → Jun 14 11:22 PM" against "May 1 → May 14 11:22 PM" is the apples-to-apples
        //    read; using full days on both sides falsely inflates the prior period in the morning.
        //  - Past month: compare full calendar days (the historical comparison is fixed).
        Instant curTo, priorTo;
        int currentEndDay, priorEndDay;
        String asOfTime;
        if (isCurrentMonth) {
            LocalDateTime nowLocal = nowZoned.toLocalDateTime();
            // minusMonths clamps the day-of-month when the prior month is shorter (e.g. May 31 → Apr 30).
            LocalDateTime priorCutoff = nowLocal.minusMonths(1);
            curTo         = nowZoned.toInstant();
            priorTo       = priorCutoff.atZone(zone).toInstant();
            currentEndDay = today.getDayOfMonth();
            priorEndDay   = priorCutoff.toLocalDate().getDayOfMonth();
            asOfTime      = nowLocal.format(TIME_FMT);
        } else {
            currentEndDay = ym.lengthOfMonth();
            priorEndDay   = Math.min(currentEndDay, priorYm.lengthOfMonth());
            curTo         = ym.atDay(currentEndDay).plusDays(1).atStartOfDay(zone).toInstant();
            priorTo       = priorYm.atDay(priorEndDay).plusDays(1).atStartOfDay(zone).toInstant();
            asOfTime      = null;
        }

        // Fetch current orders, prior orders, and upcoming bookings in parallel.
        var currentF = CompletableFuture.supplyAsync(() -> square.completedOrders(curFrom, curTo));
        var priorF   = CompletableFuture.supplyAsync(() -> square.completedOrders(priorFrom, priorTo));

        // Upcoming bookings are only meaningful for the current or a future month.
        boolean fetchUpcoming = !today.isAfter(ym.atEndOfMonth());
        Instant nowInstant  = Instant.now();
        Instant endOfMonth  = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();
        var upcomingF = fetchUpcoming
                ? CompletableFuture.supplyAsync(() -> square.bookings(nowInstant, endOfMonth))
                : CompletableFuture.completedFuture(List.<SquareClient.Booking>of());

        Split current = splitOrders(currentF.join());
        Split prior   = splitOrders(priorF.join());
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

        return new RevenuePulseDto(
                year, month, currentEndDay, currentEndDay, priorEndDay, asOfTime,
                current.total(), current.card(), current.cash(),
                prior.total(), prior.card(), prior.cash(), deltaPct,
                upcoming.count(), upcoming.gross(), naiveProjected,
                forecast.projectedMid(), projectedSplit.card(), projectedSplit.cash(),
                forecast.projectedLow(), forecast.projectedHigh(),
                forecast.calibrationDataPoints(), forecast.historyMonths());
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

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

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

    /** Card/cash split of a set of orders (catalog line items only), attributed per order by tender. */
    private static Split splitOrders(List<SquareClient.Order> orders) {
        BigDecimal card = BigDecimal.ZERO, cash = BigDecimal.ZERO;
        for (var o : orders) {
            if (o.lineItems() == null) continue;
            BigDecimal orderTotal = BigDecimal.ZERO;
            for (var li : o.lineItems()) {
                if (li.catalogObjectId() == null) continue; // skip non-catalog items
                orderTotal = orderTotal.add(lineRevenue(li));
            }
            if (SquareClient.isCashOrder(o)) cash = cash.add(orderTotal);
            else card = card.add(orderTotal);
        }
        return new Split(card.setScale(2, RoundingMode.HALF_UP), cash.setScale(2, RoundingMode.HALF_UP));
    }

    /** Card + cash dollars (total is derived); used for both realized periods and the projection split. */
    private record Split(BigDecimal card, BigDecimal cash) {
        BigDecimal total() {
            return card.add(cash).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static BigDecimal lineRevenue(SquareClient.OrderLineItem li) {
        if (li.grossSalesMoney() != null) return SquareClient.toDollars(li.grossSalesMoney());
        return SquareClient.toDollars(li.totalMoney());
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
