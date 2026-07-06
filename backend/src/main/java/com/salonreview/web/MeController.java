package com.salonreview.web;

import com.salonreview.config.AiTriageProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Language;
import com.salonreview.repo.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 *
 * <p>{@code preferredLanguage} rides along too: null means the user hasn't chosen,
 * which the frontend uses to show the one-time setup prompt. It's read fresh from the DB so a change
 * is reflected immediately (the session principal isn't reloaded mid-session).
 */
@RestController
public class MeController {

    private final AiTriageProperties aiTriage;
    private final RagProperties rag;
    private final AppUserRepository users;

    public MeController(AiTriageProperties aiTriage, RagProperties rag, AppUserRepository users) {
        this.aiTriage = aiTriage;
        this.rag = rag;
        this.users = users;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        Language lang = users.findById(principal.getUserId())
                .map(AppUser::getPreferredLanguage)
                .orElse(null);

        Map<String, Object> body = new HashMap<>();
        body.put("username", principal.getUsername());
        body.put("role", principal.getRole().name());
        body.put("providerId", principal.getProviderId());
        body.put("preferredLanguage", lang == null ? null : lang.name());
        body.put("features", Map.of(
                "aiTriageEnabled", aiTriage.isEnabled(),
                "ragSuggestionsEnabled", rag.isEnabled() && rag.getSuggestions().isEnabled(),
                "ragFollowupsEnabled", rag.isEnabled() && rag.getFollowups().isEnabled()));
        return body;
    }

    /** Set the caller's preferred language (any authenticated user). */
    @PostMapping("/api/me/language")
    public ResponseEntity<Void> setLanguage(@RequestBody LanguageRequest body,
                                            @AuthenticationPrincipal AppUserPrincipal principal) {
        Language lang = parse(body == null ? null : body.language());
        if (lang == null) return ResponseEntity.badRequest().build();
        return users.findById(principal.getUserId())
                .map(u -> {
                    u.setPreferredLanguage(lang);
                    users.save(u);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Language parse(String raw) {
        if (raw == null) return null;
        try {
            return Language.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record LanguageRequest(String language) {}
}
