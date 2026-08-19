package com.salonreview.web;

import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.FunnelDashboardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
     * TrafficSourceParam), defaulting to "Ads only". from/to (yyyy-MM-dd, both optional, both
     * inclusive) are the shared marketing period filter (see MarketingDashboardController's own
     * from/to) — omitted means "All" (no additional bound beyond the page's permanent stats-since
     * cutoff). Resolved against the salon's own business timezone (not UTC) inside the service —
     * see FunnelAnalyticsService#resolveZone. slug omitted resolves to the caller's own business's
     * first landing page — previously hardcoded to "mani" regardless of caller. */
    @GetMapping("/marketing/funnel")
    public List<FunnelDashboardDto> funnel(@RequestParam(required = false) String slug,
                                            @RequestParam(required = false) String sources,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        LocalDate periodFrom = from == null ? null : LocalDate.parse(from);
        LocalDate periodTo = to == null ? null : LocalDate.parse(to);
        return service.funnel(slug, TrafficSourceParam.parse(sources), periodFrom, periodTo);
    }
}
