package com.salonreview.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SquareBookingMirrorIngestServiceTest {

    private SquareClient square;
    private SquareBookingMirrorRepository repository;
    private SquareBookingMirrorIngestService ingest;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(1L)).thenReturn(square);
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(1L);
        repository = mock(SquareBookingMirrorRepository.class);
        ingest = new SquareBookingMirrorIngestService(provider, repository, ctx, new ObjectMapper());
    }

    private static Booking booking(String id, String customerId) {
        return new Booking(id, "ACCEPTED", "2026-06-01T15:00:00Z", "2026-05-01T00:00:00Z",
                "2026-05-01T00:00:00Z", "LOC1", customerId, "cashew $80", null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
    }

    @Test
    @DisplayName("ingestWindow upserts every booking from the location-wide call, never bookingsForCustomer")
    void ingestWindowUpsertsEveryBooking() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");
        when(square.bookings(from, to)).thenReturn(List.of(booking("bk1", "CUST1"), booking("bk2", "CUST2")));

        int count = ingest.ingestWindow(from, to);

        assertThat(count).isEqualTo(2);
        verify(repository).upsert(eq(1L), eq("bk1"), eq("CUST1"), eq("ACCEPTED"),
                any(), any(), any(), eq("LOC1"), eq("cashew $80"), eq(null), anyString());
        verify(repository).upsert(eq(1L), eq("bk2"), eq("CUST2"), eq("ACCEPTED"),
                any(), any(), any(), eq("LOC1"), eq("cashew $80"), eq(null), anyString());
        verify(square, times(0)).bookingsForCustomer(any(), any());
    }

    @Test
    @DisplayName("a booking with no appointment segments serializes to a null segments payload, not an error")
    void nullSegmentsHandledGracefully() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");
        Booking noSegments = new Booking("bk3", "ACCEPTED", "2026-06-01T15:00:00Z", null, null,
                "LOC1", "CUST3", null, null, null);
        when(square.bookings(from, to)).thenReturn(List.of(noSegments));

        ingest.ingestWindow(from, to);

        verify(repository).upsert(eq(1L), eq("bk3"), eq("CUST3"), eq("ACCEPTED"),
                any(), eq(null), eq(null), eq("LOC1"), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("backfillHistory ingests multiple months and isolates one month's failure from the rest")
    void backfillHistoryIsolatesPerMonthFailures() {
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any()))
                .thenThrow(new RuntimeException("Square down"))
                .thenReturn(List.of(booking("bkOk", "CUSTOK")));

        ingest.backfillHistory(2);

        // Both months attempted despite the first throwing.
        verify(square, times(2)).bookings(any(), any());
        verify(repository).upsert(eq(1L), eq("bkOk"), eq("CUSTOK"), any(), any(), any(), any(),
                any(), any(), any(), anyString());
    }
}
