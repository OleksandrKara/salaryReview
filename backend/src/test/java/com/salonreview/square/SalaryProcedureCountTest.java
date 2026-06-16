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
import static org.mockito.Mockito.*;

/**
 * The #salary "N procedures" line reports the number of MAIN services (gross >= the tier cutoff), not
 * every service line — so add-ons below the cutoff don't inflate the count and it matches the tier
 * count shown everywhere else.
 */
class SalaryProcedureCountTest {

    private static AttributedService line(String service, String gross, boolean counted) {
        return new AttributedService("TM1", "Anna", "2026-05-04", "FIRST", service,
                new BigDecimal(gross), ZERO, new BigDecimal(gross), ZERO, counted, counted ? 1 : 0, 1,
                false, "CARD", "10:00 AM", "bk-" + service, "cust", null);
    }

    @Test
    @DisplayName("#salary procedures counts main services only (two $100 mains + one $20 add-on → 2)")
    void proceduresCountMainServicesOnly() {
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        SquareClient square = mock(SquareClient.class);
        PrepaidRedemptionRepository prepaidRedemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidPackageRepository prepaidPackages = mock(PrepaidPackageRepository.class);
        com.salonreview.repo.ProviderRepository providerRepo = mock(com.salonreview.repo.ProviderRepository.class);

        SettlementPreviewService service = new SettlementPreviewService(aggregator,
                new TierCommissionEngine(), salonConfigRepo, directory, tierGrants, feedback, square,
                prepaidRedemptions, prepaidPackages, providerRepo, mock(com.salonreview.repo.RedoRepository.class), mock(com.salonreview.repo.ManualCreditRepository.class), mock(com.salonreview.square.NoShowFeeService.class), mock(com.salonreview.square.SuspiciousBookingService.class));

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

        // Two main services (>= $50) plus one sub-cutoff add-on, all first half, same provider.
        HalfInput first = new HalfInput(2, new BigDecimal("200.00"), ZERO, ZERO, ZERO, ZERO);
        ProviderMonth pm = new ProviderMonth("TM1", "Anna", first, HalfInput.empty());
        List<AttributedService> services = List.of(
                line("Mani", "100", true), line("Pedi", "100", true), line("Add-on", "20", false));
        MonthAggregation agg = new MonthAggregation(2026, 5, "UTC", List.of(pm),
                new SquareMonthAggregator.Diag(), services, List.of(), List.of());
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(agg);
        when(directory.resolveOrCreate("TM1", "Anna"))
                .thenReturn(Provider.builder().id(1L).name("Anna").displayName("Anna").build());

        SettlementPreview preview = service.preview(2026, 5);
        ProviderPayout p = preview.providers().get(0);

        assertThat(p.firstHalfMessage()).contains("2 procedures");      // main services
        assertThat(p.firstHalfMessage()).doesNotContain("3 procedures"); // not the raw line count
    }
}
