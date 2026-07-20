package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AdSpendEntry;
import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.marketing.MarketingAnalyticsService.PeriodKind;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.MarketingAdsReportDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/owner/marketing/ads-report")
public class MarketingAdsReportController {

    private final MarketingAnalyticsService service;

    public MarketingAdsReportController(MarketingAnalyticsService service) {
        this.service = service;
    }

    /** period is "week" (default), "month", "mtd" (month-to-date), or "custom" — which grain to
     * bucket into (see MarketingAnalyticsService.PeriodKind). "custom" requires explicit from/to
     * (no default-range fallback — a caller-specified range is the whole point of that kind).
     * "mtd" ignores from/to entirely — it's always [1st of the current month, today]. week/month
     * default to the last 8 whole weeks, or the last 6 whole months, ending today, when from/to
     * are omitted. sources/slug follow the same conventions as the Analytics tab (see
     * MarketingAnalyticsController).
     */
    @GetMapping
    public MarketingAdsReportDto adsReport(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sources,
            @RequestParam(required = false) String slug,
            @RequestParam(required = false, defaultValue = "week") String period) {
        PeriodKind kind = switch (period.toLowerCase()) {
            case "month" -> PeriodKind.MONTH;
            case "mtd" -> PeriodKind.MONTH_TO_DATE;
            case "custom" -> PeriodKind.CUSTOM;
            default -> PeriodKind.WEEK;
        };
        LocalDate today = LocalDate.now();
        LocalDate end;
        LocalDate start;
        if (kind == PeriodKind.MONTH_TO_DATE) {
            end = today;
            start = today.withDayOfMonth(1);
        } else if (kind == PeriodKind.CUSTOM) {
            if (from == null || to == null) {
                throw new IllegalArgumentException("period=custom requires both from and to");
            }
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } else {
            end = to != null ? LocalDate.parse(to) : today;
            start = from != null ? LocalDate.parse(from)
                    : kind == PeriodKind.WEEK ? end.minusWeeks(7) : end.minusMonths(5).withDayOfMonth(1);
        }
        return service.adsReport(start, end, TrafficSourceParam.parse(sources), slug, kind);
    }

    /** Ad spend has no read-only carve-out beyond the GET above (already OWNER+ADS_MANAGER) —
     * these writes are the one deliberate exception to Ads Manager's otherwise-read-only access,
     * granted in SecurityConfig alongside the old single-endpoint version of this write.
     */
    @PostMapping("/spend")
    public AdSpendEntryDto createSpendEntry(@RequestBody AdSpendEntryRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        AdSpendEntry saved = service.createAdSpendEntry(
                req.landingPageSlug(), req.periodStart(), req.periodEnd(), req.amount(), me.getUsername());
        return toDto(saved);
    }

    @GetMapping("/spend")
    public List<AdSpendEntryDto> listSpendEntries(@RequestParam String slug) {
        return service.listAdSpendEntries(slug).stream().map(MarketingAdsReportController::toDto).toList();
    }

    private static AdSpendEntryDto toDto(AdSpendEntry e) {
        return new AdSpendEntryDto(e.getId(), e.getLandingPageSlug(), e.getPeriodStart(), e.getPeriodEnd(),
                e.getAmountSpent(), e.getEnteredBy(), e.getEnteredAt());
    }

    public record AdSpendEntryRequest(String landingPageSlug, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {}

    public record AdSpendEntryDto(
            Long id, String landingPageSlug, LocalDate periodStart, LocalDate periodEnd,
            BigDecimal amount, String enteredBy, java.time.Instant enteredAt) {}
}
