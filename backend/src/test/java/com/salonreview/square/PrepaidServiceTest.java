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
        return new Booking(id, "ACCEPTED", start, null, null, "LOC", "C1", null, null,
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
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);

        PrepaidService svc = new PrepaidService(square, providers, directory, salonConfig,
                currentBusinessContext, packages, redemptions);

        PrepaidPackage pkg = PrepaidPackage.builder().id(1L).customerId("C1").customerName("Alina")
                .paidDate(LocalDate.of(2026, 3, 1)).amount(new BigDecimal("300")).totalServices(3).build();
        when(packages.findById(1L)).thenReturn(Optional.of(pkg));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(sc));

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

    @Test
    @DisplayName("Invoice lookup maps the total (sum of payment requests) and the created date")
    void invoiceLookupMapsTotalAndDate() {
        SquareClient square = mock(SquareClient.class);
        PrepaidService svc = new PrepaidService(square, mock(ProviderRepository.class), mock(ProviderDirectory.class),
                mock(SalonConfigRepository.class), mock(com.salonreview.config.CurrentBusinessContext.class),
                mock(PrepaidPackageRepository.class), mock(PrepaidRedemptionRepository.class));

        Invoice paid = new Invoice("inv1", "000089", "Prepaid", "PAID", "2026-05-29T10:00:00Z",
                List.of(new PaymentRequest(new Money(2500L, "USD")), new PaymentRequest(new Money(1500L, "USD"))));
        Invoice unpaid = new Invoice("inv2", "000090", "Pending", "UNPAID", "2026-05-30T10:00:00Z",
                List.of(new PaymentRequest(new Money(9900L, "USD"))));
        when(square.invoicesForCustomer("C1")).thenReturn(List.of(paid, unpaid));

        List<PrepaidService.InvoiceMatch> out = svc.invoices("C1");

        assertThat(out).hasSize(1);                                     // UNPAID is filtered out
        assertThat(out.get(0).number()).isEqualTo("000089");
        assertThat(out.get(0).date()).isEqualTo("2026-05-29");          // created_at date only
        assertThat(out.get(0).amount()).isEqualByComparingTo("40.00");  // 25.00 + 15.00
    }
}
