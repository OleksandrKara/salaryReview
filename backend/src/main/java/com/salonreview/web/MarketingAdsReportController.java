package com.salonreview.web;

import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.MarketingAdsReportDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/owner/marketing/ads-report")
public class MarketingAdsReportController {

    private final MarketingAnalyticsService service;

    public MarketingAdsReportController(MarketingAnalyticsService service) {
        this.service = service;
    }

    /** period is "week" (default) or "month" — which grain to bucket into. Defaults to the last 8
     * whole weeks, or the last 6 whole months, ending today, when from/to are omitted; sources/slug
     * follow the same conventions as the Analytics tab (see MarketingAnalyticsController).
     */
    @GetMapping
    public MarketingAdsReportDto adsReport(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sources,
            @RequestParam(required = false) String slug,
            @RequestParam(required = false, defaultValue = "week") String period) {
        boolean weekly = !"month".equalsIgnoreCase(period);
        LocalDate today = LocalDate.now();
        LocalDate end = to != null ? LocalDate.parse(to) : today;
        LocalDate start = from != null ? LocalDate.parse(from)
                : weekly ? end.minusWeeks(7) : end.minusMonths(5).withDayOfMonth(1);
        return service.adsReport(start, end, TrafficSourceParam.parse(sources), slug, weekly);
    }
}
