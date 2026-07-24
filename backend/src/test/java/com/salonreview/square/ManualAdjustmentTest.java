package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.ManualAdjustment;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.*;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SettlementPreviewService.ProviderDetail;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
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

/** A manual adjustment pays (or deducts) like a card service: gross commission basis, counted. */
class ManualAdjustmentTest {

    private SettlementPreviewService newService(ManualAdjustmentRepository manualAdjustments,
                                                 SquareMonthAggregator aggregator,
                                                 SalonConfigRepository salonConfigRepo,
                                                 ProviderDirectory directory,
                                                 TierGrantRepository tierGrants,
                                                 SettlementFeedbackRepository feedback,
                                                 SquareClient square,
                                                 ProviderRepository providerRepo) {
        return new SettlementPreviewService(aggregator, new TierCommissionEngine(),
                salonConfigRepo, directory, tierGrants, feedback, square, mock(PrepaidRedemptionRepository.class),
                mock(PrepaidPackageRepository.class), providerRepo, mock(RedoRepository.class), manualAdjustments,
                mock(NoShowFeeService.class), mock(SuspiciousBookingService.class), mock(CancelledAppointmentService.class));
    }

    private void configFor(SalonConfigRepository salonConfigRepo, int year, int month,
                           TierGrantRepository tierGrants, SettlementFeedbackRepository feedback,
                           SquareClient square) {
        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(new CommissionConfig(60,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350")));
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(new BigDecimal("0.0350"));
        when(sc.getOwnerShortName()).thenReturn("AK");
        when(salonConfigRepo.findById(1)).thenReturn(Optional.of(sc));
        when(tierGrants.findByYearAndMonth(year, month)).thenReturn(List.of());
        when(feedback.findByYearAndMonth(year, month)).thenReturn(List.of());
        when(square.customerNames(any())).thenReturn(java.util.Map.of());
    }

    @Test
    @DisplayName("Manual adjustment credit: gross 129 / disc 50 / tip 15.80 → provider credited like a card service")
    void creditPaysLikeCard() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);
        ManualAdjustmentRepository manualAdjustments = mock(ManualAdjustmentRepository.class);

        SettlementPreviewService service = newService(manualAdjustments, aggregator, salonConfigRepo, directory,
                tierGrants, feedback, square, providerRepo);
        configFor(salonConfigRepo, 2026, 5, tierGrants, feedback, square);
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(
                new MonthAggregation(2026, 5, "UTC", List.of(), new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of()));
        when(providerRepo.findById(1L)).thenReturn(Optional.of(Provider.builder().id(1L).displayName("Test Provider 1").build()));

        // Julia's May 26 visit, recorded as a manual credit: $129 services, −$50, $15.80 tip.
        when(manualAdjustments.findAllByOrderByServiceDateDesc()).thenReturn(List.of(ManualAdjustment.builder()
                .id(1L).providerId(1L).serviceDate(LocalDate.of(2026, 5, 26))
                .gross(new BigDecimal("129.00")).discount(new BigDecimal("50.00")).tip(new BigDecimal("15.80"))
                .serviceName("Manicure + Design (Test Customer 1)").build()));

        ProviderDetail detail = service.providerDetail(2026, 5, 1L);

        AttributedService line = detail.services().stream()
                .filter(s -> "ADJUSTMENT".equals(s.channel())).findFirst().orElseThrow();
        assertThat(line.gross()).isEqualByComparingTo("129.00");
        assertThat(line.discount()).isEqualByComparingTo("50.00");
        assertThat(line.net()).isEqualByComparingTo("79.00");
        assertThat(line.tip()).isEqualByComparingTo("15.80");
        assertThat(line.counted()).isTrue();
        // Paid like a card service in the May (16-END) half: commission on gross, tip after the fee.
        assertThat(detail.payout().secondHalf().cardRevenue()).isEqualByComparingTo("129.00");
        assertThat(detail.payout().secondHalf().countedServices()).isEqualTo(1);
        assertThat(detail.payout().secondHalf().tipsAfterFee()).isEqualByComparingTo("15.25"); // 15.80 × 0.965
    }

    @Test
    @DisplayName("Manual adjustment deduction (refund): gross -124.20 → commission reduced and tier count decremented")
    void deductionReducesCommissionAndTierCount() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);
        ManualAdjustmentRepository manualAdjustments = mock(ManualAdjustmentRepository.class);

        SettlementPreviewService service = newService(manualAdjustments, aggregator, salonConfigRepo, directory,
                tierGrants, feedback, square, providerRepo);
        configFor(salonConfigRepo, 2026, 7, tierGrants, feedback, square);
        when(aggregator.aggregate(eq(2026), eq(7), any())).thenReturn(
                new MonthAggregation(2026, 7, "UTC", List.of(), new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of()));
        when(providerRepo.findById(1L)).thenReturn(Optional.of(Provider.builder().id(1L).displayName("Susan").build()));

        // Chloe Pruitt's July 13 manicure was refunded $124.20 — deduct Susan's commission and tier count.
        when(manualAdjustments.findAllByOrderByServiceDateDesc()).thenReturn(List.of(ManualAdjustment.builder()
                .id(2L).providerId(1L).serviceDate(LocalDate.of(2026, 7, 13))
                .gross(new BigDecimal("-124.20")).discount(BigDecimal.ZERO).tip(BigDecimal.ZERO)
                .serviceName("Refund — Chloe Pruitt").build()));

        ProviderDetail detail = service.providerDetail(2026, 7, 1L);

        AttributedService line = detail.services().stream()
                .filter(s -> "ADJUSTMENT".equals(s.channel())).findFirst().orElseThrow();
        assertThat(line.gross()).isEqualByComparingTo("-124.20");
        assertThat(line.net()).isEqualByComparingTo("-124.20");
        // The old (buggy) comparison — raw signed gross against a positive cutoff — would never be
        // true for a negative amount, silently leaving the tier count un-decremented. Asserting
        // false here is exactly the regression this test guards against.
        assertThat(line.counted()).isFalse();
        assertThat(line.countedUnits()).isEqualTo(-1);
        assertThat(detail.payout().firstHalf().cardRevenue()).isEqualByComparingTo("-124.20");
        assertThat(detail.payout().firstHalf().countedServices()).isEqualTo(-1);
    }
}
