package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.PrepaidPackage;
import com.salonreview.domain.PrepaidRedemption;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.*;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SettlementPreviewService.ProviderDetail;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A discounted prepaid invoice (e.g. "Prepay for 3 sessions" −10%) shows the discount on the prepaid
 * draw-down line: discount = menu − what was paid per session (package amount / count). The provider is
 * still paid on the menu price (gross); the salon absorbs the discount — same as card/cash.
 */
class PrepaidDiscountTest {

    @Test
    @DisplayName("Prepaid line carries the per-session discount (menu 109, paid 98.10 → 10.90 off)")
    void prepaidLineShowsDiscount() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        PrepaidRedemptionRepository prepaidRedemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidPackageRepository prepaidPackages = mock(PrepaidPackageRepository.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);

        SettlementPreviewService service = new SettlementPreviewService(aggregator, new TierCommissionEngine(),
                salonConfigRepo, directory, tierGrants, feedback, square, prepaidRedemptions, prepaidPackages, providerRepo, mock(com.salonreview.repo.RedoRepository.class), mock(com.salonreview.repo.ManualAdjustmentRepository.class), mock(com.salonreview.square.NoShowFeeService.class), mock(com.salonreview.square.SuspiciousBookingService.class), mock(com.salonreview.square.CancelledAppointmentService.class));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(new CommissionConfig(60,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350")));
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(new BigDecimal("0.0350"));
        when(sc.getOwnerShortName()).thenReturn("AK");
        when(salonConfigRepo.findById(1)).thenReturn(Optional.of(sc));
        when(tierGrants.findByYearAndMonth(2026, 5)).thenReturn(List.of());
        when(feedback.findByYearAndMonth(2026, 5)).thenReturn(List.of());
        when(square.customerNames(any())).thenReturn(java.util.Map.of());
        // No Square orders this month — provider 1's only activity is the prepaid draw-down.
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(
                new MonthAggregation(2026, 5, "UTC", List.of(), new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of()));
        when(providerRepo.findById(1L)).thenReturn(Optional.of(Provider.builder().id(1L).displayName("Bayan").build()));

        // Package: $294.30 paid for 3 sessions ($98.10 each); a May-20 draw-down at the $109 menu price.
        PrepaidPackage pkg = PrepaidPackage.builder().id(1L).customerId("C").customerName("Sama")
                .amount(new BigDecimal("294.30")).totalServices(3).paidDate(LocalDate.of(2026, 4, 1)).build();
        PrepaidRedemption red = PrepaidRedemption.builder().id(7L).packageId(1L).providerId(1L)
                .squareBookingId("bk").serviceVariationId("v").serviceName("Regular Manicure")
                .serviceDate(LocalDate.of(2026, 5, 20)).menuPrice(new BigDecimal("109.00")).counts(true).build();
        when(prepaidRedemptions.findByServiceDateBetween(any(), any())).thenReturn(List.of(red));
        when(prepaidPackages.findAllById(any())).thenReturn(List.of(pkg));

        ProviderDetail detail = service.providerDetail(2026, 5, 1L);

        AttributedService line = detail.services().stream()
                .filter(s -> "PREPAID".equals(s.channel())).findFirst().orElseThrow();
        assertThat(line.gross()).isEqualByComparingTo("109.00");  // paid on the menu price
        assertThat(line.discount()).isEqualByComparingTo("10.90"); // the 10% prepay discount, now shown
        assertThat(line.net()).isEqualByComparingTo("98.10");      // what was actually paid per session
        // Payout still on the menu price (salon absorbs the discount) — second half = May.
        assertThat(detail.payout().secondHalf().cardRevenue()).isEqualByComparingTo("109.00");
    }
}
