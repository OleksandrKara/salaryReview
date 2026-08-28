package com.salonreview.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SquareBookingMirror.Segment;
import com.salonreview.repo.SquareBookingMirrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Populates {@code square_booking} — a raw, unmatched local copy of Square's own Bookings — from
 * the existing efficient, location-wide {@link SquareClient#bookings(Instant, Instant)} call
 * (never the per-customer {@code bookingsForCustomer}, which is exactly the live-call-per-contact
 * pattern this mirror exists to replace; see the Phase 1 sync plan). Idempotent: every row is
 * upserted by its natural key (business + Square booking id), so backfill, the webhook path, and
 * the reconciliation sweep can all safely re-ingest overlapping windows.
 */
@Service
public class SquareBookingMirrorIngestService {

    private static final Logger log = LoggerFactory.getLogger(SquareBookingMirrorIngestService.class);

    private final SquareClientProvider squareClientProvider;
    private final SquareBookingMirrorRepository repository;
    private final CurrentBusinessContext currentBusinessContext;
    private final ObjectMapper mapper;

    public SquareBookingMirrorIngestService(SquareClientProvider squareClientProvider,
                                            SquareBookingMirrorRepository repository,
                                            CurrentBusinessContext currentBusinessContext,
                                            ObjectMapper mapper) {
        this.squareClientProvider = squareClientProvider;
        this.repository = repository;
        this.currentBusinessContext = currentBusinessContext;
        this.mapper = mapper;
    }

    /** Ingests every booking in [from, to) for the current business — one location-wide Square
     * call (cached 10 minutes by {@link SquareClient} itself), then one upsert per booking. */
    public int ingestWindow(Instant from, Instant to) {
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);
        List<SquareClient.Booking> bookings = square.bookings(from, to);
        for (SquareClient.Booking b : bookings) {
            repository.upsert(businessId, b.id(), b.customerId(), b.status(),
                    parseInstant(b.startAt()), parseInstant(b.createdAt()), parseInstant(b.updatedAt()),
                    b.locationId(), b.sellerNote(), b.customerNote(), segmentsJson(b));
        }
        return bookings.size();
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
                log.info("square_booking backfill {} — {} bookings", ym, count);
            } catch (RuntimeException ex) {
                log.warn("square_booking backfill failed for {}: {}", ym, ex.toString());
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
