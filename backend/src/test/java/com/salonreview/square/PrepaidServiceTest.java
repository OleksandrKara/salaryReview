package com.salonreview.square;

import com.salonreview.domain.PrepaidPackage;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PrepaidPackageRepository;
import com.salonreview.repo.PrepaidRedemptionRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.PrepaidService.Candidate;
import com.salonreview.square.SquareClient.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Prepaid candidates span ALL providers the prepaid customer visited (not just one), across months —
 * the prepaid package is no longer tied to a single provider.
 */
class PrepaidServiceTest {

    private static Booking booking(String id, String start, String teamMemberId, String variationId) {
        return new Booking(id, "ACCEPTED", start, null, "LOC", "C1", null, null,
                List.of(new AppointmentSegment(teamMemberId, variationId, 60)));
    }

    @Test
    @DisplayName("Candidates include every provider the customer saw, across months")
    void candidatesSpanProvidersAndMonths() {
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);

        PrepaidService svc = new PrepaidService(square, providers, directory, salonConfig, packages, redemptions);

        PrepaidPackage pkg = PrepaidPackage.builder().id(1L).customerId("C1").customerName("Alina")
                .paidDate(LocalDate.of(2026, 3, 1)).amount(new BigDecimal("300")).totalServices(3).build();
        when(packages.findById(1L)).thenReturn(Optional.of(pkg));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findById(1)).thenReturn(Optional.of(sc));

        when(square.locationTimeZone()).thenReturn("UTC");
        // Two visits, two providers, two months — neither paid through the till.
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bkApr", "2026-04-15T15:00:00Z", "TM1", "VAR1"),
                booking("bkMay", "2026-05-10T15:00:00Z", "TM2", "VAR2")));
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember("TM1", "Alice", "A", "ACTIVE", false, null, null),
                new TeamMember("TM2", "Bob", "B", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00"), "VAR2", new BigDecimal("80.00")));
        when(square.catalogNames(any())).thenReturn(Map.of("VAR1", "Mani", "VAR2", "Pedi"));
        when(redemptions.existsBySquareBookingIdAndServiceVariationId(any(), any())).thenReturn(false);

        List<Candidate> candidates = svc.candidates(1L);

        assertThat(candidates).hasSize(2);
        Candidate apr = candidates.stream().filter(c -> c.date().equals("2026-04-15")).findFirst().orElseThrow();
        Candidate may = candidates.stream().filter(c -> c.date().equals("2026-05-10")).findFirst().orElseThrow();
        assertThat(apr.providerName()).isEqualTo("Alice A");
        assertThat(apr.teamMemberId()).isEqualTo("TM1");
        assertThat(may.providerName()).isEqualTo("Bob B"); // the May visit with a DIFFERENT provider now shows
        assertThat(may.teamMemberId()).isEqualTo("TM2");
    }
}
