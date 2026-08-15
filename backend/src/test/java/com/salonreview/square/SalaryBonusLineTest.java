package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.*;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SettlementPreviewService.ProviderPayout;
import com.salonreview.square.SettlementPreviewService.SettlementPreview;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The #salary 16-END block shows the 50/50 tier bonus when a qualified provider earned one. */
class SalaryBonusLineTest {

    @Test
    @DisplayName("Qualified month: 16-END block has a 50/50 bonus line; 1-15 does not")
    void bonusLineOnSecondHalfWhenQualified() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(org.mockito.ArgumentMatchers.anyLong())).thenReturn(square);
        PrepaidRedemptionRepository prepaidRedemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidPackageRepository prepaidPackages = mock(PrepaidPackageRepository.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);

        SettlementPreviewService service = new SettlementPreviewService(aggregator, new TierCommissionEngine(),
                salonConfigRepo, directory, tierGrants, feedback, squareClientProvider, prepaidRedemptions, prepaidPackages, providerRepo, mock(com.salonreview.repo.RedoRepository.class), mock(com.salonreview.repo.ManualAdjustmentRepository.class), mock(com.salonreview.square.NoShowFeeService.class), mock(com.salonreview.square.SuspiciousBookingService.class), mock(com.salonreview.square.CancelledAppointmentService.class), currentBusinessContext);

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(new CommissionConfig(60,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350"), true));
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(new BigDecimal("0.0350"));
        when(sc.getOwnerShortName()).thenReturn("AK");
        when(salonConfigRepo.findByBusinessId(1L)).thenReturn(Optional.of(sc));
        when(tierGrants.findByBusinessIdAndYearAndMonth(1L, 2026, 5)).thenReturn(List.of());
        when(feedback.findByBusinessIdAndYearAndMonth(1L, 2026, 5)).thenReturn(List.of());
        when(prepaidRedemptions.findByBusinessIdAndServiceDateBetween(eq(1L), any(), any())).thenReturn(List.of());

        // 60 counted in H1 → qualified; card 1000 (H1) + 500 (H2). Bonus = 0.05 * 1500 = 75.00 at close.
        HalfInput first = new HalfInput(60, new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        HalfInput second = new HalfInput(0, new BigDecimal("500.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        ProviderMonth pm = new ProviderMonth("TM1", "Anna", first, second);
        MonthAggregation agg = new MonthAggregation(2026, 5, "UTC", List.of(pm),
                new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of());
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(agg);
        when(directory.resolveOrCreate("TM1", "Anna"))
                .thenReturn(Provider.builder().id(1L).name("Anna").displayName("Anna").build());

        SettlementPreview preview = service.preview(2026, 5);
        ProviderPayout p = preview.providers().get(0);

        assertThat(p.tierApplied()).isTrue();
        assertThat(p.secondHalfMessage()).contains("Month 50/50 bonus"); // whole-month, both periods
        assertThat(p.secondHalfMessage()).contains("$75.00");
        assertThat(p.firstHalfMessage()).doesNotContain("50/50 bonus"); // 1-15 is provisional, no bonus
    }
}
