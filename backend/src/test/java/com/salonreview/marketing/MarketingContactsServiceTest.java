package com.salonreview.marketing;

import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.marketing.MarketingContactsRepository.RawSubmission;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import com.salonreview.web.dto.MarketingContactHistoryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingContactsServiceTest {

    private MarketingContactsRepository repository;
    private SquareClient square;
    private MarketingContactsService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketingContactsRepository.class);
        square = mock(SquareClient.class);
        service = new MarketingContactsService(repository, square);
    }

    @Test
    @DisplayName("a contact with a booking exposes hasAppointment=true and a Square profile link")
    void bookedContactHasAppointmentAndProfileLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(new RawContact(
                id, "(858) 555-0100", "Jane", "jane@example.com",
                "instagram / paid / promo", "google / cpc / retargeting",
                "mani", "Version_1",
                "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                true, true,
                "SQCUST123", "SQBOOK456", "ACCEPTED", Instant.parse("2026-07-31T17:00:00Z"),
                "Manicure", new BigDecimal("85.00"), "Susan A.",
                Instant.parse("2026-07-01T00:00:00Z")
        )));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isTrue();
        Contact c = dto.contacts().get(0);
        assertThat(c.hasAppointment()).isTrue();
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/SQCUST123");
        assertThat(c.originalTrafficSource()).isEqualTo("instagram / paid / promo");
        assertThat(c.marketingTrafficSource()).isEqualTo("google / cpc / retargeting");
        assertThat(c.landingPageSlug()).isEqualTo("mani");
        assertThat(c.variantName()).isEqualTo("Version_1");
        assertThat(c.deviceType()).isEqualTo("mobile");
        assertThat(c.osName()).isEqualTo("iOS");
        assertThat(c.bookingPrice()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("a lead with no booking yet has hasAppointment=false and no Square link")
    void leadWithoutBookingHasNoAppointmentOrLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(new RawContact(
                id, "(858) 555-0100", "Jane", null,
                "instagram / paid / promo", "instagram / paid / promo",
                "mani", "Version_1",
                "desktop", "Windows", "10", "Chrome", "126",
                null, null,
                null, null, null, null,
                null, null, null,
                Instant.parse("2026-07-01T00:00:00Z")
        )));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.hasAppointment()).isFalse();
        assertThat(c.squareProfileUrl()).isNull();
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.listAll()).thenThrow(new DataAccessResourceFailureException("relation \"marketing.contacts\" does not exist"));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isFalse();
        assertThat(dto.contacts()).isEmpty();
    }

    private static RawContact rawContact(UUID id, String squareCustomerId) {
        return new RawContact(
                id, "(858) 555-0100", "Jane", "jane@example.com",
                "instagram / paid / promo", "instagram / paid / promo",
                "mani", "Version_1",
                "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                true, true,
                squareCustomerId, null, null, null,
                null, null, null,
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }

    @Test
    @DisplayName("history() 404s for an unknown contact")
    void historyNotFoundForUnknownContact() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such contact");
    }

    @Test
    @DisplayName("history() returns submissions with empty appointments when there's no Square customer")
    void historyWithoutSquareCustomerHasEmptyAppointments() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(rawContact(id, null)));
        when(repository.findSubmissionHistory("(858) 555-0100", "jane@example.com")).thenReturn(List.of(
                new RawSubmission("step1", Instant.parse("2026-07-01T00:00:00Z"), "mani", "Version_1",
                        "google", "cpc", "promo", null, null)
        ));

        MarketingContactHistoryDto history = service.history(id);

        assertThat(history.submissions()).hasSize(1);
        assertThat(history.submissions().get(0).submissionType()).isEqualTo("step1");
        assertThat(history.appointments()).isEmpty();
    }

    @Test
    @DisplayName("history() resolves Square bookings into service name, price, and provider name")
    void historyResolvesSquareAppointments() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(rawContact(id, "SQCUST123")));
        when(repository.findSubmissionHistory("(858) 555-0100", "jane@example.com")).thenReturn(List.of());

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        when(square.bookingsForCustomer("SQCUST123")).thenReturn(List.of(booking));
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember("TM1", "Susan", "A.", "ACTIVE", false, null, null)));
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of("VAR1", "Manicure"));
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of("VAR1", new BigDecimal("85.00")));

        MarketingContactHistoryDto history = service.history(id);

        assertThat(history.appointments()).hasSize(1);
        var appt = history.appointments().get(0);
        assertThat(appt.bookingId()).isEqualTo("SQBOOK1");
        assertThat(appt.serviceName()).isEqualTo("Manicure");
        assertThat(appt.price()).isEqualByComparingTo("85.00");
        assertThat(appt.artistName()).isEqualTo("Susan A.");
    }

    @Test
    @DisplayName("history() yields empty appointments, not a thrown exception, when Square is unreachable")
    void historyToleratesSquareFailure() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(rawContact(id, "SQCUST123")));
        when(repository.findSubmissionHistory("(858) 555-0100", "jane@example.com")).thenReturn(List.of());
        when(square.bookingsForCustomer("SQCUST123")).thenThrow(new RuntimeException("Square unreachable"));

        MarketingContactHistoryDto history = service.history(id);

        assertThat(history.appointments()).isEmpty();
    }
}
