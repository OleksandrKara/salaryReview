package com.salonreview.web;

import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.FunnelDashboardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/owner")
public class FunnelAnalyticsController {

    private final FunnelAnalyticsService service;

    public FunnelAnalyticsController(FunnelAnalyticsService service) {
        this.service = service;
    }

    /** One entry per flow_key this landing page has recorded — almost always exactly one. sources
     * is the same comma-separated traffic-source list as the Overview tab (see
     * TrafficSourceParam), defaulting to "Ads only". from/to (yyyy-MM-dd, both optional) are the
     * shared marketing period filter (see MarketingDashboardController's own from/to) — omitted
     * means "All" (no additional bound beyond the page's permanent stats-since cutoff). to is
     * inclusive on the wire but converted to an exclusive next-day bound before reaching the
     * service, matching FunnelAnalyticsRepository's own "< ?" upper-bound convention. */
    @GetMapping("/marketing/funnel")
    public List<FunnelDashboardDto> funnel(@RequestParam(defaultValue = "mani") String slug,
                                            @RequestParam(required = false) String sources,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        Instant periodFrom = from == null ? null : LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodTo = to == null ? null : LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return service.funnel(slug, TrafficSourceParam.parse(sources), periodFrom, periodTo);
    }
}
