package com.salonreview.web;

import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/owner/marketing/analytics")
public class MarketingAnalyticsController {

    private final MarketingAnalyticsService service;

    public MarketingAnalyticsController(MarketingAnalyticsService service) {
        this.service = service;
    }

    /** Defaults to month-to-date (the 1st of the current month through today) when from/to are
     * omitted — the view the owner checks most often, per their own description of how they use
     * this page.
     */
    @GetMapping
    public MarketingAnalyticsDto analytics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? LocalDate.parse(from) : today.withDayOfMonth(1);
        LocalDate end = to != null ? LocalDate.parse(to) : today;
        return service.analytics(start, end);
    }
}
