package com.salonreview.web;

import com.salonreview.marketing.MarketingDashboardService;
import com.salonreview.web.dto.MarketingDashboardDto;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

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

    @PatchMapping("/marketing/variants/{variantId}")
    @Transactional
    public void updateVariant(@PathVariable UUID variantId, @RequestBody UpdateVariantRequest req) {
        if (req.name() != null) service.renameVariant(variantId, req.name());
        if (req.active() != null) service.setVariantActive(variantId, req.active());
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

    public record UpdateVariantRequest(String name, Boolean active) {}
    public record DuplicateVariantRequest(String name) {}
    public record DuplicateVariantResponse(String variantId) {}
    public record StatsSinceRequest(String value) {}
}
