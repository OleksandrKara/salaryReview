package com.salonreview.web;

import com.salonreview.ai.FunnelAnalysisResult;
import com.salonreview.ai.FunnelAnalysisService;
import com.salonreview.config.AiFunnelAnalysisProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Language;
import com.salonreview.repo.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Owner/ads-manager-only AI funnel-analysis endpoint. Sits under {@code /api/owner/marketing/**}
 * so it inherits the existing OWNER+ADS_MANAGER GET role gate in {@code SecurityConfig} — no
 * security wiring change needed. Mirrors {@code SuspiciousTriageController}'s feature-flag-404
 * pattern exactly.
 */
@RestController
public class FunnelAnalysisController {

    private final FunnelAnalysisService service;
    private final AiFunnelAnalysisProperties props;
    private final AppUserRepository users;
    private final CurrentBusinessContext currentBusinessContext;
    private final BusinessFeatureService businessFeatures;

    public FunnelAnalysisController(FunnelAnalysisService service, AiFunnelAnalysisProperties props, AppUserRepository users,
                                    CurrentBusinessContext currentBusinessContext, BusinessFeatureService businessFeatures) {
        this.service = service;
        this.props = props;
        this.users = users;
        this.currentBusinessContext = currentBusinessContext;
        this.businessFeatures = businessFeatures;
    }

    private boolean enabledForCaller() {
        return props.isEnabled() && businessFeatures.isEnabled(
                currentBusinessContext.id(), BusinessFeatureService.AI_FUNNEL_ANALYSIS_ENABLED);
    }

    /** mode defaults to "ads", same convention as the dashboard endpoints; anything other than
     * exactly "all" is treated as "ads". force=true bypasses the cache and always calls Claude
     * fresh — the owner-facing "run again anyway" action, distinct from a plain repeat click.
     * Generates the analysis in the caller's preferred language (Russian owners/ads-managers get
     * a Russian analysis) — resolved server-side, same as {@code RagController}. */
    @PostMapping("/api/owner/marketing/funnel/analyze")
    public ResponseEntity<FunnelAnalysisResult> analyze(@RequestParam String slug, @RequestParam String flowKey,
                                                         @RequestParam(defaultValue = "ads") String mode,
                                                         @RequestParam(defaultValue = "false") boolean force,
                                                         @AuthenticationPrincipal AppUserPrincipal me) {
        if (!enabledForCaller()) return ResponseEntity.notFound().build();
        return service.analyze(slug, flowKey, !"all".equalsIgnoreCase(mode), force, language(me))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The caller's preferred language, read fresh from the DB; English when unset — same helper
     * as {@code RagController.language(me)}. */
    private Language language(AppUserPrincipal me) {
        if (me == null) return Language.EN;
        return users.findById(me.getUserId())
                .map(AppUser::getPreferredLanguage)
                .orElse(Language.EN) == Language.RU ? Language.RU : Language.EN;
    }

    /** Past analyses for this landing page/flow, newest first — powers the owner-facing history
     * list so a past result stays visible (with its timestamp) without re-running the LLM. */
    @GetMapping("/api/owner/marketing/funnel/analyze/history")
    public ResponseEntity<List<FunnelAnalysisResult>> history(@RequestParam String slug, @RequestParam String flowKey) {
        if (!enabledForCaller()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(service.history(slug, flowKey));
    }
}
