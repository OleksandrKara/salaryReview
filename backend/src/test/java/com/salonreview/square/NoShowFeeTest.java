package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.*;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.NoShowFeeService.NoShowMonth;
import com.salonreview.square.NoShowFeeService.NoShowRow;
import com.salonreview.square.SettlementPreviewService.ProviderDetail;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** No-show fee tracking: detection/pairing (incl. split + month membership) and the full-$25 payout fold. */
class NoShowFeeTest {

    private static Money usd(long cents) { return new Money(cents, "USD"); }
    private static TeamMember member(String id, String first) {
        return new TeamMember(id, first, "X", "ACTIVE", false, null, null);
    }

    @Test
    @DisplayName("Detection: a paid $25 'Cancelation Policy' is split across a 2-provider no-show, in the paid month")
    void detectsSplitsAndScopesToPaidMonth() {
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        NoShowFeeOverrideRepository overrides = mock(NoShowFeeOverrideRepository.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        SalonConfig sc = SalonConfig.builder().businessId(1L).noShowFeeAmount(new BigDecimal("25.00")).build();
        when(salonConfigRepo.findByBusinessId(1L)).thenReturn(Optional.of(sc));

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(member("M1", "Susan"), member("M2", "Bayan")));
        when(square.customerNames(any())).thenReturn(Map.of("CUST1", "Test Customer 1"));
        when(overrides.findAllByBusinessId(1L)).thenReturn(List.of());

        // One NO_SHOW booking on May 10 with two providers (two segments), customer CUST1.
        Booking noShow = new Booking("BK1", "NO_SHOW", "2026-05-10T17:00:00Z", null, "2026-05-10T17:00:00Z", "LOC",
                "CUST1", null, null,
                List.of(new AppointmentSegment("M1", "V1", 60), new AppointmentSegment("M2", "V2", 60)));
        when(square.bookings(any(), any())).thenReturn(List.of(noShow));

        // A completed $25 "Cancelation Policy" order for CUST1, paid May 12.
        Order fee = new Order("O1", "LOC", "CUST1", "COMPLETED", "2026-05-12T20:00:00Z", "2026-05-12T20:00:00Z",
                List.of(new OrderLineItem("u1", "Cancelation Policy", "1", null, usd(2500), usd(2500), usd(2500), null, null)),
                null, null, List.of(), null, null);
        when(square.completedOrders(any(), any())).thenReturn(List.of(fee));

        when(directory.resolveOrCreate(eq("M1"), any())).thenReturn(Provider.builder().id(1L).displayName("Susan").build());
        when(directory.resolveOrCreate(eq("M2"), any())).thenReturn(Provider.builder().id(2L).displayName("Bayan").build());
        when(providers.findById(1L)).thenReturn(Optional.of(Provider.builder().id(1L).displayName("Susan").build()));
        when(providers.findById(2L)).thenReturn(Optional.of(Provider.builder().id(2L).displayName("Bayan").build()));

        NoShowFeeService svc = new NoShowFeeService(squareClientProvider, directory, providers, overrides,
                currentBusinessContext, salonConfigRepo);

        // In the payment month (May): $25 split evenly → $12.50 each, both CREDITED.
        NoShowMonth may = svc.compute(2026, 5);
        assertThat(may.linesByProvider().keySet()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(may.linesByProvider().get(1L).get(0).gross()).isEqualByComparingTo("12.50");
        assertThat(may.linesByProvider().get(2L).get(0).gross()).isEqualByComparingTo("12.50");
        assertThat(may.linesByProvider().get(1L).get(0).channel()).isEqualTo("NOSHOW");
        assertThat(may.rows()).hasSize(2).allMatch(r -> "CREDITED".equals(r.state()));

        // The no-show is in May, the fee is paid in May → it does NOT belong to April.
        assertThat(svc.compute(2026, 4).rows()).isEmpty();
    }

    @Test
    @DisplayName("Phase 4.4: no-show fee program off for this business (no configured amount) — "
            + "compute() short-circuits to empty without ever calling Square")
    void computeNoOpsWhenFeatureOff() {
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(2L);
        when(squareClientProvider.forBusiness(2L)).thenReturn(square);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.of(
                SalonConfig.builder().businessId(2L).noShowFeeAmount(null).build()));

        NoShowFeeService svc = new NoShowFeeService(squareClientProvider, mock(ProviderDirectory.class),
                mock(ProviderRepository.class), mock(NoShowFeeOverrideRepository.class),
                currentBusinessContext, salonConfigRepo);

        NoShowMonth result = svc.compute(2026, 5);

        assertThat(result.rows()).isEmpty();
        assertThat(result.linesByProvider()).isEmpty();
        verifyNoInteractions(square);
    }

    @Test
    @DisplayName("Phase 4.4: confirm() rejects with no amount and no configured business default")
    void confirmRejectsWithNoAmountAndNoDefault() {
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(2L);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.of(
                SalonConfig.builder().businessId(2L).noShowFeeAmount(null).build()));
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.existsById(1L)).thenReturn(true);

        NoShowFeeService svc = new NoShowFeeService(mock(SquareClientProvider.class), mock(ProviderDirectory.class),
                providers, mock(NoShowFeeOverrideRepository.class), currentBusinessContext, salonConfigRepo);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.confirm(
                new NoShowFeeService.ConfirmRequest("BK1", 1L, null, null, "Julia B.", null, null), "manager"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("amount is required");
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: confirm() on a bookingId that collides with another "
            + "business's override row does NOT take that row over — it creates/updates the "
            + "current business's own row instead")
    void confirmDoesNotTakeOverAnotherBusinessRow() {
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        when(salonConfigRepo.findByBusinessId(1L)).thenReturn(Optional.of(
                SalonConfig.builder().businessId(1L).noShowFeeAmount(new BigDecimal("25.00")).build()));
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.existsById(1L)).thenReturn(true);
        NoShowFeeOverrideRepository overrides = mock(NoShowFeeOverrideRepository.class);
        // Business-1-scoped lookup correctly misses, even though a row for the same bookingId
        // exists under business 2 (not stubbed here, so a leaked call would see nothing useful
        // either — the point is the business-1-scoped method is the one actually called).
        when(overrides.findByBusinessIdAndSquareBookingId(1L, "BK1")).thenReturn(Optional.empty());

        NoShowFeeService svc = new NoShowFeeService(mock(SquareClientProvider.class), mock(ProviderDirectory.class),
                providers, overrides, currentBusinessContext, salonConfigRepo);

        svc.confirm(new NoShowFeeService.ConfirmRequest("BK1", 1L, null, null, "Julia B.", null, null), "manager");

        org.mockito.ArgumentCaptor<com.salonreview.domain.NoShowFeeOverride> cap =
                org.mockito.ArgumentCaptor.forClass(com.salonreview.domain.NoShowFeeOverride.class);
        verify(overrides).save(cap.capture());
        assertThat(cap.getValue().getBusinessId()).isEqualTo(1L);
        assertThat(cap.getValue().getSquareBookingId()).isEqualTo("BK1");
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: clearOverride() deletes only the current business's "
            + "row for this bookingId, not any business's row sharing the same Square id")
    void clearOverrideIsScopedToCurrentBusiness() {
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        NoShowFeeOverrideRepository overrides = mock(NoShowFeeOverrideRepository.class);

        NoShowFeeService svc = new NoShowFeeService(mock(SquareClientProvider.class), mock(ProviderDirectory.class),
                mock(ProviderRepository.class), overrides, currentBusinessContext, mock(SalonConfigRepository.class));

        svc.clearOverride("BK1");

        verify(overrides).deleteByBusinessIdAndSquareBookingId(1L, "BK1");
    }

    @Test
    @DisplayName("Payout: a $25 no-show fee is paid to the provider in full (Zelle), not commissioned, no tier effect")
    void paysFullFeeViaAdjustment() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);
        NoShowFeeService noShowFees = mock(NoShowFeeService.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);

        SettlementPreviewService service = new SettlementPreviewService(aggregator, new TierCommissionEngine(),
                salonConfigRepo, directory, tierGrants, feedback, squareClientProvider, mock(PrepaidRedemptionRepository.class),
                mock(PrepaidPackageRepository.class), providerRepo, mock(RedoRepository.class),
                mock(ManualAdjustmentRepository.class), noShowFees, mock(SuspiciousBookingService.class),
                mock(com.salonreview.square.CancelledAppointmentService.class), currentBusinessContext);

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(new CommissionConfig(60,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350"), true));
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(new BigDecimal("0.0350"));
        when(sc.getOwnerShortName()).thenReturn("AK");
        when(salonConfigRepo.findByBusinessId(1L)).thenReturn(Optional.of(sc));
        when(tierGrants.findByBusinessIdAndYearAndMonth(1L, 2026, 5)).thenReturn(List.of());
        when(feedback.findByBusinessIdAndYearAndMonth(1L, 2026, 5)).thenReturn(List.of());
        when(square.customerNames(any())).thenReturn(Map.of());
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(
                new MonthAggregation(2026, 5, "UTC", List.of(), new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of()));
        when(providerRepo.findById(1L)).thenReturn(Optional.of(Provider.builder().id(1L).displayName("Susan").build()));

        // One $25 no-show credit (paid May 20 → SECOND half), folded as an adjustment.
        AttributedService line = new AttributedService("", "Susan", "2026-05-20", "SECOND",
                "No-show fee — Julia B.", new BigDecimal("25.00"), BigDecimal.ZERO, new BigDecimal("25.00"),
                BigDecimal.ZERO, false, 0, 1, false, "NOSHOW", null, "BK1", "CUST1", "Julia B.");
        NoShowRow row = new NoShowRow("BK1", 1L, "Susan", "Julia B.", "2026-05-20T17:00:00Z",
                "2026-05-20", new BigDecimal("25.00"), "2026-05-20", "CREDITED");
        when(noShowFees.compute(2026, 5)).thenReturn(new NoShowMonth(List.of(row), Map.of(1L, List.of(line))));

        ProviderDetail detail = service.providerDetail(2026, 5, 1L);

        AttributedService traced = detail.services().stream()
                .filter(s -> "NOSHOW".equals(s.channel())).findFirst().orElseThrow();
        assertThat(traced.gross()).isEqualByComparingTo("25.00");
        // The full $25 reaches Zelle (not a commission share); card revenue and counts are untouched.
        assertThat(detail.payout().secondHalf().zelleToProvider()).isEqualByComparingTo("25.00");
        assertThat(detail.payout().secondHalf().cardRevenue()).isEqualByComparingTo("0.00");
        assertThat(detail.payout().secondHalf().countedServices()).isEqualTo(0);
        assertThat(detail.payout().monthCountedServices()).isEqualTo(0);
        assertThat(detail.noShows()).hasSize(1);
    }
}
