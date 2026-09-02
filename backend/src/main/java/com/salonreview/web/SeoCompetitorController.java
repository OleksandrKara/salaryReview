package com.salonreview.web;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.seo.SeoCompetitorService;
import com.salonreview.seo.SeoCompetitorService.CompetitorRow;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Owner-facing competitor CRUD (seo-intelligence-advisor Phase 7, zero-cost scope). GET falls
 * under the existing {@code /api/owner/marketing/**} OWNER+ADS_MANAGER read gate; mutating
 * endpoints fall through to the OWNER-only catch-all (same convention as {@code
 * SeoDashboardController}'s tracked-query/tracked-keyword endpoints — curating competitors is an
 * owner decision). Gated only by {@code seo-monitoring.enabled} — this isn't a second AI feature
 * like the Advisor, just more SEO-tab content, so no separate deployment-level flag.
 */
@RestController
@RequestMapping("/api/owner/marketing/seo/competitors")
public class SeoCompetitorController {

    private final SeoCompetitorService service;
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;

    public SeoCompetitorController(SeoCompetitorService service, BusinessFeatureService businessFeatures,
            CurrentBusinessContext currentBusinessContext) {
        this.service = service;
        this.businessFeatures = businessFeatures;
        this.currentBusinessContext = currentBusinessContext;
    }

    private Long requireFeatureEnabled() {
        Long businessId = currentBusinessContext.id();
        if (!businessFeatures.isEnabled(businessId, BusinessFeatureService.SEO_MONITORING_ENABLED)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SEO monitoring is not enabled for this business");
        }
        return businessId;
    }

    @GetMapping
    public List<CompetitorRowDto> list() {
        Long businessId = requireFeatureEnabled();
        return service.competitors(businessId).stream().map(SeoCompetitorController::toDto).toList();
    }

    @PostMapping
    public List<CompetitorRowDto> add(@RequestBody CompetitorRequest request) {
        Long businessId = requireFeatureEnabled();
        String name = request.name() == null ? "" : request.name().trim();
        String website = request.website() == null ? "" : request.website().trim();
        if (name.isEmpty() || website.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and website must not be blank");
        }
        String location = request.location() == null || request.location().isBlank() ? null : request.location().trim();
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim();
        service.addCompetitor(businessId, name, website, location, notes);
        return list();
    }

    /** Updates only the owner-entered GBP rating/review count and the active flag — name/website
     * are set once at creation (delete + re-add for those, same as tracked keywords). */
    @PutMapping("/{id}")
    public List<CompetitorRowDto> update(@PathVariable Long id, @RequestBody CompetitorUpdateRequest request) {
        Long businessId = requireFeatureEnabled();
        service.updateCompetitorGbp(businessId, id, request.gbpRating(), request.gbpReviewCount());
        if (request.active() != null) {
            service.setCompetitorActive(businessId, id, request.active());
        }
        return list();
    }

    @DeleteMapping("/{id}")
    public List<CompetitorRowDto> remove(@PathVariable Long id) {
        Long businessId = requireFeatureEnabled();
        service.removeCompetitor(businessId, id);
        return list();
    }

    private static CompetitorRowDto toDto(CompetitorRow row) {
        return new CompetitorRowDto(row.id(), row.name(), row.website(), row.location(), row.notes(), row.active(),
                row.gbpRating(), row.gbpReviewCount(), row.gbpUpdatedAt(),
                toDto(row.latestMobile()), toDto(row.latestDesktop()));
    }

    private static SeoDashboardController.CoreWebVitalsDto toDto(com.salonreview.seo.SeoDashboardService.CoreWebVitals v) {
        if (v == null) return null;
        return new SeoDashboardController.CoreWebVitalsDto(v.date(), v.performanceScore(), v.lcpMs(), v.cls(), v.fcpMs(), v.tbtMs());
    }

    public record CompetitorRowDto(Long id, String name, String website, String location, String notes,
            boolean active, BigDecimal gbpRating, Integer gbpReviewCount, Instant gbpUpdatedAt,
            SeoDashboardController.CoreWebVitalsDto latestMobile, SeoDashboardController.CoreWebVitalsDto latestDesktop) {
    }

    public record CompetitorRequest(String name, String website, String location, String notes) {
    }

    public record CompetitorUpdateRequest(BigDecimal gbpRating, Integer gbpReviewCount, Boolean active) {
    }
}
