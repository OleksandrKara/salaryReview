package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PrepaidPackageRepository;
import com.salonreview.repo.PrepaidRedemptionRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.repo.TierGrantRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SettlementPreviewService.ProviderPayout;
import com.salonreview.square.SettlementPreviewService.SettlementPreview;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static java.math.BigDecimal.ZERO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-18 cross-tenant fix: {@code SettlementPreviewService#toPayout}'s {@code autoQualified}
 * flag used to be {@code monthCounted >= config.tierServiceThreshold()} with no
 * {@code tierEnabled} guard, unlike {@link TierCommissionEngine#secondHalfFinal} itself, which
 * always gated qualification on it. Found live for AK PMU (business 2): {@code tierEnabled=false}
 * uses {@code tierServiceThreshold=0} as its "off" sentinel, so every provider with any counted
 * service at all (0 &gt;= 0) showed as having "earned" the 50/50 tier on the owner's /reports page
 * and the provider's own /me page — a real, visible, wrong-looking-like-a-payroll-bug display
 * defect, even though the actual dollar amounts were always correct (the engine's own qualified
 * check already respected {@code tierEnabled}).
 */
class TierQualificationDisplayTest {

    private static AttributedService line(String service, String gross) {
        return new AttributedService("TM1", "Anna", "2026-05-04", "FIRST", service,
                new BigDecimal(gross), ZERO, new BigDecimal(gross), ZERO, true, 1, 1,
                false, "CARD", "10:00 AM", "bk-" + service, "cust", null);
    }

    private SettlementPreviewService.ProviderPayout runWithConfig(CommissionConfig cfg) {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(any())).thenReturn(square);
        PrepaidRedemptionRepository prepaidRedemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidPackageRepository prepaidPackages = mock(PrepaidPackageRepository.class);
        com.salonreview.repo.ProviderRepository providerRepo = mock(com.salonreview.repo.ProviderRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(2L);

        SettlementPreviewService service = new SettlementPreviewService(aggregator,
                new TierCommissionEngine(), salonConfigRepo, directory, tierGrants, feedback, squareClientProvider,
                prepaidRedemptions, prepaidPackages, providerRepo, mock(com.salonreview.repo.RedoRepository.class),
                mock(com.salonreview.repo.ManualAdjustmentRepository.class), mock(com.salonreview.square.NoShowFeeService.class),
                mock(com.salonreview.square.SuspiciousBookingService.class), mock(com.salonreview.square.CancelledAppointmentService.class),
                currentBusinessContext);

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.toCommissionConfig()).thenReturn(cfg);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(sc.getCardTipFeeRate()).thenReturn(cfg.cardTipFeeRate());
        when(sc.getOwnerShortName()).thenReturn("APMU");
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.of(sc));
        when(tierGrants.findByBusinessIdAndYearAndMonth(2L, 2026, 5)).thenReturn(List.of());
        when(feedback.findByBusinessIdAndYearAndMonth(2L, 2026, 5)).thenReturn(List.of());
        when(prepaidRedemptions.findByBusinessIdAndServiceDateBetween(eq(2L), any(), any())).thenReturn(List.of());

        HalfInput first = new HalfInput(2, new BigDecimal("200.00"), ZERO, ZERO, ZERO, ZERO);
        ProviderMonth pm = new ProviderMonth("TM1", "Anna", first, HalfInput.empty());
        List<AttributedService> services = List.of(line("Brows", "100"), line("Lips", "100"));
        MonthAggregation agg = new MonthAggregation(2026, 5, "UTC", List.of(pm),
                new SquareMonthAggregator.Diag(), services, List.of(), List.of());
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(agg);
        when(directory.resolveOrCreate("TM1", "Anna"))
                .thenReturn(Provider.builder().id(1L).name("Anna").displayName("Anna").build());

        SettlementPreview preview = service.preview(2026, 5);
        return preview.providers().get(0);
    }

    @Test
    @DisplayName("2026-08-18: tierEnabled=false never shows autoQualified/tierApplied true, "
            + "even when tierServiceThreshold's own \"off\" sentinel (0) is trivially satisfied")
    void tierDisabledNeverAutoQualifies() {
        CommissionConfig cfg = new CommissionConfig(0,
                new BigDecimal("0.4500"), new BigDecimal("0.4500"), new BigDecimal("0.0350"), false);

        ProviderPayout p = runWithConfig(cfg);

        assertThat(p.autoQualified()).isFalse();
        assertThat(p.tierApplied()).isFalse();
        assertThat(p.tierManuallyGranted()).isFalse();
        assertThat(p.secondHalf().appliedRate()).isEqualByComparingTo("0.4500");
    }

    @Test
    @DisplayName("2026-08-18: tierEnabled=true still auto-qualifies normally once the real "
            + "threshold is met — the tierEnabled guard doesn't break the ordinary case")
    void tierEnabledStillAutoQualifiesAtThreshold() {
        CommissionConfig cfg = new CommissionConfig(2,
                new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350"), true);

        ProviderPayout p = runWithConfig(cfg);

        assertThat(p.autoQualified()).isTrue();
        assertThat(p.tierApplied()).isTrue();
        assertThat(p.secondHalf().appliedRate()).isEqualByComparingTo("0.5000");
    }
}
