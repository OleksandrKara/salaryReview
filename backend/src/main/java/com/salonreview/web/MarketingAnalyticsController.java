package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/owner/marketing/analytics")
public class MarketingAnalyticsController {

    private final MarketingAnalyticsService service;

    public MarketingAnalyticsController(MarketingAnalyticsService service) {
        this.service = service;
    }

    /** Defaults to month-to-date (the 1st of the current month through today) when from/to are
     * omitted — the view the owner checks most often, per their own description of how they use
     * this page. sources defaults to every recognized ad platform (Meta + Google) when omitted —
     * that default is echoed nowhere in the response, so the frontend is the single source of truth
     * for "what's currently selected", same as it already is for the date range.
     */
    @GetMapping
    public MarketingAnalyticsDto analytics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sources) {
        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? LocalDate.parse(from) : today.withDayOfMonth(1);
        LocalDate end = to != null ? LocalDate.parse(to) : today;
        return service.analytics(start, end, parseSources(sources));
    }

    /** Ad spend has no read-only carve-out beyond the GET above (already OWNER+ADS_MANAGER) — this
     * write is the one deliberate exception to Ads Manager's otherwise-read-only access, granted in
     * SecurityConfig alongside it.
     */
    @PutMapping("/ad-spend")
    public AdSpendResponse setAdSpend(@RequestBody AdSpendRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        BigDecimal saved = service.saveAdSpend(req.year(), req.month(), req.amount(), me.getUsername());
        return new AdSpendResponse(req.year(), req.month(), saved);
    }

    private static Set<String> parseSources(String raw) {
        if (raw == null || raw.isBlank()) return MarketingAnalyticsService.ALL_SOURCES;
        Set<String> out = new HashSet<>();
        for (String s : raw.split(",")) {
            String upper = s.trim().toUpperCase();
            if (MarketingAnalyticsService.ALL_SOURCES.contains(upper)) out.add(upper);
        }
        return out.isEmpty() ? MarketingAnalyticsService.ALL_SOURCES : out;
    }

    public record AdSpendRequest(int year, int month, BigDecimal amount) {}
    public record AdSpendResponse(int year, int month, BigDecimal amount) {}
}
