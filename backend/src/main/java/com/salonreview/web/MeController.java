package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** Returns the authenticated user (username, role, linked providerId) — the frontend routes on this. */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", principal.getUsername());
        body.put("role", principal.getRole().name());
        body.put("providerId", principal.getProviderId());
        return body;
    }
}
