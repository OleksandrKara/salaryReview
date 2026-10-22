package com.salonreview.square;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareOrderMirror;
import com.salonreview.domain.SquarePaymentMirror;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareOrderMirrorRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import com.salonreview.square.SquareClient.AppliedDiscount;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderLineItem;
import com.salonreview.square.SquareClient.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Test fixture adapter: the aggregator reads only the local Square mirror, so fixtures staged as
 * {@code square.bookings/completedOrders/payments(...)} stubs are served to it as mirror rows. */
final class MirrorFeed {

    private MirrorFeed() {}

    static SquareBookingMirrorRepository bookings(SquareClient square) {
        SquareBookingMirrorRepository repo = mock(SquareBookingMirrorRepository.class);
        when(repo.findByBusinessIdAndStartAtBetween(any(), any(), any())).thenAnswer(inv ->
                nz(square.bookings(inv.getArgument(1), inv.getArgument(2))).stream().map(MirrorFeed::toMirror).toList());
        return repo;
    }

    static SquareOrderMirrorRepository orders(SquareClient square) {
        SquareOrderMirrorRepository repo = mock(SquareOrderMirrorRepository.class);
        when(repo.findByBusinessIdAndClosedAtBetween(any(), any(), any())).thenAnswer(inv ->
                nz(square.completedOrders(inv.getArgument(1), inv.getArgument(2))).stream().map(MirrorFeed::toMirror).toList());
        return repo;
    }

    static SquarePaymentMirrorRepository payments(SquareClient square) {
        SquarePaymentMirrorRepository repo = mock(SquarePaymentMirrorRepository.class);
        when(repo.findByBusinessIdAndCreatedAtBetween(any(), any(), any())).thenAnswer(inv ->
                nz(square.payments(inv.getArgument(1), inv.getArgument(2))).stream().map(MirrorFeed::toMirror).toList());
        return repo;
    }

    private static <T> List<T> nz(List<T> l) { return l == null ? List.of() : l; }

    private static Instant ts(String iso) { return iso == null ? null : Instant.parse(iso); }

    private static BigDecimal d(SquareClient.Money m) { return m == null ? null : SquareClient.toDollars(m); }

    static SquareBookingMirror toMirror(Booking b) {
        return SquareBookingMirror.builder().businessId(1L).squareBookingId(b.id()).status(b.status())
                .startAt(ts(b.startAt())).createdAt(ts(b.createdAt())).updatedAt(ts(b.updatedAt()))
                .locationId(b.locationId()).squareCustomerId(b.customerId())
                .sellerNote(b.sellerNote()).customerNote(b.customerNote())
                .appointmentSegments(b.appointmentSegments() == null ? null : b.appointmentSegments().stream()
                        .map(s -> new SquareBookingMirror.Segment(s.teamMemberId(), s.serviceVariationId(), s.durationMinutes()))
                        .toList())
                .build();
    }

    static SquareOrderMirror toMirror(Order o) {
        return SquareOrderMirror.builder().businessId(1L).squareOrderId(o.id()).squareCustomerId(o.customerId())
                .state(o.state()).closedAt(ts(o.closedAt())).createdAt(ts(o.createdAt()))
                .totalTipMoney(d(o.totalTipMoney())).totalDiscountMoney(d(o.totalDiscountMoney()))
                .tenders(o.tenders() == null ? null : o.tenders().stream()
                        .map(t -> new SquareOrderMirror.Tender(t.type(), d(t.amountMoney()))).toList())
                .lineItems(o.lineItems() == null ? null : o.lineItems().stream().map(MirrorFeed::toMirror).toList())
                .discounts(o.discounts() == null ? null : o.discounts().stream()
                        .map(x -> new SquareOrderMirror.OrderDiscount(x.uid(), x.name(), d(x.appliedMoney()))).toList())
                .build();
    }

    private static SquareOrderMirror.LineItem toMirror(OrderLineItem li) {
        List<AppliedDiscount> ad = li.appliedDiscounts();
        return new SquareOrderMirror.LineItem(li.catalogObjectId(), li.name(), d(li.grossSalesMoney()),
                d(li.totalMoney()), d(li.totalDiscountMoney()),
                ad == null ? null : ad.stream()
                        .map(a -> new SquareOrderMirror.AppliedDiscount(a.uid(), a.discountUid(), d(a.appliedMoney()))).toList());
    }

    static SquarePaymentMirror toMirror(Payment p) {
        return SquarePaymentMirror.builder().businessId(1L).squarePaymentId(p.id()).squareOrderId(p.orderId())
                .squareCustomerId(p.customerId()).status(p.status()).createdAt(ts(p.createdAt()))
                .totalMoney(d(p.totalMoney())).tipMoney(d(p.tipMoney())).build();
    }
}
