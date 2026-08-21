package com.salonreview.square;

import com.salonreview.domain.MissedBooking;
import com.salonreview.repo.MissedBookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissedBookingServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private MissedBookingRepository missedBookings;
    private MissedBookingService service;

    @BeforeEach
    void setUp() {
        missedBookings = mock(MissedBookingRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        service = new MissedBookingService(missedBookings, currentBusinessContext);
    }

    @Test
    @DisplayName("create() rejects a missing date")
    void createRejectsMissingDate() {
        var req = new MissedBookingService.CreateRequest(null, null, new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> service.create(req, "manager")).isInstanceOf(ResponseStatusException.class);

        verify(missedBookings, never()).save(any());
    }

    @Test
    @DisplayName("create() rejects a zero/negative estimated revenue")
    void createRejectsNonPositiveRevenue() {
        var req = new MissedBookingService.CreateRequest(LocalDate.of(2026, 5, 10), null, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> service.create(req, "manager")).isInstanceOf(ResponseStatusException.class);

        verify(missedBookings, never()).save(any());
    }

    @Test
    @DisplayName("create() saves against the caller's own business, trims a blank optional service name to null")
    void createSavesForOwnBusiness() {
        when(missedBookings.save(any())).thenAnswer(inv -> {
            MissedBooking m = inv.getArgument(0);
            m.setId(9L);
            return m;
        });
        var req = new MissedBookingService.CreateRequest(LocalDate.of(2026, 5, 10), LocalTime.of(18, 0),
                new BigDecimal("150.00"), "  ");

        var view = service.create(req, "manager");

        var captor = org.mockito.ArgumentCaptor.forClass(MissedBooking.class);
        verify(missedBookings).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BUSINESS_ID);
        assertThat(view.id()).isEqualTo(9L);
        assertThat(view.requestedDate()).isEqualTo("2026-05-10");
        assertThat(view.requestedTime()).isEqualTo("18:00");
        assertThat(view.estimatedRevenue()).isEqualByComparingTo("150.00");
        assertThat(view.serviceName()).isNull();
        assertThat(view.createdBy()).isEqualTo("manager");
    }

    @Test
    @DisplayName("delete() 404s for a missed booking belonging to another business")
    void deleteRejectsAnotherBusinessesRow() {
        when(missedBookings.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ResponseStatusException.class);

        verify(missedBookings, never()).delete(any());
        verify(missedBookings, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete() succeeds for the caller's own business's row")
    void deleteSucceedsForOwnBusiness() {
        MissedBooking row = MissedBooking.builder().id(9L).businessId(BUSINESS_ID)
                .requestedDate(LocalDate.of(2026, 5, 10)).estimatedRevenue(new BigDecimal("150.00")).build();
        when(missedBookings.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(row));

        service.delete(9L);

        verify(missedBookings).delete(row);
    }
}
