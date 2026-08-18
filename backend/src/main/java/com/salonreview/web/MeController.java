package com.salonreview.web;

import com.salonreview.config.AiTriageProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.Language;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.PlatformAdminRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
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
    private final CurrentBusinessContext currentBusinessContext;
    private final PlatformAdminRepository platformAdmins;
    private final BusinessMembershipRepository memberships;
    private final BusinessRepository businesses;

    public MeController(AiTriageProperties aiTriage, RagProperties rag, AppUserRepository users,
                        CurrentBusinessContext currentBusinessContext, PlatformAdminRepository platformAdmins,
                        BusinessMembershipRepository memberships, BusinessRepository businesses) {
        this.aiTriage = aiTriage;
        this.rag = rag;
        this.users = users;
        this.currentBusinessContext = currentBusinessContext;
        this.platformAdmins = platformAdmins;
        this.memberships = memberships;
        this.businesses = businesses;
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
        // Phase 6.1/6.2 (design.md D12): activeBusinessId reflects any session-level switch
        // (CurrentBusinessContext is already populated for this request by
        // CurrentBusinessContextFilter, session-override-aware); businesses is the switcher's own
        // options list — every business for a platform_admin (design.md D4's "manage every
        // business's onboarding"), otherwise just this user's own real membership(s) (today always
        // exactly one, so the frontend renders plain text, not a dropdown, for that case).
        body.put("activeBusinessId", currentBusinessContext.id());
        body.put("businesses", switchableBusinesses(principal.getUserId()));
        return body;
    }

    private List<Map<String, Object>> switchableBusinesses(Long userId) {
        List<Business> options = platformAdmins.existsById(userId)
                ? businesses.findAllByActiveTrue()
                : memberships.findByUserId(userId).stream()
                        .map(m -> businesses.findById(m.getBusinessId()).orElse(null))
                        .filter(b -> b != null && b.isActive())
                        .toList();
        return options.stream()
                .map(b -> Map.<String, Object>of("id", b.getId(), "name", b.getName()))
                .toList();
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
