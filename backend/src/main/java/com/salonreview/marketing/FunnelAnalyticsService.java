package com.salonreview.marketing;

import com.salonreview.marketing.FunnelAnalyticsRepository.RawFunnelStep;
import com.salonreview.util.TtlCache;
import com.salonreview.web.dto.FunnelDashboardDto;
import com.salonreview.web.dto.FunnelDashboardDto.FunnelStepStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    // See docs/CACHING.md / MarketingDashboardService's own CACHE_TTL — same 10-min TTL and same
    // "Sync now" escape hatch (see invalidateCache()).
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    // How recently a flow needs to have logged an event to count as "still live" rather than
    // "retired" — wide enough to absorb a returning visitor's sticky localStorage variant
    // assignment (see experiments.ts resolveExperiment's persisted-assignment reuse) trickling in
    // for a while after a variant's weight is zeroed, without so wide that a genuinely retired
    // flow still reads as active for weeks.
    private static final Duration ACTIVE_WINDOW = Duration.ofDays(7);

    private final FunnelAnalyticsRepository repository;
    private final MarketingDashboardRepository landingPageRepository;
    private final com.salonreview.square.SquareClientProvider squareClientProvider;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final TtlCache cache = new TtlCache();

    public FunnelAnalyticsService(FunnelAnalyticsRepository repository,
                                   MarketingDashboardRepository landingPageRepository,
                                   com.salonreview.square.SquareClientProvider squareClientProvider,
                                   com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.repository = repository;
        this.landingPageRepository = landingPageRepository;
        this.squareClientProvider = squareClientProvider;
        this.currentBusinessContext = currentBusinessContext;
    }

    /** The salon's real business timezone, resolved from Square's own location config — see
     * MarketingDashboardService#resolveZone for the identical pattern used across this codebase. */
    private ZoneId resolveZone() {
        try {
            String tz = squareClientProvider.forBusiness(currentBusinessContext.id()).locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * One entry per {@code flow_key} this landing page has ever recorded (almost always exactly
     * one). Never throws: any {@link DataAccessException} (schema not reachable, same guarantee
     * as {@link MarketingDashboardService#dashboard}) yields an empty list instead of a 500.
     *
     * <p>{@code periodFrom}/{@code periodTo} are the owner's currently-selected period-filter
     * window (All/Month to date/Custom — see the shared frontend PeriodFilter), both nullable
     * meaning "unbounded" on that side, expressed as calendar dates (inclusive on both ends) in
     * the salon's own business timezone rather than UTC — resolved via {@link #resolveZone()}.
     * This is layered on top of, never instead of, the permanent {@code stats_since} cutoff: the
     * effective lower bound is whichever of the two is later (both null means unbounded), so a
     * period filter can only narrow the view further, and can never resurface pre-cutoff test
     * traffic the owner explicitly hid.
     */
    public List<FunnelDashboardDto> funnel(String slug, Set<String> sources, LocalDate periodFrom, LocalDate periodTo) {
        String key = "funnel:" + currentBusinessContext.id() + ":" + slug + ":" + sources + ":" + periodFrom + ":" + periodTo;
        return cache.get(key, CACHE_TTL, () -> computeFunnel(slug, sources, periodFrom, periodTo));
    }

    private List<FunnelDashboardDto> computeFunnel(String slugParam, Set<String> sources, LocalDate periodFrom, LocalDate periodTo) {
        try {
            Long businessId = currentBusinessContext.id();
            String slug = slugParam != null ? slugParam : landingPageRepository.findDefaultSlugForBusiness(businessId).orElse(null);
            if (slug == null) return List.of();
            Optional<UUID> landingPageId = landingPageRepository.findLandingPageId(slug, businessId);
            if (landingPageId.isEmpty()) return List.of();

            ZoneId zone = resolveZone();
            Instant periodFromInstant = periodFrom == null ? null : periodFrom.atStartOfDay(zone).toInstant();
            Instant periodToInstant = periodTo == null ? null : periodTo.plusDays(1).atStartOfDay(zone).toInstant();

            Instant statsSince = landingPageRepository.findStatsSince(landingPageId.get()).orElse(null);
            Instant effectiveFrom = laterOf(statsSince, periodFromInstant);
            List<RawFunnelStep> rawSteps = repository.findFunnelSteps(landingPageId.get(), effectiveFrom, periodToInstant, sources);
            if (rawSteps.isEmpty()) return List.of();

            long totalVisitors = repository.countPageViews(landingPageId.get(), effectiveFrom, periodToInstant, sources);
            // Shared across every flow_key this page has — in practice a page has exactly one
            // active flow at a time, so this is never actually split across multiple funnels.
            long totalCompleted = repository.countBookingsCompleted(landingPageId.get(), effectiveFrom, periodToInstant, sources);

            Map<String, List<RawFunnelStep>> byFlow = new LinkedHashMap<>();
            for (RawFunnelStep step : rawSteps) {
                byFlow.computeIfAbsent(step.flowKey(), k -> new ArrayList<>()).add(step);
            }

            // Unfiltered by the owner's period selection — "is this flow still live" must reflect
            // real current activity regardless of which date range they happen to be viewing.
            Map<String, Instant> lastActivityByFlow = repository.findLastActivityByFlow(landingPageId.get());
            Instant activeSince = Instant.now().minus(ACTIVE_WINDOW);

            List<FunnelDashboardDto> result = new ArrayList<>();
            for (Map.Entry<String, List<RawFunnelStep>> entry : byFlow.entrySet()) {
                Instant lastActivity = lastActivityByFlow.get(entry.getKey());
                boolean active = lastActivity != null && lastActivity.isAfter(activeSince);
                result.add(toDto(slug, entry.getKey(), entry.getValue(), totalVisitors, totalCompleted,
                        active, lastActivity));
            }
            return result;
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building funnel for slug={}", slugParam, ex);
            return List.of();
        }
    }

    /** The later of two nullable instants, where null means "unbounded" (i.e. loses to any real
     * value) — null only when both inputs are null. Used to intersect the permanent stats_since
     * cutoff with the owner's currently-selected period-filter lower bound. */
    private static Instant laterOf(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** Backs the global "Sync now" button (see SquareSyncController) — so forcing a fresh Square
     * pull also busts this service's own cached funnel responses. */
    /** Only this business's own cached entries — see TtlCache#invalidateWhere's own doc for why
     * a per-tenant "Sync now" shouldn't force every other business's cache to also be dropped. */
    public void invalidateCache() {
        cache.invalidateWhere(k -> k.contains(":" + currentBusinessContext.id() + ":"));
    }

    private FunnelDashboardDto toDto(String slug, String flowKey, List<RawFunnelStep> steps,
                                      long totalVisitors, long totalCompleted,
                                      boolean active, Instant lastActivityAt) {
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
        return new FunnelDashboardDto(slug, flowKey, totalVisitors, totalStarted, stats, totalCompleted,
                finalConversionRate, active, lastActivityAt);
    }
}
