package com.salonreview.web;

import com.salonreview.config.AiTriageProperties;
import com.salonreview.config.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Returns the authenticated user (username, role, linked providerId) — the frontend routes on this.
 *
 * <p>Also returns a {@code features} object with deployment-level feature flags the frontend needs
 * to render the right UI (e.g. whether the AI triage Explain button should be shown). Putting the
 * flags here piggybacks on the existing /api/me round-trip the frontend already does on every
 * page load — no separate config endpoint needed.
 */
@RestController
public class MeController {

    private final AiTriageProperties aiTriage;

    public MeController(AiTriageProperties aiTriage) {
        this.aiTriage = aiTriage;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", principal.getUsername());
        body.put("role", principal.getRole().name());
        body.put("providerId", principal.getProviderId());
        body.put("features", Map.of("aiTriageEnabled", aiTriage.isEnabled()));
        return body;
    }
}
