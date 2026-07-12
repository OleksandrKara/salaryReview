package com.salonreview.marketing;

import com.salonreview.domain.MarketingContactSquareLink;
import com.salonreview.marketing.MarketingContactsRepository.RawAppointmentSubmission;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.marketing.MarketingContactsRepository.RawSubmission;
import com.salonreview.repo.MarketingContactSquareLinkRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingContactsServiceTest {

    private MarketingContactsRepository repository;
    private MarketingContactSquareLinkRepository squareLinks;
    private SquareClient square;
    private MarketingContactsService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketingContactsRepository.class);
        squareLinks = mock(MarketingContactSquareLinkRepository.class);
        square = mock(SquareClient.class);
        service = new MarketingContactsService(repository, squareLinks, square);
        when(repository.findSubmissionHistory(any())).thenReturn(List.of());
        when(repository.findSubmissionsByBookingIds(any())).thenReturn(Map.of());
        when(squareLinks.findByPhoneNumber(any())).thenReturn(Optional.empty());
    }

    private static RawContact rawContact(UUID id, String squareCustomerId) {
        return new RawContact(
                id, "(858) 555-0100", "Jane", "jane@example.com",
                "instagram / paid / promo", "google / cpc / retargeting",
                "google_ads",
                "google", "cpc", "retargeting",
                "mani", "Version_1",
                "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                true, true,
                squareCustomerId, null, null, null,
                null, null, null,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
    }

    @Test
    @DisplayName("a contact with a known Square customer gets a profile link")
    void contactWithSquareCustomerHasProfileLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of());

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isTrue();
        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/directory/customer/SQCUST123");
        assertThat(c.originalTrafficSource()).isEqualTo("instagram / paid / promo");
        assertThat(c.marketingTrafficSource()).isEqualTo("google / cpc / retargeting");
        assertThat(c.channel()).isEqualTo("google_ads");
        assertThat(c.landingPageSlug()).isEqualTo("mani");
        assertThat(c.variantName()).isEqualTo("Version_1");
        assertThat(c.deviceType()).isEqualTo("mobile");
        assertThat(c.osName()).isEqualTo("iOS");
        assertThat(c.utmSource()).isEqualTo("google");
        assertThat(c.utmMedium()).isEqualTo("cpc");
        assertThat(c.utmCampaign()).isEqualTo("retargeting");
        assertThat(c.updatedAt()).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
    }

    @Test
    @DisplayName("a lead with no known Square customer has no profile link and an empty appointments list")
    void leadWithoutSquareCustomerHasNoLinkOrAppointments() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isNull();
        assertThat(c.appointments()).isEmpty();
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.listAll()).thenThrow(new DataAccessResourceFailureException("relation \"marketing.contacts\" does not exist"));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isFalse();
        assertThat(dto.contacts()).isEmpty();
    }

    @Test
    @DisplayName("submissions come from our own DB regardless of whether a Square customer is known")
    void submissionsAlwaysPopulated() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(repository.findSubmissionHistory("(858) 555-0100")).thenReturn(List.of(
                new RawSubmission("step1", Instant.parse("2026-07-01T00:00:00Z"), "mani", "Version_1",
                        "google / cpc / promo", "google", "cpc", "promo", null, null)
        ));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.submissions()).hasSize(1);
        assertThat(c.submissions().get(0).submissionType()).isEqualTo("step1");
        assertThat(c.submissions().get(0).trafficSource()).isEqualTo("google / cpc / promo");
        assertThat(c.submissions().get(0).utmCampaign()).isEqualTo("promo");
    }

    @Test
    @DisplayName("appointments resolve Square bookings into service name, price, and provider name")
    void appointmentsResolveFromSquare() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(booking));
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember("TM1", "Susan", "A.", "ACTIVE", false, null, null)));
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of("VAR1", "Manicure"));
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of("VAR1", new BigDecimal("85.00")));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.appointments()).hasSize(1);
        var appt = c.appointments().get(0);
        assertThat(appt.bookingId()).isEqualTo("SQBOOK1");
        assertThat(appt.serviceName()).isEqualTo("Manicure");
        assertThat(appt.price()).isEqualByComparingTo("85.00");
        assertThat(appt.artistName()).isEqualTo("Susan A.");
        assertThat(appt.trafficSource()).isNull();
        assertThat(appt.submissionOccurredAt()).isNull();
    }

    @Test
    @DisplayName("an appointment that came through our own funnel is enriched with its originating submission's traffic/device info")
    void appointmentEnrichedWithOriginatingSubmission() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(booking));
        when(square.allTeamMembers()).thenReturn(List.of());
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of());
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of());
        when(repository.findSubmissionsByBookingIds(List.of("SQBOOK1"))).thenReturn(Map.of(
                "SQBOOK1", new RawAppointmentSubmission("SQBOOK1", Instant.parse("2026-07-30T10:00:00Z"),
                        "google / cpc / promo", "mobile", "iOS", "17.5", "Mobile Safari")
        ));

        MarketingContactDto dto = service.contacts();

        var appt = dto.contacts().get(0).appointments().get(0);
        assertThat(appt.trafficSource()).isEqualTo("google / cpc / promo");
        assertThat(appt.deviceType()).isEqualTo("mobile");
        assertThat(appt.osName()).isEqualTo("iOS");
        assertThat(appt.submissionOccurredAt()).isEqualTo(Instant.parse("2026-07-30T10:00:00Z"));
    }

    @Test
    @DisplayName("yields an empty appointments list, not a thrown exception, when Square is unreachable")
    void toleratesSquareFailure() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenThrow(new RuntimeException("Square unreachable"));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isTrue();
        assertThat(dto.contacts().get(0).appointments()).isEmpty();
    }

    @Test
    @DisplayName("a lead with no stored Square id, but a previously-cached phone link, shows appointments through it")
    void usesCachedLinkAsFallback() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(squareLinks.findByPhoneNumber("(858) 555-0100")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0100").squareCustomerId("SQCUST999")
                        .lastSyncedAt(Instant.now()).build()));
        when(square.bookingsForCustomer(eq("SQCUST999"), any())).thenReturn(List.of());

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/directory/customer/SQCUST999");
    }

    @Test
    @DisplayName("sync resolves an unlinked lead's Square customer by phone and caches it")
    void syncResolvesUnlinkedLeadByPhone() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(square.customerIdsForPhone("(858) 555-0100")).thenReturn(List.of("SQCUST777"));
        when(square.bookingsForCustomer(eq("SQCUST777"), any())).thenReturn(List.of());

        service.syncSquareLinks();

        var captor = org.mockito.ArgumentCaptor.forClass(MarketingContactSquareLink.class);
        verify(squareLinks).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo("(858) 555-0100");
        assertThat(captor.getValue().getSquareCustomerId()).isEqualTo("SQCUST777");
    }

    @Test
    @DisplayName("sync skips a contact that already has a stored Square id or an already-cached link, and busts the Square cache first")
    void syncSkipsAlreadyLinkedContacts() {
        UUID linkedId = UUID.randomUUID();
        UUID cachedId = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(
                rawContact(linkedId, "SQCUST_STORED"),
                new RawContact(cachedId, "(858) 555-0200", "Ann", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "mani", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, null, null, null, null, null, null, null,
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"))
        ));
        when(square.bookingsForCustomer(any(), any())).thenReturn(List.of());
        when(squareLinks.findByPhoneNumber("(858) 555-0200")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0200").squareCustomerId("SQCUST_CACHED")
                        .lastSyncedAt(Instant.now()).build()));

        service.syncSquareLinks();

        verify(square, never()).customerIdsForPhone(any());
        verify(squareLinks, never()).save(any());
        verify(square).invalidate();
    }

    @Test
    @DisplayName("counts a contact's real appointment as a follow-up booking when its booking_id isn't in the attributed set")
    void followUpCountsRealAppointmentNotInAttribution() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, java.util.Set.of("OTHERBOOK"));

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("does not count a booking that's already in the attributed set")
    void followUpSkipsAlreadyAttributedBooking() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("TRACKEDBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, java.util.Set.of("TRACKEDBOOK"));

        assertThat(byVariant).isEmpty();
    }

    @Test
    @DisplayName("does not count a cancelled booking as a follow-up conversion")
    void followUpIgnoresCancelledBookings() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking cancelled = new Booking("CANCELLEDBOOK", "CANCELLED_BY_SELLER", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(cancelled));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, java.util.Set.of());

        assertThat(byVariant).isEmpty();
    }

    @Test
    @DisplayName("a lead resolved only through the cached Sync link (never had a stored square_customer_id) also counts")
    void followUpCountsLeadResolvedThroughSyncLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(squareLinks.findByPhoneNumber("(858) 555-0100")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0100").squareCustomerId("SQCUST999")
                        .lastSyncedAt(Instant.now()).build()));
        Booking accepted = new Booking("PHONEBOOKED", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST999", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST999"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, java.util.Set.of());

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("scopes follow-up counting to the requested landing page slug and statsSince cutoff")
    void followUpRespectsSlugAndStatsSinceScope() {
        UUID otherPageId = UUID.randomUUID();
        UUID oldContactId = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(
                new RawContact(otherPageId, "(858) 555-0300", "Other", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "home", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, "SQCUST_OTHERPAGE", null, null, null, null, null, null,
                        Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z")),
                new RawContact(oldContactId, "(858) 555-0400", "Old", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "mani", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, "SQCUST_OLD", null, null, null, null, null, null,
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))
        ));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant(
                "mani", Instant.parse("2026-07-01T00:00:00Z"), java.util.Set.of());

        assertThat(byVariant).isEmpty();
        verify(square, never()).bookingsForCustomer(eq("SQCUST_OTHERPAGE"), any());
        verify(square, never()).bookingsForCustomer(eq("SQCUST_OLD"), any());
    }
}
