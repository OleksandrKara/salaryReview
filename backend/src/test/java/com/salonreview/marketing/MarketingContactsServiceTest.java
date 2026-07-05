package com.salonreview.marketing;

import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingContactsServiceTest {

    private MarketingContactsRepository repository;
    private MarketingContactsService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketingContactsRepository.class);
        service = new MarketingContactsService(repository);
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
}
