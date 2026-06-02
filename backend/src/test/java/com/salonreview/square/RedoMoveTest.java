package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Redo;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A redo moves a service's commission from the original provider to the redo provider. */
class RedoMoveTest {

    @Test
    @DisplayName("Redo: deduction + credit BOTH land in the redo period; the original (paid) period is untouched")
    void redoMovesCommission() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        PrepaidRedemptionRepository prepaidRedemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidPackageRepository prepaidPackages = mock(PrepaidPackageRepository.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);
        RedoRepository redoRepo = mock(RedoRepository.class);

        SettlementPreviewService service = new SettlementPreviewService(aggregator, new TierCommissionEngine(),
                salonConfigRepo, directory, tierGrants, feedback, square, prepaidRedemptions, prepaidPackages,
                providerRepo, redoRepo, mock(com.salonreview.repo.ManualCreditRepository.class));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(new CommissionConfig(60,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350")));
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(new BigDecimal("0.0350"));
        when(sc.getOwnerShortName()).thenReturn("AK");
        when(salonConfigRepo.findById(1)).thenReturn(Optional.of(sc));
        when(tierGrants.findByYearAndMonth(2026, 5)).thenReturn(List.of());
        when(feedback.findByYearAndMonth(2026, 5)).thenReturn(List.of());
        when(prepaidRedemptions.findByServiceDateBetween(any(), any())).thenReturn(List.of());

        // Susan: first-half card 200 (2 counted, the original/paid period) + second-half card 300 (2).
        // Bayan: second-half card 150 (1 counted).
        ProviderMonth susan = new ProviderMonth("TM_S", "Susan",
                new HalfInput(2, new BigDecimal("200.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new HalfInput(2, new BigDecimal("300.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        ProviderMonth bayan = new ProviderMonth("TM_B", "Bayan", HalfInput.empty(),
                new HalfInput(1, new BigDecimal("150.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(
                new MonthAggregation(2026, 5, "UTC", List.of(susan, bayan), new SquareMonthAggregator.Diag(), List.of(), List.of()));
        when(directory.resolveOrCreate("TM_S", "Susan")).thenReturn(Provider.builder().id(1L).displayName("Susan").build());
        when(directory.resolveOrCreate("TM_B", "Bayan")).thenReturn(Provider.builder().id(2L).displayName("Bayan").build());
        when(providerRepo.findById(1L)).thenReturn(Optional.of(Provider.builder().id(1L).displayName("Susan").build()));
        when(providerRepo.findById(2L)).thenReturn(Optional.of(Provider.builder().id(2L).displayName("Bayan").build()));

        // Redo: Susan's May-10 (first half) $100 service redone by Bayan May-20 (second half).
        when(redoRepo.findAllByOrderByRedoDateDesc()).thenReturn(List.of(Redo.builder()
                .originalProviderId(1L).redoProviderId(2L)
                .originalDate(LocalDate.of(2026, 5, 10)).redoDate(LocalDate.of(2026, 5, 20))
                .amount(new BigDecimal("100.00")).build()));

        SettlementPreview preview = service.preview(2026, 5);
        ProviderPayout s = preview.providers().stream().filter(p -> p.name().equals("Susan")).findFirst().orElseThrow();
        ProviderPayout b = preview.providers().stream().filter(p -> p.name().equals("Bayan")).findFirst().orElseThrow();

        assertThat(s.firstHalf().cardRevenue()).isEqualByComparingTo("200.00");   // original period UNTOUCHED
        assertThat(s.firstHalf().countedServices()).isEqualTo(2);
        assertThat(s.secondHalf().cardRevenue()).isEqualByComparingTo("200.00");  // 300 − 100 deducted in redo period
        assertThat(s.secondHalf().countedServices()).isEqualTo(1);               // 2 − 1
        assertThat(b.secondHalf().cardRevenue()).isEqualByComparingTo("250.00");  // 150 + 100 moved in
        assertThat(b.secondHalf().countedServices()).isEqualTo(2);               // 1 + 1

        // Short #salary note on each provider's redo-period block.
        assertThat(b.secondHalfMessage()).contains("Redo (from Susan): +$100.00");
        assertThat(s.secondHalfMessage()).contains("Redo (redone by Bayan): −$100.00");
    }
}
