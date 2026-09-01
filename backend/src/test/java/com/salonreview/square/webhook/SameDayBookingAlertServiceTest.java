package com.salonreview.square.webhook;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Tara Lumley incident (2026-09-01) this class exists to catch: a customer books, her
 * provider doesn't notice until very late. These tests drive {@link
 * SameDayBookingAlertService#handleBookingCreated} directly with fake webhook payloads — no HTTP,
 * no DB, matching every other webhook-handler test in this package. */
class SameDayBookingAlertServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private ProviderRepository providers;
    private SquareCustomerMirrorRepository customers;
    private TelegramNotificationService telegram;
    private SameDayBookingAlertService service;

    private void setUp() {
        providers = mock(ProviderRepository.class);
        customers = mock(SquareCustomerMirrorRepository.class);
        telegram = mock(TelegramNotificationService.class);
        service = new SameDayBookingAlertService(providers, customers, telegram);
    }

    private static SquareWebhookEvent.Booking booking(String startAt, String createdAt, String customerId,
                                                        List<SquareClient.AppointmentSegment> segments) {
        return new SquareWebhookEvent.Booking("bk_1", "ACCEPTED", customerId, startAt, createdAt, createdAt,
                null, null, null, segments);
    }

    @Test
    @DisplayName("booked 2h30m before start (inside the [2h,3h) window), known provider — alert fires with "
            + "lead time and provider name")
    void firesForGenuineLastMinuteBooking() {
        setUp();
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(
                Optional.of(Provider.builder().id(8L).displayName("Susan Alieva").build()));
        when(customers.findByBusinessIdAndSquareCustomerId(BUSINESS_ID, "CUST1")).thenReturn(
                Optional.of(SquareCustomerMirror.builder().givenName("Tara").familyName("Lumley").build()));

        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T15:30:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verify(telegram).sendSameDayBookingAlert(BUSINESS_ID, "Susan Alieva", "Tara Lumley",
                "2026-09-01T18:00:00Z", Duration.ofHours(2).plusMinutes(30));
    }

    @Test
    @DisplayName("booked with less than Square's own 2-hour minimum lead time — shouldn't be reachable in "
            + "practice (Square blocks it), but defensively still doesn't fire if it somehow happens")
    void belowTwoHourMinimumDoesNotFire() {
        setUp();
        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T17:15:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verifyNoInteractions(telegram);
        verifyNoInteractions(providers);
    }

    @Test
    @DisplayName("booked exactly at the 2-hour lower bound — this is the edge Square still allows, alert fires")
    void exactlyAtTwoHourLowerBoundFires() {
        setUp();
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(
                Optional.of(Provider.builder().id(8L).displayName("Susan Alieva").build()));

        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T16:00:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verify(telegram).sendSameDayBookingAlert(eq(BUSINESS_ID), eq("Susan Alieva"), any(), any(),
                eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("booked exactly at the 3-hour upper bound — no longer last-minute, no alert")
    void exactlyAtThresholdDoesNotFire() {
        setUp();
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(
                Optional.of(Provider.builder().id(8L).displayName("Susan Alieva").build()));

        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T15:00:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verifyNoInteractions(telegram);
    }

    @Test
    @DisplayName("booked well in advance — no alert")
    void bookedWellInAdvanceDoesNotFire() {
        setUp();
        SquareWebhookEvent.Booking b = booking("2026-09-05T18:00:00Z", "2026-09-01T15:00:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verifyNoInteractions(telegram);
        verifyNoInteractions(providers);
    }

    @Test
    @DisplayName("last-minute, but the team member doesn't map to any known Provider — no alert "
            + "(nothing useful to tell staff)")
    void unknownProviderDoesNotFire() {
        setUp();
        when(providers.findBySquareTeamMemberId("TM_UNKNOWN")).thenReturn(Optional.empty());

        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T15:30:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM_UNKNOWN", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verifyNoInteractions(telegram);
    }

    @Test
    @DisplayName("last-minute, no appointment segments at all (e.g. blocked time) — no alert")
    void noSegmentsDoesNotFire() {
        setUp();
        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T15:30:00Z", "CUST1", null);

        service.handleBookingCreated(BUSINESS_ID, b);

        verifyNoInteractions(telegram);
        verifyNoInteractions(providers);
    }

    @Test
    @DisplayName("customer not yet in the mirror — alert still fires, just without a client name")
    void unknownCustomerStillFiresWithNullName() {
        setUp();
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(
                Optional.of(Provider.builder().id(8L).displayName("Susan Alieva").build()));
        when(customers.findByBusinessIdAndSquareCustomerId(BUSINESS_ID, "CUST_NEW")).thenReturn(Optional.empty());

        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T15:30:00Z", "CUST_NEW",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verify(telegram).sendSameDayBookingAlert(eq(BUSINESS_ID), eq("Susan Alieva"), eq((String) null),
                any(), any());
    }

    @Test
    @DisplayName("a 4-hand booking (two segments, two different providers) — both names in the alert")
    void multiProviderBookingListsBothNames() {
        setUp();
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(
                Optional.of(Provider.builder().id(8L).displayName("Susan Alieva").build()));
        when(providers.findBySquareTeamMemberId("TM2")).thenReturn(
                Optional.of(Provider.builder().id(9L).displayName("Bayan").build()));

        SquareWebhookEvent.Booking b = booking("2026-09-01T18:00:00Z", "2026-09-01T15:30:00Z", "CUST1",
                List.of(new SquareClient.AppointmentSegment("TM1", "VAR1", 60),
                        new SquareClient.AppointmentSegment("TM2", "VAR2", 60)));

        service.handleBookingCreated(BUSINESS_ID, b);

        verify(telegram).sendSameDayBookingAlert(eq(BUSINESS_ID), eq("Susan Alieva, Bayan"), any(), any(), any());
    }

    @Test
    @DisplayName("malformed/missing timestamps never throw — just skip, no alert")
    void malformedTimestampsNeverThrow() {
        setUp();
        SquareWebhookEvent.Booking missingStart = booking(null, "2026-09-01T17:15:00Z", "CUST1", null);
        SquareWebhookEvent.Booking garbled = booking("not-a-timestamp", "2026-09-01T17:15:00Z", "CUST1", null);

        service.handleBookingCreated(BUSINESS_ID, missingStart);
        service.handleBookingCreated(BUSINESS_ID, garbled);

        verifyNoInteractions(telegram);
    }
}
