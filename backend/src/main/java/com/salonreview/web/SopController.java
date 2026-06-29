package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Role;
import com.salonreview.domain.Sop;
import com.salonreview.domain.SopAudience;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.kb.KbAiDraftService;
import com.salonreview.sop.SopService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * SOP endpoints. Coarse role gating is in {@code SecurityConfig} (owner-only writes + roster +
 * version history; acknowledge = MANAGER/PROVIDER; GET list = any authenticated). The service
 * additionally enforces audience on reads and acknowledge, so the UI does no authorization itself.
 */
@RestController
@RequestMapping("/api/sops")
public class SopController {

    private final SopService sops;
    private final KbAiDraftService aiDraft;

    public SopController(SopService sops, KbAiDraftService aiDraft) {
        this.sops = sops;
        this.aiDraft = aiDraft;
    }

    // ---- reads ----

    @GetMapping
    public List<SopDto> list(@AuthenticationPrincipal AppUserPrincipal me) {
        return sops.list(me.getRole(), me.getUserId()).stream().map(SopController::toDto).toList();
    }

    @GetMapping("/{id}/versions")
    public List<SopVersionDto> versions(@PathVariable Long id) {
        return sops.versionHistory(id).stream().map(SopController::toVersionDto).toList();
    }

    @GetMapping("/{id}/acknowledgment-status")
    public List<RosterDto> roster(@PathVariable Long id) {
        return sops.roster(id).stream()
                .map(r -> new RosterDto(r.userId(), r.username(), r.role().name(),
                        r.acknowledged(), r.acknowledgedAt()))
                .toList();
    }

    // ---- authoring (owner) ----

    @PostMapping
    public ResponseEntity<SopDto> create(@RequestBody CreateRequest body,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        if (isBlank(body.title()) || isBlank(body.category()) || body.audience() == null) {
            return ResponseEntity.badRequest().build();
        }
        Sop sop = sops.create(body.title(), body.category(), body.audience(), body.body(), body.bodyRu(),
                me.getUsername());
        return ResponseEntity.ok(toDto(sops.item(sop, null)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SopDto> update(@PathVariable Long id, @RequestBody UpdateRequest body) {
        if (isBlank(body.title()) || isBlank(body.category()) || body.audience() == null) {
            return ResponseEntity.badRequest().build();
        }
        return sops.updateMeta(id, body.title(), body.category(), body.audience())
                .map(s -> ResponseEntity.ok(toDto(sops.item(s, null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<SopVersionDto> addVersion(@PathVariable Long id, @RequestBody VersionRequest body,
                                                    @AuthenticationPrincipal AppUserPrincipal me) {
        return sops.addVersion(id, body.body(), body.bodyRu(), me.getUsername())
                .map(v -> ResponseEntity.ok(toVersionDto(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Translate an English version body into Russian (owner; keeps customer-facing English intact). */
    @PostMapping("/ai-translate")
    public ResponseEntity<AiTranslateResponse> aiTranslate(@RequestBody AiTranslateRequest body) {
        if (isBlank(body.body())) return ResponseEntity.badRequest().build();
        try {
            return ResponseEntity.ok(new AiTranslateResponse(aiDraft.translateToRussian(body.body())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PostMapping("/{id}/versions/{versionId}/publish")
    public ResponseEntity<SopDto> publish(@PathVariable Long id, @PathVariable Long versionId) {
        try {
            return sops.publish(id, versionId)
                    .map(s -> ResponseEntity.ok(toDto(sops.item(s, null))))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (SopService.AlreadyPublishedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<SopDto> archive(@PathVariable Long id) {
        return sops.setStatus(id, SopStatus.ARCHIVED)
                .map(s -> ResponseEntity.ok(toDto(sops.item(s, null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/unarchive")
    public ResponseEntity<SopDto> unarchive(@PathVariable Long id) {
        return sops.setStatus(id, SopStatus.ACTIVE)
                .map(s -> ResponseEntity.ok(toDto(sops.item(s, null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- acknowledge (staff) ----

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<SopDto> acknowledge(@PathVariable Long id,
                                              @AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return sops.acknowledge(id, me.getUserId(), me.getRole())
                    .map(item -> ResponseEntity.ok(toDto(item)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (SopService.OutOfAudienceException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (SopService.NothingToAcknowledgeException e) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }

    // ---- mapping ----

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static SopDto toDto(SopService.SopListItem it) {
        Sop s = it.sop();
        return new SopDto(s.getId(), s.getTitle(), s.getCategory(), s.getAudience().name(),
                s.getStatus().name(),
                it.currentVersion() == null ? null : toVersionDto(it.currentVersion()),
                it.acknowledged(), it.acknowledgedAt(),
                s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private static SopVersionDto toVersionDto(SopVersion v) {
        return new SopVersionDto(v.getId(), v.getVersionNumber(), v.getBody(), v.getBodyRu(),
                v.getStatus().name(), v.getCreatedBy(), v.getCreatedAt());
    }

    public record SopDto(Long id, String title, String category, String audience, String status,
                         SopVersionDto currentVersion, boolean acknowledged, Instant acknowledgedAt,
                         String createdBy, Instant createdAt, Instant updatedAt) {}

    public record SopVersionDto(Long id, Integer versionNumber, String body, String bodyRu, String status,
                                String createdBy, Instant createdAt) {}

    public record RosterDto(Long userId, String username, String role, boolean acknowledged,
                            Instant acknowledgedAt) {}

    public record CreateRequest(String title, String category, SopAudience audience, String body, String bodyRu) {}

    public record UpdateRequest(String title, String category, SopAudience audience) {}

    public record VersionRequest(String body, String bodyRu) {}

    public record AiTranslateRequest(String body) {}

    public record AiTranslateResponse(String markdown) {}
}
