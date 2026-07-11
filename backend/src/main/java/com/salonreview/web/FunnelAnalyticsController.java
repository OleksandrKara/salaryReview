package com.salonreview.web;

import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.FunnelDashboardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * TrafficSourceParam), defaulting to "Ads only". */
    @GetMapping("/marketing/funnel")
    public List<FunnelDashboardDto> funnel(@RequestParam(defaultValue = "mani") String slug,
                                            @RequestParam(required = false) String sources) {
        return service.funnel(slug, TrafficSourceParam.parse(sources));
    }
}
