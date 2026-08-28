package com.salonreview.square.webhook;

import com.salonreview.square.SquareBookingMirrorIngestService;
import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SquareBookingWebhookHandlerTest {

    @Test
    void mapsAndUpsertsTheFullInlineBookingWithNoExtraSquareCall() {
        SquareBookingMirrorIngestService ingest = mock(SquareBookingMirrorIngestService.class);
        SquareBookingWebhookHandler handler = new SquareBookingWebhookHandler(ingest);
        var segments = List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60));
        var webhookBooking = new SquareWebhookEvent.Booking("bk1", "ACCEPTED", "CUST1",
                "2026-06-01T15:00:00Z", "2026-05-01T00:00:00Z", "2026-05-01T00:00:00Z", "LOC1",
                "cashew $80", null, segments);

        handler.handleBookingEvent(1L, webhookBooking);

        var captor = org.mockito.ArgumentCaptor.forClass(SquareClient.Booking.class);
        verify(ingest).upsertBooking(eq(1L), captor.capture());
        SquareClient.Booking mapped = captor.getValue();
        assertThat(mapped.id()).isEqualTo("bk1");
        assertThat(mapped.customerId()).isEqualTo("CUST1");
        assertThat(mapped.status()).isEqualTo("ACCEPTED");
        assertThat(mapped.sellerNote()).isEqualTo("cashew $80");
        assertThat(mapped.appointmentSegments()).isEqualTo(segments);
    }

    @Test
    void nullBookingIsIgnored() {
        SquareBookingMirrorIngestService ingest = mock(SquareBookingMirrorIngestService.class);
        SquareBookingWebhookHandler handler = new SquareBookingWebhookHandler(ingest);

        handler.handleBookingEvent(1L, null);

        verifyNoInteractions(ingest);
    }

    @Test
    void ingestFailureIsSwallowedNotPropagated() {
        SquareBookingMirrorIngestService ingest = mock(SquareBookingMirrorIngestService.class);
        SquareBookingWebhookHandler handler = new SquareBookingWebhookHandler(ingest);
        var webhookBooking = new SquareWebhookEvent.Booking("bk1", "ACCEPTED", "CUST1",
                "2026-06-01T15:00:00Z", null, null, "LOC1", null, null, null);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(ingest).upsertBooking(eq(1L), org.mockito.ArgumentMatchers.any());

        handler.handleBookingEvent(1L, webhookBooking); // must not throw
    }
}
