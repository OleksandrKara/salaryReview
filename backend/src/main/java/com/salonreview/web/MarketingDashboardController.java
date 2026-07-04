package com.salonreview.web;

import com.salonreview.marketing.MarketingDashboardService;
import com.salonreview.web.dto.MarketingDashboardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner")
public class MarketingDashboardController {

    private final MarketingDashboardService service;

    public MarketingDashboardController(MarketingDashboardService service) {
        this.service = service;
    }

    @GetMapping("/marketing")
    public MarketingDashboardDto marketing(@RequestParam(defaultValue = "mani") String slug) {
        return service.dashboard(slug);
    }
}
