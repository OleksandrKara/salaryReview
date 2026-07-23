package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AdSpendEntry;
import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.marketing.MarketingAnalyticsService.PeriodKind;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.MarketingAdsReportDto;
import com.salonreview.web.dto.MarketingCustomerHistoryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/owner/marketing/ads-report")
public class MarketingAdsReportController {

    // This controller has no SquareClient injected, and computing default week/month/mtd ranges
    // is a low-stakes fallback (only used when the caller omits from/to) rather than a
    // data-correctness path — so unlike the zone-aware services elsewhere in this codebase, it's
    // simplest to hardcode the salon's real zone directly here, same precedent as
    // TelegramNotificationService's own hardcoded "America/Los_Angeles".
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final MarketingAnalyticsService service;
    private final MarketingContactsService contactsService;

    public MarketingAdsReportController(MarketingAnalyticsService service, MarketingContactsService contactsService) {
        this.service = service;
        this.contactsService = contactsService;
    }

    /** period is "week" (default), "month", "mtd" (month-to-date), "custom", or "all" — which
     * grain to bucket into (see MarketingAnalyticsService.PeriodKind). "custom" requires explicit
     * from/to (no default-range fallback — a caller-specified range is the whole point of that
     * kind). "mtd"/"all" ignore from/to entirely — "mtd" is always [1st of the current month,
     * today], "all" is the service's own all-time start through today. week/month default to the
     * last 8 whole weeks, or the last 6 whole months, ending today, when from/to are omitted.
     * sources/slug follow the same conventions as the Analytics tab (see
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
            case "all" -> PeriodKind.ALL;
            default -> PeriodKind.WEEK;
        };
        LocalDate today = LocalDate.now(SALON_ZONE);
        LocalDate end;
        LocalDate start;
        if (kind == PeriodKind.MONTH_TO_DATE) {
            end = today;
            start = today.withDayOfMonth(1);
        } else if (kind == PeriodKind.ALL) {
            // Both ignored by the service for ALL (it computes its own all-time start), but
            // adsReport() still takes from/to as parameters — pass today for both so the
            // "no periods" empty-DTO fallback (see adsReport's own from/to echo) is sane if it's
            // ever reached, same as MONTH_TO_DATE effectively does today via end/start above.
            end = today;
            start = today;
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

    @PutMapping("/spend/{id}")
    public ResponseEntity<AdSpendEntryDto> updateSpendEntry(@PathVariable Long id, @RequestBody AdSpendEntryRequest req) {
        return service.updateAdSpendEntry(id, req.periodStart(), req.periodEnd(), req.amount())
                .map(MarketingAdsReportController::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/spend/{id}")
    public ResponseEntity<Void> deleteSpendEntry(@PathVariable Long id) {
        return service.deleteAdSpendEntry(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** One customer's submission + appointment history, fetched lazily when the owner expands a
     * row on the breakdown drill-down's Completed/Anticipated lists — not bundled into the
     * breakdown's own response, so that stays fast regardless of how many customers are in range.
     */
    @GetMapping("/customer-history")
    public MarketingCustomerHistoryDto customerHistory(@RequestParam String customerId) {
        return contactsService.contactByCustomerId(customerId)
                .map(c -> new MarketingCustomerHistoryDto(c.submissions(), c.appointments()))
                .orElse(MarketingCustomerHistoryDto.empty());
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
