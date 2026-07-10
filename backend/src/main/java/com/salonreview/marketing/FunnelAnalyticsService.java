package com.salonreview.marketing;

import com.salonreview.marketing.FunnelAnalyticsRepository.RawFunnelStep;
import com.salonreview.web.dto.FunnelDashboardDto;
import com.salonreview.web.dto.FunnelDashboardDto.FunnelStepStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes booking-funnel drop-off stats from {@code marketing.funnel_events}. Reuses
 * {@link MarketingDashboardRepository} for landing-page lookup and the existing
 * {@code stats_since} cutoff — the same "exclude my own test traffic" cutoff the owner already
 * sets on the main marketing dashboard applies here too, rather than being a second, separate
 * setting to manage.
 */
@Service
public class FunnelAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(FunnelAnalyticsService.class);

    private final FunnelAnalyticsRepository repository;
    private final MarketingDashboardRepository landingPageRepository;

    public FunnelAnalyticsService(FunnelAnalyticsRepository repository,
                                   MarketingDashboardRepository landingPageRepository) {
        this.repository = repository;
        this.landingPageRepository = landingPageRepository;
    }

    /**
     * One entry per {@code flow_key} this landing page has ever recorded (almost always exactly
     * one). Never throws: any {@link DataAccessException} (schema not reachable, same guarantee
     * as {@link MarketingDashboardService#dashboard}) yields an empty list instead of a 500.
     */
    public List<FunnelDashboardDto> funnel(String slug, boolean adsOnly) {
        try {
            Optional<UUID> landingPageId = landingPageRepository.findLandingPageId(slug);
            if (landingPageId.isEmpty()) return List.of();

            Instant statsSince = landingPageRepository.findStatsSince(landingPageId.get()).orElse(null);
            List<RawFunnelStep> rawSteps = repository.findFunnelSteps(landingPageId.get(), statsSince, adsOnly);
            if (rawSteps.isEmpty()) return List.of();

            long totalVisitors = repository.countPageViews(landingPageId.get(), statsSince, adsOnly);
            // Shared across every flow_key this page has — in practice a page has exactly one
            // active flow at a time, so this is never actually split across multiple funnels.
            long totalCompleted = repository.countBookingsCompleted(landingPageId.get(), statsSince, adsOnly);

            Map<String, List<RawFunnelStep>> byFlow = new LinkedHashMap<>();
            for (RawFunnelStep step : rawSteps) {
                byFlow.computeIfAbsent(step.flowKey(), k -> new ArrayList<>()).add(step);
            }

            List<FunnelDashboardDto> result = new ArrayList<>();
            for (Map.Entry<String, List<RawFunnelStep>> entry : byFlow.entrySet()) {
                result.add(toDto(slug, entry.getKey(), entry.getValue(), totalVisitors, totalCompleted));
            }
            return result;
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building funnel for slug={}", slug, ex);
            return List.of();
        }
    }

    private FunnelDashboardDto toDto(String slug, String flowKey, List<RawFunnelStep> steps,
                                      long totalVisitors, long totalCompleted) {
        long totalStarted = steps.stream()
                .filter(s -> s.stepIndex() == 0)
                .mapToLong(RawFunnelStep::reachedCount)
                .findFirst()
                .orElse(0);

        List<FunnelStepStat> stats = new ArrayList<>();
        long previousReached = totalStarted;
        for (RawFunnelStep step : steps) {
            long dropOffCount = Math.max(0, previousReached - step.reachedCount());
            double dropOffPct = previousReached == 0 ? 0.0 : (double) dropOffCount / previousReached;
            double reachedPct = totalStarted == 0 ? 0.0 : (double) step.reachedCount() / totalStarted;
            stats.add(new FunnelStepStat(
                    step.stepKey(), step.stepIndex(), step.stepCountTotal(),
                    step.reachedCount(), reachedPct, dropOffCount, dropOffPct));
            previousReached = step.reachedCount();
        }

        double finalConversionRate = totalVisitors == 0 ? 0.0 : (double) totalCompleted / totalVisitors;
        return new FunnelDashboardDto(slug, flowKey, totalVisitors, totalStarted, stats, totalCompleted, finalConversionRate);
    }
}
