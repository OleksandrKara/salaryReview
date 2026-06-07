package com.salonreview.square;

import com.salonreview.web.dto.RevenuePulseDto;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.CompletableFuture;

@Service
public class RevenuePulseService {

    private final SquareClient square;

    public RevenuePulseService(SquareClient square) {
        this.square = square;
    }

    public RevenuePulseDto pulse(int year, int month) {
        ZoneId zone = resolveZone();
        LocalDate today = LocalDate.now(zone);
        YearMonth ym = YearMonth.of(year, month);
        YearMonth priorYm = ym.minusMonths(1);

        // Current period end: today if we're in this month, last day otherwise.
        boolean isCurrentMonth = today.getYear() == year && today.getMonthValue() == month;
        int currentEndDay = isCurrentMonth ? today.getDayOfMonth() : ym.lengthOfMonth();
        // Prior period end: same day number, clamped to prior month length.
        int priorEndDay = Math.min(currentEndDay, priorYm.lengthOfMonth());

        LocalDate curStart   = ym.atDay(1);
        LocalDate curEnd     = ym.atDay(currentEndDay);
        LocalDate priorStart = priorYm.atDay(1);
        LocalDate priorEnd   = priorYm.atDay(priorEndDay);

        // Exclusive upper bounds for the Square order query.
        Instant curFrom   = curStart.atStartOfDay(zone).toInstant();
        Instant curTo     = curEnd.plusDays(1).atStartOfDay(zone).toInstant();
        Instant priorFrom = priorStart.atStartOfDay(zone).toInstant();
        Instant priorTo   = priorEnd.plusDays(1).atStartOfDay(zone).toInstant();

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

        BigDecimal currentGross = sumOrders(currentF.join());
        BigDecimal priorGross   = sumOrders(priorF.join());
        BigDecimal deltaPct     = delta(currentGross, priorGross);

        UpcomingResult upcoming = processUpcoming(upcomingF.join(), year, month, zone);
        BigDecimal projected = currentGross.add(upcoming.gross()).setScale(2, RoundingMode.HALF_UP);

        return new RevenuePulseDto(
                year, month, currentEndDay, currentEndDay, priorEndDay,
                currentGross, priorGross, deltaPct,
                upcoming.count(), upcoming.gross(), projected);
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

    private static BigDecimal sumOrders(List<SquareClient.Order> orders) {
        BigDecimal total = BigDecimal.ZERO;
        for (var o : orders) {
            if (o.lineItems() == null) continue;
            for (var li : o.lineItems()) {
                if (li.catalogObjectId() == null) continue; // skip non-catalog items
                total = total.add(lineRevenue(li));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
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
