package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.FeedbackStatus;
import com.salonreview.domain.Half;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.domain.SettlementFeedback;
import com.salonreview.repo.*;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SettlementPreviewService.ProviderPayout;
import com.salonreview.square.SettlementPreviewService.SettlementPreview;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A provider's approve / request-correction is per period — the payout carries each half separately. */
class FeedbackPerHalfTest {

    @Test
    @DisplayName("Per-half feedback flows to the payout: 1-15 approved, 16-end changes requested")
    void perHalfFeedbackOnPayout() {
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
                salonConfigRepo, directory, tierGrants, feedback, square, prepaidRedemptions, prepaidPackages, providerRepo, mock(com.salonreview.repo.RedoRepository.class), mock(com.salonreview.repo.ManualCreditRepository.class), mock(com.salonreview.square.NoShowFeeService.class), mock(com.salonreview.square.SuspiciousBookingService.class), mock(com.salonreview.square.CancelledAppointmentService.class));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(new CommissionConfig(60,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350")));
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(new BigDecimal("0.0350"));
        when(sc.getOwnerShortName()).thenReturn("AK");
        when(salonConfigRepo.findById(1)).thenReturn(Optional.of(sc));
        when(tierGrants.findByYearAndMonth(2026, 5)).thenReturn(List.of());
        when(prepaidRedemptions.findByServiceDateBetween(any(), any())).thenReturn(List.of());

        HalfInput first = new HalfInput(1, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        ProviderMonth pm = new ProviderMonth("TM1", "Anna", first, HalfInput.empty());
        MonthAggregation agg = new MonthAggregation(2026, 5, "UTC", List.of(pm),
                new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of());
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(agg);
        when(directory.resolveOrCreate("TM1", "Anna"))
                .thenReturn(Provider.builder().id(1L).name("Anna").displayName("Anna").build());

        when(feedback.findByYearAndMonth(2026, 5)).thenReturn(List.of(
                SettlementFeedback.builder().providerId(1L).year(2026).month(5).half(Half.FIRST)
                        .status(FeedbackStatus.APPROVED).updatedAt(Instant.now()).build(),
                SettlementFeedback.builder().providerId(1L).year(2026).month(5).half(Half.SECOND)
                        .status(FeedbackStatus.CHANGES_REQUESTED).comment("fix the 22nd").updatedAt(Instant.now()).build()));

        SettlementPreview preview = service.preview(2026, 5);
        ProviderPayout p = preview.providers().get(0);

        assertThat(p.firstFeedback()).isNotNull();
        assertThat(p.firstFeedback().status()).isEqualTo("APPROVED");
        assertThat(p.secondFeedback().status()).isEqualTo("CHANGES_REQUESTED");
        assertThat(p.secondFeedback().comment()).isEqualTo("fix the 22nd");
    }
}
