package com.salonreview.web;

import com.salonreview.aivisibility.AiVisibilityAdminService;
import com.salonreview.aivisibility.AiVisibilityPageService;
import com.salonreview.seo.SeoCompetitorService;
import com.salonreview.marketing.AiReferralStatsRepository;
import com.salonreview.aivisibility.AiVisibilityQueryService;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** AI tab (openspec/changes/ai-visibility-monitoring design.md D6). GETs fall under the existing
 * {@code /api/owner/marketing/**} OWNER+ADS_MANAGER read gate; every write falls through to the
 * OWNER-only catch-all. 404 when {@code ai-visibility.enabled} is off for the business. */
@RestController
@RequestMapping("/api/owner/marketing/ai")
public class AiVisibilityController {

    private final AiVisibilityQueryService readModel;
    private final AiVisibilityAdminService admin;
    private final AiVisibilityPageService pages;
    private final SeoCompetitorService competitors;
    private final AiReferralStatsRepository aiReferrals;
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;

    public AiVisibilityController(AiVisibilityQueryService readModel, AiVisibilityAdminService admin, AiVisibilityPageService pages,
            SeoCompetitorService competitors, AiReferralStatsRepository aiReferrals, BusinessFeatureService businessFeatures,
            CurrentBusinessContext currentBusinessContext) {
        this.readModel = readModel;
        this.admin = admin;
        this.pages = pages;
        this.competitors = competitors;
        this.aiReferrals = aiReferrals;
        this.businessFeatures = businessFeatures;
        this.currentBusinessContext = currentBusinessContext;
    }

    private Long requireFeatureEnabled() {
        Long businessId = currentBusinessContext.id();
        if (!businessFeatures.isEnabled(businessId, BusinessFeatureService.AI_VISIBILITY_ENABLED)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI visibility is not enabled for this business");
        }
        return businessId;
    }

    @GetMapping("/overview")
    public AiVisibilityQueryService.Overview overview() {
        return readModel.overview(requireFeatureEnabled());
    }

    @GetMapping("/trends")
    public AiVisibilityQueryService.Trends trends(@RequestParam(defaultValue = "12") int weeks) {
        return readModel.trends(requireFeatureEnabled(), Math.max(2, Math.min(weeks, 52)));
    }

    /** "From AI to clients": AI-referred visitors, leads, bookings, paying clients and revenue. */
    @GetMapping("/traffic")
    public AiReferralStatsRepository.Stats traffic(@RequestParam(defaultValue = "90") int days) {
        return aiReferrals.stats(requireFeatureEnabled(), Math.max(7, Math.min(days, 365)));
    }

    @GetMapping("/queries/{id}/history")
    public List<AiVisibilityQueryService.HistoryItem> history(@PathVariable Long id, @RequestParam(defaultValue = "260") int limit) {
        return readModel.history(requireFeatureEnabled(), id, Math.max(1, Math.min(limit, 520)));
    }

    @PostMapping("/queries")
    public Map<String, Object> add(@RequestBody QueryRequest req) {
        boolean nearMe = req.nearMe() != null ? req.nearMe() : req.query() != null && req.query().toLowerCase().contains("near me");
        var q = admin.add(requireFeatureEnabled(), req.query(), req.topic(), req.targetUrl(), nearMe);
        return Map.of("id", q.getId());
    }

    @PatchMapping("/queries/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody QueryPatch req) {
        var q = admin.update(requireFeatureEnabled(), id, req.query(), req.topic(), req.targetUrl(), req.status(), req.sortOrder());
        return Map.of("id", q.getId(), "status", q.getStatus().name());
    }

    @PostMapping("/queries/approve-all")
    public Map<String, Object> approveAll() {
        return Map.of("approved", admin.approveAll(requireFeatureEnabled()));
    }

    @PostMapping("/queries/{id}/recheck")
    public RunDto recheck(@PathVariable Long id) {
        return RunDto.of(admin.recheck(requireFeatureEnabled(), id));
    }

    @PostMapping("/queries/recheck")
    public Map<String, Object> recheckMany(@RequestBody RecheckRequest req) {
        var b = admin.recheckMany(requireFeatureEnabled(), req.ids());
        return Map.of("run", RunDto.of(b.run()), "started", b.started(), "skippedToday", b.skippedToday(), "skippedNotActive", b.skippedNotActive());
    }

    public record RecheckRequest(List<Long> ids) {
    }

    @GetMapping("/runs/{id}")
    public RunDto run(@PathVariable Long id) {
        return RunDto.of(admin.run(requireFeatureEnabled(), id));
    }

    @GetMapping("/queries/{id}/pages")
    public AiVisibilityPageService.Comparison pages(@PathVariable Long id) {
        return pages.comparison(requireFeatureEnabled(), id);
    }

    @PostMapping("/queries/{id}/pages/refresh")
    public Map<String, Object> refreshPages(@PathVariable Long id) {
        pages.startRefresh(requireFeatureEnabled(), id);
        return Map.of("started", true);
    }

    @GetMapping("/page-gaps")
    public List<AiVisibilityPageService.PageGap> pageGaps() {
        return pages.pageGaps(requireFeatureEnabled());
    }

    /** "Add to competitors" from the who-AI-recommends-instead list: reuses the SEO tab's competitor
     * list (which already tracks competitor PageSpeed), always for the current business only. */
    @PostMapping("/competitors")
    public Map<String, Object> addCompetitor(@RequestBody CompetitorRequest req) {
        Long businessId = requireFeatureEnabled();
        String name = req.name() == null ? "" : req.name().trim();
        String website = req.website() == null ? "" : req.website().trim();
        if (name.isEmpty() || !website.matches("(?i)^(https?://)?[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and a website are required");
        }
        competitors.addCompetitor(businessId, name, website.startsWith("http") ? website : "https://" + website, null, "Added from the AI tab");
        return Map.of("added", true);
    }

    public record CompetitorRequest(String name, String website) {
    }

    @GetMapping("/settings")
    public SettingsDto settings() {
        return SettingsDto.of(admin.settings(requireFeatureEnabled()));
    }

    @PutMapping("/settings")
    public SettingsDto updateSettings(@RequestBody SettingsDto req) {
        return SettingsDto.of(admin.updateSettings(requireFeatureEnabled(), req.brandAliases(), req.ownDomains(), req.monthlyBudgetUsd(),
                req.digestEnabled(), req.searchPoints()));
    }

    public record QueryRequest(String query, String topic, String targetUrl, Boolean nearMe) {
    }

    public record QueryPatch(String query, String topic, String targetUrl, String status, Integer sortOrder) {
    }

    public record RunDto(Long id, String trigger, Instant startedAt, Instant finishedAt, int checksOk, int checksFailed,
            BigDecimal costUsd, String stoppedReason) {
        static RunDto of(AiVisibilityRun r) {
            return new RunDto(r.getId(), r.getTrigger().name(), r.getStartedAt(), r.getFinishedAt(), r.getChecksOk(), r.getChecksFailed(),
                    r.getCostUsd(), r.getStoppedReason());
        }
    }

    public record SettingsDto(List<String> brandAliases, List<String> ownDomains, BigDecimal monthlyBudgetUsd, Boolean digestEnabled,
            List<Map<String, Object>> searchPoints) {
        static SettingsDto of(AiVisibilitySettings s) {
            return new SettingsDto(s.getBrandAliases(), s.getOwnDomains(), s.getMonthlyBudgetUsd(), s.isDigestEnabled(), s.getSearchPoints());
        }
    }

    /** Spring Boot hides exception reasons from error bodies (server.error.include-message=never);
     * the AI tab shows the real reason ("already re-checked today") instead of a bare 409. Only
     * ResponseStatusException reasons written in this controller's services reach the client. */
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode())
                .body(Map.of("status", e.getStatusCode().value(), "message", e.getReason() == null ? "Request failed" : e.getReason()));
    }
}
