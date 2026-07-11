package com.salonreview.web;

import com.salonreview.ai.FunnelAnalysisResult;
import com.salonreview.ai.FunnelAnalysisService;
import com.salonreview.config.AiFunnelAnalysisProperties;
import org.springframework.http.ResponseEntity;
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

    public FunnelAnalysisController(FunnelAnalysisService service, AiFunnelAnalysisProperties props) {
        this.service = service;
        this.props = props;
    }

    /** mode defaults to "ads", same convention as the dashboard endpoints; anything other than
     * exactly "all" is treated as "ads". force=true bypasses the cache and always calls Claude
     * fresh — the owner-facing "run again anyway" action, distinct from a plain repeat click. */
    @PostMapping("/api/owner/marketing/funnel/analyze")
    public ResponseEntity<FunnelAnalysisResult> analyze(@RequestParam String slug, @RequestParam String flowKey,
                                                         @RequestParam(defaultValue = "ads") String mode,
                                                         @RequestParam(defaultValue = "false") boolean force) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return service.analyze(slug, flowKey, !"all".equalsIgnoreCase(mode), force)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Past analyses for this landing page/flow, newest first — powers the owner-facing history
     * list so a past result stays visible (with its timestamp) without re-running the LLM. */
    @GetMapping("/api/owner/marketing/funnel/analyze/history")
    public ResponseEntity<List<FunnelAnalysisResult>> history(@RequestParam String slug, @RequestParam String flowKey) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(service.history(slug, flowKey));
    }
}
