package com.salonreview.square.webhook;

import com.salonreview.square.SquareBookingMirrorIngestService;
import com.salonreview.square.SquareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Mirrors a {@code booking.created}/{@code booking.updated} webhook event into {@code
 * square_booking} — see {@link SquareWebhookEvent.Booking}'s own doc for why no extra Square call
 * is needed here (the full booking is already inline in the payload). Best-effort: a failure here
 * never fails the webhook response (Square would just retry-storm an endpoint that 500s), and the
 * reconciliation sweep (Phase 1 plan's 1d) catches anything missed.
 */
@Service
public class SquareBookingWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(SquareBookingWebhookHandler.class);

    private final SquareBookingMirrorIngestService ingest;

    public SquareBookingWebhookHandler(SquareBookingMirrorIngestService ingest) {
        this.ingest = ingest;
    }

    public void handleBookingEvent(Long businessId, SquareWebhookEvent.Booking booking) {
        if (booking == null || booking.id() == null) return;
        try {
            List<SquareClient.AppointmentSegment> segments = booking.appointmentSegments();
            SquareClient.Booking mapped = new SquareClient.Booking(booking.id(), booking.status(),
                    booking.startAt(), booking.createdAt(), booking.updatedAt(), booking.locationId(),
                    booking.customerId(), booking.sellerNote(), booking.customerNote(), segments);
            ingest.upsertBooking(businessId, mapped);
        } catch (RuntimeException ex) {
            log.warn("Failed to mirror booking {} for business {} from webhook (reconciliation will "
                    + "catch it): {}", booking.id(), businessId, ex.toString());
        }
    }
}
