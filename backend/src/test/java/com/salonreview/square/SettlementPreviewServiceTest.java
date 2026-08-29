package com.salonreview.square;

import com.salonreview.commission.TierCommissionEngine;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.ManualAdjustmentRepository;
import com.salonreview.repo.PrepaidPackageRepository;
import com.salonreview.repo.PrepaidRedemptionRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.RedoRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.repo.TierGrantRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SettlementPreviewService.ProviderDetail;
import com.salonreview.square.SettlementPreviewService.SettlementPreview;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers only the {@link com.salonreview.util.TtlCache} layer added on top of {@link
 * SettlementPreviewService#preview}/{@link SettlementPreviewService#providerDetail} — every mock
 * below is stubbed with empty/zero data specifically so the commission-computation machinery
 * (already covered elsewhere) never actually runs; an empty {@link MonthAggregation} means {@code
 * aggregate()} is the only collaborator whose call count matters for these tests. */
class SettlementPreviewServiceTest {

    private SquareMonthAggregator aggregator;
    private SalonConfigRepository salonConfig;
    private SquareClientProvider squareClientProvider;
    private CurrentBusinessContext currentBusinessContext;
    private SettlementPreviewService service;

    @BeforeEach
    void setUp() {
        aggregator = mock(SquareMonthAggregator.class);
        TierCommissionEngine engine = mock(TierCommissionEngine.class);
        salonConfig = mock(SalonConfigRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        TierGrantRepository tierGrants = mock(TierGrantRepository.class);
        SettlementFeedbackRepository feedback = mock(SettlementFeedbackRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        PrepaidRedemptionRepository prepaidRedemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidPackageRepository prepaidPackages = mock(PrepaidPackageRepository.class);
        ProviderRepository providerRepo = mock(ProviderRepository.class);
        RedoRepository redoRepo = mock(RedoRepository.class);
        ManualAdjustmentRepository manualAdjustments = mock(ManualAdjustmentRepository.class);
        NoShowFeeService noShowFees = mock(NoShowFeeService.class);
        SuspiciousBookingService suspiciousBookings = mock(SuspiciousBookingService.class);
        CancelledAppointmentService cancelledAppointments = mock(CancelledAppointmentService.class);
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);

        stubEmptyCollaboratorsForBusiness(1L, salonConfig, squareClientProvider);
        when(tierGrants.findByBusinessIdAndYearAndMonth(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(feedback.findByBusinessIdAndYearAndMonth(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(prepaidRedemptions.findByBusinessIdAndServiceDateBetween(any(), any(), any())).thenReturn(List.of());
        when(redoRepo.findAllByBusinessIdOrderByRedoDateDesc(any())).thenReturn(List.of());
        when(manualAdjustments.findAllByBusinessIdOrderByServiceDateDesc(any())).thenReturn(List.of());
        when(noShowFees.noShowFeeLinesByProvider(anyInt(), anyInt())).thenReturn(Map.of());
        when(noShowFees.compute(anyInt(), anyInt())).thenReturn(new NoShowFeeService.NoShowMonth(List.of(), Map.of()));
        when(suspiciousBookings.summaryFor(any(MonthAggregation.class))).thenReturn(Map.of());
        when(suspiciousBookings.summaryForSelf(any(MonthAggregation.class))).thenReturn(Map.of());
        when(cancelledAppointments.summaryFor(any(MonthAggregation.class))).thenReturn(Map.of());

        service = new SettlementPreviewService(aggregator, engine, salonConfig, directory, tierGrants, feedback,
                squareClientProvider, prepaidRedemptions, prepaidPackages, providerRepo, redoRepo,
                manualAdjustments, noShowFees, suspiciousBookings, cancelledAppointments, currentBusinessContext);
    }

    private static void stubEmptyCollaboratorsForBusiness(
            Long businessId, SalonConfigRepository salonConfig, SquareClientProvider squareClientProvider) {
        SalonConfig sc = SalonConfig.builder().businessId(businessId).ownerShortName("o")
                .tierServiceThreshold(0).servicePriceCutoff(BigDecimal.ZERO)
                .baseCommissionRate(BigDecimal.ZERO).tierCommissionRate(BigDecimal.ZERO)
                .cardTipFeeRate(BigDecimal.ZERO).tierEnabled(false).build();
        when(salonConfig.findByBusinessId(businessId)).thenReturn(Optional.of(sc));
        SquareClient square = mock(SquareClient.class);
        when(square.lastFetchAt()).thenReturn(Instant.parse("2026-08-01T00:00:00Z"));
        when(squareClientProvider.forBusiness(businessId)).thenReturn(square);
    }

    private static MonthAggregation emptyAgg(int year, int month) {
        return new MonthAggregation(year, month, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("a second preview() call for the same month within the TTL reuses the cached result — no second aggregate() call")
    void previewCachesWithinTtl() {
        when(aggregator.aggregate(2026, 7, BigDecimal.ZERO)).thenReturn(emptyAgg(2026, 7));

        SettlementPreview first = service.preview(2026, 7);
        SettlementPreview second = service.preview(2026, 7);

        assertThat(second).isSameAs(first);
        verify(aggregator, times(1)).aggregate(2026, 7, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("invalidateCache() forces the next preview() call to recompute")
    void invalidateCacheForcesRecompute() {
        when(aggregator.aggregate(2026, 7, BigDecimal.ZERO)).thenReturn(emptyAgg(2026, 7));

        service.preview(2026, 7);
        service.invalidateCache();
        service.preview(2026, 7);

        verify(aggregator, times(2)).aggregate(2026, 7, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a different month is fetched independently, never served from another month's cache entry")
    void differentMonthIsNotCrossServed() {
        when(aggregator.aggregate(2026, 7, BigDecimal.ZERO)).thenReturn(emptyAgg(2026, 7));
        when(aggregator.aggregate(2026, 8, BigDecimal.ZERO)).thenReturn(emptyAgg(2026, 8));

        service.preview(2026, 7);
        service.preview(2026, 8);

        verify(aggregator).aggregate(2026, 7, BigDecimal.ZERO);
        verify(aggregator).aggregate(2026, 8, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("providerDetail() also caches per (year, month, providerId) — a second call within TTL doesn't recompute")
    void providerDetailCachesWithinTtl() {
        when(aggregator.aggregate(2026, 7, BigDecimal.ZERO)).thenReturn(emptyAgg(2026, 7));

        ProviderDetail first = service.providerDetail(2026, 7, 99L);
        ProviderDetail second = service.providerDetail(2026, 7, 99L);

        assertThat(second).isSameAs(first);
        verify(aggregator, times(1)).aggregate(2026, 7, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("invalidateCache() only drops the calling business's own cached entries, never another business's")
    void invalidateCacheOnlyDropsCallingBusinesssOwnEntry() {
        stubEmptyCollaboratorsForBusiness(2L, salonConfig, squareClientProvider);
        when(aggregator.aggregate(2026, 7, BigDecimal.ZERO)).thenReturn(emptyAgg(2026, 7));

        when(currentBusinessContext.id()).thenReturn(1L);
        service.preview(2026, 7);
        when(currentBusinessContext.id()).thenReturn(2L);
        service.preview(2026, 7);
        verify(aggregator, times(2)).aggregate(2026, 7, BigDecimal.ZERO); // both businesses computed once each, now cached

        when(currentBusinessContext.id()).thenReturn(1L);
        service.invalidateCache(); // only business 1's entry should drop

        service.preview(2026, 7); // business 1: cache miss -> recomputes (3rd call)
        when(currentBusinessContext.id()).thenReturn(2L);
        service.preview(2026, 7); // business 2: still cached -> must NOT recompute

        verify(aggregator, times(3)).aggregate(2026, 7, BigDecimal.ZERO);
    }
}
