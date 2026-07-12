package com.salonreview.web;

import com.salonreview.marketing.MarketingDashboardRepository.LandingPageSummary;
import com.salonreview.marketing.MarketingDashboardService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.web.dto.MarketingDashboardDto;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/owner")
public class MarketingDashboardController {

    private final MarketingDashboardService service;

    public MarketingDashboardController(MarketingDashboardService service) {
        this.service = service;
    }

    /** sources is a comma-separated list of traffic-source buckets (meta_ads, google_ads,
     * instagram_organic, google_organic, direct), or "all" — see TrafficSourceParam. Defaults to
     * "Ads only" (meta_ads + google_ads) when omitted, same default as the Contacts/Analytics tabs,
     * since mani runs ads. */
    @GetMapping("/marketing")
    public MarketingDashboardDto marketing(@RequestParam(defaultValue = "mani") String slug,
                                            @RequestParam(required = false) String sources) {
        return service.dashboard(slug, TrafficSourceParam.parse(sources));
    }

    /** Feeds the dashboard's page selector (Overview tab) — every landing page currently in the
     * marketing schema, so a newly-added one (see akluxnails-home) shows up with no frontend change.
     */
    @GetMapping("/marketing/pages")
    public List<LandingPageSummary> pages() {
        return service.listLandingPages();
    }

    @PatchMapping("/marketing/variants/{variantId}")
    @Transactional
    public void updateVariant(@PathVariable UUID variantId, @RequestBody UpdateVariantRequest req) {
        if (req.name() != null) service.renameVariant(variantId, req.name());
        // "" clears the description; a genuinely absent field (null) leaves it untouched.
        if (req.description() != null) service.updateVariantDescription(variantId, req.description());
    }

    @DeleteMapping("/marketing/variants/{variantId}")
    @Transactional
    public void deleteVariant(@PathVariable UUID variantId) {
        service.deleteVariant(variantId);
    }

    @PostMapping("/marketing/variants/{variantId}/duplicate")
    @Transactional
    public DuplicateVariantResponse duplicateVariant(@PathVariable UUID variantId, @RequestBody DuplicateVariantRequest req) {
        UUID newId = service.duplicateVariant(variantId, req.name());
        return new DuplicateVariantResponse(newId.toString());
    }

    @PutMapping("/marketing/stats-since")
    public void updateStatsSince(@RequestParam(defaultValue = "mani") String slug, @RequestBody StatsSinceRequest req) {
        Instant statsSince = parseInstantOrNull(req.value());
        service.updateStatsSince(slug, statsSince);
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp — expected ISO-8601, e.g. 2026-07-10T09:00:00Z");
        }
    }

    public record UpdateVariantRequest(String name, String description) {}
    public record DuplicateVariantRequest(String name) {}
    public record DuplicateVariantResponse(String variantId) {}
    public record StatsSinceRequest(String value) {}
}
