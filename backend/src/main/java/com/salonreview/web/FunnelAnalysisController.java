package com.salonreview.web;

import com.salonreview.ai.FunnelAnalysisResult;
import com.salonreview.ai.FunnelAnalysisService;
import com.salonreview.config.AiFunnelAnalysisProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * exactly "all" is treated as "ads". */
    @PostMapping("/api/owner/marketing/funnel/analyze")
    public ResponseEntity<FunnelAnalysisResult> analyze(@RequestParam String slug, @RequestParam String flowKey,
                                                         @RequestParam(defaultValue = "ads") String mode) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return service.analyze(slug, flowKey, !"all".equalsIgnoreCase(mode))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
