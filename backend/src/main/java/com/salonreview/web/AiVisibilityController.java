package com.salonreview.web;

import com.salonreview.aivisibility.AiVisibilityAdminService;
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
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;

    public AiVisibilityController(AiVisibilityQueryService readModel, AiVisibilityAdminService admin,
            BusinessFeatureService businessFeatures, CurrentBusinessContext currentBusinessContext) {
        this.readModel = readModel;
        this.admin = admin;
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

    @GetMapping("/queries/{id}/history")
    public List<AiVisibilityQueryService.HistoryItem> history(@PathVariable Long id, @RequestParam(defaultValue = "260") int limit) {
        return readModel.history(requireFeatureEnabled(), id, Math.max(1, Math.min(limit, 520)));
    }

    @PostMapping("/queries")
    public Map<String, Object> add(@RequestBody QueryRequest req) {
        var q = admin.add(requireFeatureEnabled(), req.query(), req.topic(), req.targetUrl());
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

    @GetMapping("/runs/{id}")
    public RunDto run(@PathVariable Long id) {
        return RunDto.of(admin.run(requireFeatureEnabled(), id));
    }

    @GetMapping("/settings")
    public SettingsDto settings() {
        return SettingsDto.of(admin.settings(requireFeatureEnabled()));
    }

    @PutMapping("/settings")
    public SettingsDto updateSettings(@RequestBody SettingsDto req) {
        return SettingsDto.of(admin.updateSettings(requireFeatureEnabled(), req.brandAliases(), req.ownDomains(), req.monthlyBudgetUsd(), req.digestEnabled()));
    }

    public record QueryRequest(String query, String topic, String targetUrl) {
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

    public record SettingsDto(List<String> brandAliases, List<String> ownDomains, BigDecimal monthlyBudgetUsd, Boolean digestEnabled) {
        static SettingsDto of(AiVisibilitySettings s) {
            return new SettingsDto(s.getBrandAliases(), s.getOwnDomains(), s.getMonthlyBudgetUsd(), s.isDigestEnabled());
        }
    }
}
