package com.salonreview.web;

import com.salonreview.ai.LanguageResolver;
import com.salonreview.ai.SeoAiAdvisorService;
import com.salonreview.ai.SeoAnalysisResult;
import com.salonreview.config.AiSeoAdvisorProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Owner/ads-manager-only SEO AI Advisor endpoint. Sits under {@code /api/owner/marketing/**} so
 * it inherits the existing OWNER+ADS_MANAGER GET role gate in {@code SecurityConfig} — no security
 * wiring change needed. Mirrors {@link FunnelAnalysisController}'s feature-flag-404 pattern
 * exactly (seo-intelligence-advisor design.md D7).
 */
@RestController
public class SeoAiAdvisorController {

    private final SeoAiAdvisorService service;
    private final AiSeoAdvisorProperties props;
    private final AppUserRepository users;
    private final CurrentBusinessContext currentBusinessContext;
    private final BusinessFeatureService businessFeatures;

    public SeoAiAdvisorController(SeoAiAdvisorService service, AiSeoAdvisorProperties props, AppUserRepository users,
            CurrentBusinessContext currentBusinessContext, BusinessFeatureService businessFeatures) {
        this.service = service;
        this.props = props;
        this.users = users;
        this.currentBusinessContext = currentBusinessContext;
        this.businessFeatures = businessFeatures;
    }

    private boolean enabledForCaller() {
        return props.isEnabled() && businessFeatures.isEnabled(
                currentBusinessContext.id(), BusinessFeatureService.AI_SEO_ADVISOR_ENABLED);
    }

    /** force=true bypasses the cache and always calls Claude fresh — the owner-facing "Analyze
     * again" action, distinct from a plain repeat click. Generates the analysis in the caller's
     * preferred language, resolved server-side, same as {@code FunnelAnalysisController}. 404 when
     * disabled OR when the business has no seo_connection yet (nothing to analyze). */
    @PostMapping("/api/owner/marketing/seo/advisor/analyze")
    public ResponseEntity<SeoAnalysisResult> analyze(@RequestParam(defaultValue = "false") boolean force,
            @AuthenticationPrincipal AppUserPrincipal me) {
        if (!enabledForCaller()) return ResponseEntity.notFound().build();
        return service.analyze(currentBusinessContext.id(), force, LanguageResolver.resolve(users, me))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Past analyses for this business, newest first — powers the owner-facing history list so a
     * past result stays visible (with its timestamp) without re-running the LLM. */
    @GetMapping("/api/owner/marketing/seo/advisor/history")
    public ResponseEntity<List<SeoAnalysisResult>> history() {
        if (!enabledForCaller()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(service.history(currentBusinessContext.id()));
    }
}
