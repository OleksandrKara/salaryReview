package com.salonreview.square;

import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.TeamMember;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only diagnostic that pulls a window of real Square data and reports how well it supports the
 * salary automation — before any of it is persisted. Answers the make-or-break questions: do
 * appointment segments carry the provider (team member)? Do catalog prices resolve for the price
 * cutoff? Do the {@code cashew $xx} notes parse? How do bookings and orders line up by day?
 */
@Service
public class SquareSpikeService {

    private final SquareClientProvider squareClientProvider;
    private final CashNoteParser cashNotes;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public SquareSpikeService(SquareClientProvider squareClientProvider, CashNoteParser cashNotes,
                               com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.squareClientProvider = squareClientProvider;
        this.cashNotes = cashNotes;
        this.currentBusinessContext = currentBusinessContext;
    }

    public SpikeReport run(LocalDate from, LocalDate to, BigDecimal priceCutoff) {
        // squareClientProvider.forBusiness() itself throws a clear IllegalStateException if this
        // business has no square_connection row yet — no separate pre-flight check needed.
        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<TeamMember> members = square.activeTeamMembers();
        List<Booking> bookings = square.bookings(start, end);
        List<Order> orders = square.completedOrders(start, end);

        Map<String, String> memberName = new LinkedHashMap<>();
        members.forEach(m -> memberName.put(m.id(), m.fullName()));

        // --- Segments, attribution, providers ---
        int segments = 0, withMember = 0;
        Map<String, int[]> perProvider = new LinkedHashMap<>(); // id -> [bookings, segments]
        List<String> variationIds = new ArrayList<>();
        for (Booking b : bookings) {
            List<AppointmentSegment> segs = b.appointmentSegments() == null ? List.of() : b.appointmentSegments();
            for (AppointmentSegment s : segs) {
                segments++;
                if (s.teamMemberId() != null && !s.teamMemberId().isBlank()) {
                    withMember++;
                    perProvider.computeIfAbsent(s.teamMemberId(), k -> new int[2])[1]++;
                }
                if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId());
            }
            segs.stream().map(AppointmentSegment::teamMemberId).filter(id -> id != null && !id.isBlank())
                    .distinct().forEach(id -> perProvider.computeIfAbsent(id, k -> new int[2])[0]++);
        }

        Map<String, BigDecimal> prices = square.catalogPrices(variationIds);
        int atOrAbove = 0, below = 0, priceResolved = 0, priceMissing = 0;
        for (Booking b : bookings) {
            for (AppointmentSegment s : (b.appointmentSegments() == null ? List.<AppointmentSegment>of() : b.appointmentSegments())) {
                BigDecimal price = prices.get(s.serviceVariationId());
                if (price == null) { priceMissing++; continue; }
                priceResolved++;
                if (price.compareTo(priceCutoff) >= 0) atOrAbove++; else below++;
            }
        }

        List<ProviderActivity> providers = perProvider.entrySet().stream()
                .map(e -> new ProviderActivity(e.getKey(),
                        memberName.getOrDefault(e.getKey(), "(unknown — not in team list)"),
                        e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparing(ProviderActivity::segments).reversed())
                .toList();

        // --- Cash notes ---
        int cashCount = 0;
        BigDecimal cashTotal = BigDecimal.ZERO;
        List<CashSample> cashSamples = new ArrayList<>();
        for (Booking b : bookings) {
            var decl = cashNotes.parse(b.sellerNote()).or(() -> cashNotes.parse(b.customerNote()));
            if (decl.isPresent()) {
                cashCount++;
                BigDecimal amt = decl.get().amount().orElse(BigDecimal.ZERO); // 0 = "use service total"
                cashTotal = cashTotal.add(amt);
                if (cashSamples.size() < 10) {
                    String note = b.sellerNote() != null ? b.sellerNote() : b.customerNote();
                    cashSamples.add(new CashSample(b.id(), note, amt));
                }
            }
        }

        // --- Orders: tips + tenders by type ---
        BigDecimal tipTotal = BigDecimal.ZERO;
        Map<String, int[]> tenderCount = new LinkedHashMap<>();
        Map<String, BigDecimal> tenderAmt = new LinkedHashMap<>();
        for (Order o : orders) {
            tipTotal = tipTotal.add(SquareClient.toDollars(o.totalTipMoney()));
            if (o.tenders() != null) {
                for (var t : o.tenders()) {
                    String type = t.type() == null ? "UNKNOWN" : t.type();
                    tenderCount.computeIfAbsent(type, k -> new int[1])[0]++;
                    tenderAmt.merge(type, SquareClient.toDollars(t.amountMoney()), BigDecimal::add);
                }
            }
        }
        List<TenderTotal> tenders = tenderCount.entrySet().stream()
                .map(e -> new TenderTotal(e.getKey(), e.getValue()[0], tenderAmt.getOrDefault(e.getKey(), BigDecimal.ZERO)))
                .toList();

        // --- By-date alignment of bookings vs orders (UTC days) ---
        Map<String, int[]> byDate = new TreeMap<>();
        bookings.forEach(b -> byDate.computeIfAbsent(day(b.startAt()), k -> new int[2])[0]++);
        orders.forEach(o -> byDate.computeIfAbsent(day(o.closedAt()), k -> new int[2])[1]++);
        List<DayAlignment> dayAlignment = byDate.entrySet().stream()
                .map(e -> new DayAlignment(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();

        // --- Raw samples for eyeballing ---
        List<BookingSample> sampleBookings = bookings.stream().limit(10).map(b -> {
            var seg = (b.appointmentSegments() == null || b.appointmentSegments().isEmpty())
                    ? null : b.appointmentSegments().get(0);
            return new BookingSample(b.id(), b.startAt(), b.status(),
                    seg == null ? null : seg.teamMemberId(),
                    seg == null ? null : memberName.get(seg.teamMemberId()),
                    seg == null ? null : seg.serviceVariationId(),
                    seg == null ? null : prices.get(seg.serviceVariationId()),
                    b.sellerNote(), b.customerNote());
        }).toList();

        List<OrderSample> sampleOrders = orders.stream().limit(10).map(o -> new OrderSample(
                o.id(), o.closedAt(),
                o.lineItems() == null ? 0 : o.lineItems().size(),
                SquareClient.toDollars(o.totalTipMoney()),
                o.tenders() == null ? List.of() : o.tenders().stream().map(t -> t.type()).toList()
        )).toList();

        Totals totals = new Totals(members.size(), bookings.size(), segments, orders.size());
        Attribution attribution = new Attribution(withMember, segments - withMember,
                perProvider.size(), pct(withMember, segments));
        Catalog catalog = new Catalog(priceCutoff, priceResolved, priceMissing, atOrAbove, below);
        OrdersSummary ordersSummary = new OrdersSummary(orders.size(), tipTotal, tenders);

        return new SpikeReport(from.toString(), to.toString(), totals, attribution, providers,
                new CashNotes(cashCount, cashTotal, cashSamples), catalog, ordersSummary,
                dayAlignment, sampleBookings, sampleOrders);
    }

    private static String day(String rfc3339) {
        return rfc3339 == null || rfc3339.length() < 10 ? "?" : rfc3339.substring(0, 10);
    }

    private static String pct(int part, int whole) {
        return whole == 0 ? "n/a" : Math.round(100.0 * part / whole) + "%";
    }

    // --- Report shape (serialized to JSON) ---
    public record SpikeReport(String from, String to, Totals totals, Attribution attribution,
                              List<ProviderActivity> providers, CashNotes cashNotes, Catalog catalog,
                              OrdersSummary orders, List<DayAlignment> byDate,
                              List<BookingSample> sampleBookings, List<OrderSample> sampleOrders) {}

    public record Totals(int teamMembers, int bookings, int appointmentSegments, int completedOrders) {}

    public record Attribution(int segmentsWithProvider, int segmentsWithoutProvider,
                              int distinctProvidersInBookings, String providerAttributionRate) {}

    public record ProviderActivity(String teamMemberId, String name, int bookings, int segments) {}

    public record CashNotes(int bookingsWithCashNote, BigDecimal totalCashDeclared, List<CashSample> samples) {}

    public record CashSample(String bookingId, String note, BigDecimal amount) {}

    public record Catalog(BigDecimal priceCutoff, int segmentsWithResolvedPrice, int segmentsWithMissingPrice,
                          int countedAtOrAboveCutoff, int excludedBelowCutoff) {}

    public record OrdersSummary(int count, BigDecimal totalTips, List<TenderTotal> tendersByType) {}

    public record TenderTotal(String type, int count, BigDecimal amount) {}

    public record DayAlignment(String date, int bookings, int orders) {}

    public record BookingSample(String bookingId, String startAt, String status, String teamMemberId,
                                String providerName, String serviceVariationId, BigDecimal catalogPrice,
                                String sellerNote, String customerNote) {}

    public record OrderSample(String orderId, String closedAt, int lineItems, BigDecimal tip, List<String> tenderTypes) {}
}
