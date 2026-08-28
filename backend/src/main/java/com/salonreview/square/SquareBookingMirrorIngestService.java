package com.salonreview.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SquareBookingMirror.Segment;
import com.salonreview.domain.SquareOrderMirror;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareOrderMirrorRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Populates {@code square_booking}/{@code square_order}/{@code square_payment} — raw, unmatched
 * local copies of Square's own Bookings/Orders/Payments — from the existing efficient,
 * location-wide {@link SquareClient#bookings(Instant, Instant)}/{@code completedOrders}/
 * {@code payments} calls (never the per-customer {@code bookingsForCustomer}, which is exactly the
 * live-call-per-contact pattern this mirror exists to replace; see the Phase 1 sync plan).
 * Idempotent: every row is upserted by its natural key (business + Square's own id), so backfill,
 * the webhook path, and the reconciliation sweep can all safely re-ingest overlapping windows.
 */
@Service
public class SquareBookingMirrorIngestService {

    private static final Logger log = LoggerFactory.getLogger(SquareBookingMirrorIngestService.class);

    private final SquareClientProvider squareClientProvider;
    private final SquareBookingMirrorRepository repository;
    private final SquareOrderMirrorRepository orderRepository;
    private final SquarePaymentMirrorRepository paymentRepository;
    private final CurrentBusinessContext currentBusinessContext;
    // Not Spring-injected — same convention as RagSuggestionService/TelegramNotificationService's
    // own ObjectMapper fields. This is purely internal serialization (our own JSON in, our own JSON
    // out, via the mirror entities' JSONB columns), not a bean shared with request handling.
    private final ObjectMapper mapper = new ObjectMapper();

    public SquareBookingMirrorIngestService(SquareClientProvider squareClientProvider,
                                            SquareBookingMirrorRepository repository,
                                            SquareOrderMirrorRepository orderRepository,
                                            SquarePaymentMirrorRepository paymentRepository,
                                            CurrentBusinessContext currentBusinessContext) {
        this.squareClientProvider = squareClientProvider;
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.currentBusinessContext = currentBusinessContext;
    }

    /** Ingests every booking, completed order, and payment in [from, to) for the current business
     * — three location-wide Square calls (each already cached 10 minutes by {@link SquareClient}
     * itself), then one upsert per row. */
    public int ingestWindow(Instant from, Instant to) {
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);

        List<SquareClient.Booking> bookings = square.bookings(from, to);
        for (SquareClient.Booking b : bookings) {
            repository.upsert(businessId, b.id(), b.customerId(), b.status(),
                    parseInstant(b.startAt()), parseInstant(b.createdAt()), parseInstant(b.updatedAt()),
                    b.locationId(), b.sellerNote(), b.customerNote(), segmentsJson(b));
        }

        List<SquareClient.Order> orders = square.completedOrders(from, to);
        for (SquareClient.Order o : orders) {
            orderRepository.upsert(businessId, o.id(), o.customerId(), o.state(),
                    parseInstant(o.closedAt()), parseInstant(o.createdAt()),
                    SquareClient.toDollars(o.totalTipMoney()), SquareClient.toDollars(o.totalDiscountMoney()),
                    tendersJson(o), lineItemsJson(o));
        }

        List<SquareClient.Payment> payments = square.payments(from, to);
        for (SquareClient.Payment p : payments) {
            paymentRepository.upsert(businessId, p.id(), p.orderId(), p.customerId(), p.status(),
                    parseInstant(p.createdAt()), SquareClient.toDollars(p.totalMoney()), SquareClient.toDollars(p.tipMoney()));
        }

        return bookings.size() + orders.size() + payments.size();
    }

    /** Backfills the last {@code months} months, one {@link #ingestWindow} call per month — bounded,
     * idempotent (safe to re-run; every row upserts by its natural key regardless of how many times
     * a given month has already been ingested). */
    public void backfillHistory(int months) {
        ZoneId zone = salonZone();
        YearMonth cursor = YearMonth.now(zone);
        for (int i = 0; i < months; i++) {
            YearMonth ym = cursor.minusMonths(i);
            Instant from = ym.atDay(1).atStartOfDay(zone).toInstant();
            Instant to = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();
            try {
                int count = ingestWindow(from, to);
                log.info("square_booking mirror backfill {} — {} rows", ym, count);
            } catch (RuntimeException ex) {
                log.warn("square_booking mirror backfill failed for {}: {}", ym, ex.toString());
            }
        }
    }

    private String segmentsJson(SquareClient.Booking b) {
        if (b.appointmentSegments() == null) return null;
        try {
            List<Segment> segments = b.appointmentSegments().stream()
                    .map(s -> new Segment(s.teamMemberId(), s.serviceVariationId(), s.durationMinutes()))
                    .toList();
            return mapper.writeValueAsString(segments);
        } catch (Exception ex) {
            log.warn("Failed to serialize appointment segments for booking {}: {}", b.id(), ex.toString());
            return null;
        }
    }

    private String tendersJson(SquareClient.Order o) {
        if (o.tenders() == null) return null;
        try {
            List<SquareOrderMirror.Tender> tenders = o.tenders().stream()
                    .map(t -> new SquareOrderMirror.Tender(t.type(), SquareClient.toDollars(t.amountMoney())))
                    .toList();
            return mapper.writeValueAsString(tenders);
        } catch (Exception ex) {
            log.warn("Failed to serialize tenders for order {}: {}", o.id(), ex.toString());
            return null;
        }
    }

    private String lineItemsJson(SquareClient.Order o) {
        if (o.lineItems() == null) return null;
        try {
            List<SquareOrderMirror.LineItem> items = o.lineItems().stream()
                    .map(li -> new SquareOrderMirror.LineItem(li.catalogObjectId(),
                            SquareClient.toDollars(li.grossSalesMoney()), SquareClient.toDollars(li.totalMoney()),
                            SquareClient.toDollars(li.totalDiscountMoney())))
                    .toList();
            return mapper.writeValueAsString(items);
        } catch (Exception ex) {
            log.warn("Failed to serialize line items for order {}: {}", o.id(), ex.toString());
            return null;
        }
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception ex) {
            return null;
        }
    }

    private ZoneId salonZone() {
        try {
            String tz = squareClientProvider.forBusiness(currentBusinessContext.id()).locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
