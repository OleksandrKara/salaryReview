package com.salonreview.square;

import com.salonreview.domain.NoShowFeeOverride;
import com.salonreview.domain.Provider;
import com.salonreview.repo.NoShowFeeOverrideRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderLineItem;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * No-show fee tracking. Reads Square (read-only) to surface every {@code NO_SHOW} booking and detect the
 * paid $25 cancellation fee that sometimes follows it — a COMPLETED order with a "Cancelation Policy"
 * line ≈ $25 for the same customer. Each fee is paired to the nearest preceding no-show for that customer
 * within 2 months; the provider is then compensated the $25 (split evenly across a multi-provider booking)
 * as a {@code NOSHOW} adjustment line, in the month the fee was paid. A no-show with no detected fee shows
 * in its own month as "no fee collected". Only the owner/manager overrides (CONFIRM / SUPPRESS) are
 * persisted — everything else is derived live, so nothing drifts from Square.
 */
@Service
public class NoShowFeeService {

    static final BigDecimal FEE = new BigDecimal("25.00");
    private static final BigDecimal FEE_MIN = new BigDecimal("24.00");
    private static final BigDecimal FEE_MAX = new BigDecimal("26.00");
    private static final Pattern CANCEL = Pattern.compile("cancel\\w*\\s*polic", Pattern.CASE_INSENSITIVE);
    private static final int LOOKBACK_MONTHS = 2; // max gap between a no-show and its fee payment

    private final SquareClientProvider squareClientProvider;
    private final ProviderDirectory directory;
    private final ProviderRepository providers;
    private final NoShowFeeOverrideRepository overrides;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public NoShowFeeService(SquareClientProvider squareClientProvider, ProviderDirectory directory,
                            ProviderRepository providers, NoShowFeeOverrideRepository overrides,
                            com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.squareClientProvider = squareClientProvider;
        this.directory = directory;
        this.providers = providers;
        this.overrides = overrides;
        this.currentBusinessContext = currentBusinessContext;
    }

    /**
     * True if this order is the salon's ~$25 "Cancelation Policy" charge — the no-show / late-cancellation
     * fee. Same definition used to pair fees to no-shows above; exposed so the cancelled-appointments
     * review can drop cancellations we already charged a fee on (single source of truth for the fee shape).
     */
    public static boolean isCancellationFeeOrder(Order o) {
        if (o == null || o.lineItems() == null) return false;
        for (OrderLineItem li : o.lineItems()) {
            if (li.name() == null || !CANCEL.matcher(li.name()).find()) continue;
            BigDecimal amt = SquareClient.toDollars(li.totalMoney());
            if (amt.compareTo(FEE_MIN) >= 0 && amt.compareTo(FEE_MAX) <= 0) return true;
        }
        return false;
    }

    /** One row in the no-show table — one per provider on the booking (a multi-provider no-show splits). */
    public record NoShowRow(String bookingId, Long providerId, String providerName, String customer,
                            String noShowAt, String noShowDate, BigDecimal feeAmount, String feePaidDate,
                            String state) {}

    /** The month's no-show rows plus the {@code NOSHOW} credit lines keyed by provider id. */
    public record NoShowMonth(List<NoShowRow> rows, Map<Long, List<AttributedService>> linesByProvider) {}

    /** {@code NOSHOW} credit lines for fees paid in (year, month), keyed by provider id (for settlement). */
    public Map<Long, List<AttributedService>> noShowFeeLinesByProvider(int year, int month) {
        return compute(year, month).linesByProvider();
    }

    /** All no-show rows belonging to (year, month) — for the admin table and the provider's own view. */
    public List<NoShowRow> rowsForMonth(int year, int month) {
        return compute(year, month).rows();
    }

    // --- internal carriers ---
    private record NoShow(String bookingId, String customerId, LocalDate day, String startAt, List<Long> providerIds) {}
    private record Fee(String orderId, String customerId, LocalDate paid, BigDecimal amount) {}

    /**
     * Compute the month's no-show rows and credit lines. Set A = fees paid in the month, paired to their
     * (possibly earlier) no-show; set B = no-shows in the month with no fee anywhere in the window. CONFIRM
     * overrides add self-contained credits in their paid month; SUPPRESS removes an auto-detected credit.
     */
    @Transactional
    public NoShowMonth compute(int year, int month) {
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);
        ZoneId zone = zone(square);
        YearMonth ym = YearMonth.of(year, month);
        LocalDate monthStart = ym.atDay(1), monthEnd = ym.atEndOfMonth();

        // No-shows from 2 months before the month; fee charges from the month start to 2 months after
        // (so an in-month no-show paid later is excluded here and surfaces in its payment month instead).
        Instant bookFrom = monthStart.minusMonths(LOOKBACK_MONTHS).atStartOfDay(zone).toInstant();
        Instant bookTo = monthEnd.plusDays(1).atStartOfDay(zone).toInstant();
        Instant orderFrom = monthStart.atStartOfDay(zone).toInstant();
        Instant orderTo = monthEnd.plusMonths(LOOKBACK_MONTHS).plusDays(1).atStartOfDay(zone).toInstant();
        // Independent Square reads — fetch concurrently to cut cold latency.
        var bookingsF = java.util.concurrent.CompletableFuture.supplyAsync(() -> square.bookings(bookFrom, bookTo));
        var ordersF = java.util.concurrent.CompletableFuture.supplyAsync(() -> square.completedOrders(orderFrom, orderTo));
        List<Booking> bookings = bookingsF.join();
        List<Order> orders = ordersF.join();

        Map<String, String> memberName = new HashMap<>();
        for (TeamMember tm : square.allTeamMembers()) memberName.put(tm.id(), tm.fullName());

        List<NoShow> noShows = new ArrayList<>();
        for (Booking b : bookings) {
            if (!"NO_SHOW".equals(b.status())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null) continue;
            LinkedHashSet<Long> providerIds = new LinkedHashSet<>();
            if (b.appointmentSegments() != null) {
                for (AppointmentSegment s : b.appointmentSegments()) {
                    if (s.teamMemberId() == null) continue;
                    providerIds.add(directory.resolveOrCreate(s.teamMemberId(),
                            memberName.getOrDefault(s.teamMemberId(), "")).getId());
                }
            }
            if (providerIds.isEmpty()) continue;
            noShows.add(new NoShow(b.id(), b.customerId(), day, b.startAt(), new ArrayList<>(providerIds)));
        }

        List<Fee> fees = new ArrayList<>();
        for (Order o : orders) {
            if (o.lineItems() == null || o.customerId() == null) continue;
            LocalDate paid = localDate(o.closedAt(), zone);
            if (paid == null) continue;
            for (OrderLineItem li : o.lineItems()) {
                if (li.name() == null || !CANCEL.matcher(li.name()).find()) continue;
                BigDecimal amt = SquareClient.toDollars(li.totalMoney());
                if (amt.compareTo(FEE_MIN) >= 0 && amt.compareTo(FEE_MAX) <= 0) {
                    fees.add(new Fee(o.id(), o.customerId(), paid, amt));
                    break;
                }
            }
        }

        // Pair each fee (chronologically) to the nearest preceding unpaired no-show for that customer,
        // within the 2-month cap. One fee pays one no-show; one no-show is paid once.
        fees.sort(Comparator.comparing(Fee::paid));
        Set<String> usedNoShow = new HashSet<>();
        Map<String, Fee> feeByNoShow = new HashMap<>();
        for (Fee f : fees) {
            NoShow best = null;
            for (NoShow n : noShows) {
                if (n.customerId() == null || usedNoShow.contains(n.bookingId())) continue;
                if (!Objects.equals(n.customerId(), f.customerId())) continue;
                if (n.day().isAfter(f.paid())) continue;
                if (n.day().isBefore(f.paid().minusMonths(LOOKBACK_MONTHS))) continue;
                if (best == null || n.day().isAfter(best.day())) best = n;
            }
            if (best != null) { usedNoShow.add(best.bookingId()); feeByNoShow.put(best.bookingId(), f); }
        }

        Map<String, NoShowFeeOverride> ovr = overrides.findAllByBusinessId(businessId).stream()
                .collect(Collectors.toMap(NoShowFeeOverride::getSquareBookingId, o -> o, (a, b) -> a));
        Map<String, String> custNames = square.customerNames(noShows.stream()
                .map(NoShow::customerId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<NoShowRow> rows = new ArrayList<>();
        Map<Long, List<AttributedService>> lines = new LinkedHashMap<>();

        for (NoShow n : noShows) {
            String cust = shortName(custNames.get(n.customerId()));
            NoShowFeeOverride o = ovr.get(n.bookingId());
            boolean suppressed = o != null && NoShowFeeOverride.SUPPRESS.equals(o.getKind());
            boolean confirmed = o != null && NoShowFeeOverride.CONFIRM.equals(o.getKind());
            Fee f = feeByNoShow.get(n.bookingId());

            if (f != null && !suppressed) {
                if (!sameMonth(f.paid(), year, month)) continue; // belongs to its payment month
                BigDecimal share = f.amount().divide(BigDecimal.valueOf(n.providerIds().size()), 2, RoundingMode.HALF_UP);
                for (Long pid : n.providerIds()) {
                    rows.add(row(n, cust, pid, f.amount(), f.paid(), "CREDITED"));
                    lines.computeIfAbsent(pid, k -> new ArrayList<>())
                            .add(noShowLine(pid, n, cust, share, f.paid()));
                }
            } else if (f != null && suppressed) {
                if (sameMonth(f.paid(), year, month))
                    for (Long pid : n.providerIds()) rows.add(row(n, cust, pid, f.amount(), f.paid(), "SUPPRESSED"));
            } else if (!confirmed) {
                if (sameMonth(n.day(), year, month))
                    for (Long pid : n.providerIds()) rows.add(row(n, cust, pid, null, null, "NO_FEE"));
            }
        }

        // CONFIRM overrides: self-contained credits, landing in their paid (or no-show) month.
        for (NoShowFeeOverride o : ovr.values()) {
            if (!NoShowFeeOverride.CONFIRM.equals(o.getKind()) || o.getProviderId() == null) continue;
            LocalDate paid = o.getFeePaidDate() != null ? o.getFeePaidDate() : o.getNoShowDate();
            if (paid == null || !sameMonth(paid, year, month)) continue;
            BigDecimal amt = o.getAmount() == null ? FEE : o.getAmount();
            Long pid = o.getProviderId();
            String dateStr = o.getNoShowDate() == null ? paid.toString() : o.getNoShowDate().toString();
            rows.add(new NoShowRow(o.getSquareBookingId(), pid, providerName(pid), o.getCustomerName(),
                    dateStr, dateStr, amt, paid.toString(), "CONFIRMED"));
            String half = paid.getDayOfMonth() <= 15 ? "FIRST" : "SECOND";
            lines.computeIfAbsent(pid, k -> new ArrayList<>()).add(new AttributedService("",
                    providerName(pid), paid.toString(), half,
                    "No-show fee" + (o.getCustomerName() == null ? "" : " — " + o.getCustomerName()),
                    amt, BigDecimal.ZERO, amt, BigDecimal.ZERO, false, 0, 1, false, "NOSHOW",
                    null, o.getSquareBookingId(), null, o.getCustomerName()));
        }
        rows.sort(Comparator.comparing(NoShowRow::noShowDate).thenComparing(NoShowRow::providerName));
        return new NoShowMonth(rows, lines);
    }

    private NoShowRow row(NoShow n, String cust, Long pid, BigDecimal amount, LocalDate paid, String state) {
        return new NoShowRow(n.bookingId(), pid, providerName(pid), cust, n.startAt(), n.day().toString(),
                amount, paid == null ? null : paid.toString(), state);
    }

    /** A {@code NOSHOW} credit line — a flat fee paid in full to the provider (folded into adjustments). */
    private AttributedService noShowLine(Long pid, NoShow n, String cust, BigDecimal amount, LocalDate paid) {
        String half = paid.getDayOfMonth() <= 15 ? "FIRST" : "SECOND";
        String svc = "No-show fee" + (cust == null ? "" : " — " + cust) + " (no-show " + n.day() + ")";
        return new AttributedService("", providerName(pid), paid.toString(), half, svc,
                amount, BigDecimal.ZERO, amount, BigDecimal.ZERO, false, 0, 1, false, "NOSHOW",
                null, n.bookingId(), n.customerId(), cust);
    }

    // --- overrides (owner/manager) ---

    public record ConfirmRequest(String bookingId, Long providerId, BigDecimal amount, LocalDate feePaidDate,
                                 String customerName, LocalDate noShowDate, String note) {}

    @Transactional
    public void confirm(ConfirmRequest req, String by) {
        if (req.bookingId() == null || req.bookingId().isBlank() || req.providerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bookingId and providerId are required");
        }
        if (!providers.existsById(req.providerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such provider");
        }
        NoShowFeeOverride row = overrides.findBySquareBookingId(req.bookingId()).orElseGet(NoShowFeeOverride::new);
        row.setBusinessId(currentBusinessContext.id());
        row.setSquareBookingId(req.bookingId());
        row.setKind(NoShowFeeOverride.CONFIRM);
        row.setProviderId(req.providerId());
        row.setAmount(req.amount() == null ? FEE : req.amount());
        row.setFeePaidDate(req.feePaidDate());
        row.setNoShowDate(req.noShowDate());
        row.setCustomerName(req.customerName());
        row.setNote(req.note());
        row.setCreatedBy(by);
        overrides.save(row);
    }

    @Transactional
    public void suppress(String bookingId, String by) {
        if (bookingId == null || bookingId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bookingId is required");
        }
        NoShowFeeOverride row = overrides.findBySquareBookingId(bookingId).orElseGet(NoShowFeeOverride::new);
        row.setBusinessId(currentBusinessContext.id());
        row.setSquareBookingId(bookingId);
        row.setKind(NoShowFeeOverride.SUPPRESS);
        row.setAmount(FEE);
        row.setCreatedBy(by);
        overrides.save(row);
    }

    @Transactional
    public void clearOverride(String bookingId) {
        overrides.deleteBySquareBookingId(bookingId);
    }

    // --- helpers ---

    private String providerName(Long providerId) {
        return providers.findById(providerId).map(Provider::getDisplayName).orElse("#" + providerId);
    }

    private static boolean sameMonth(LocalDate d, int year, int month) {
        return d != null && d.getYear() == year && d.getMonthValue() == month;
    }

    private ZoneId zone(SquareClient square) {
        String tz = square.locationTimeZone();
        try {
            return tz == null || tz.isBlank() ? ZoneId.of("UTC") : ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private static LocalDate localDate(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).atZone(zone).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Donnah Phipps" → "Donnah P."; null/blank → null. */
    private static String shortName(String full) {
        if (full == null || full.isBlank()) return null;
        String[] parts = full.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }
}
