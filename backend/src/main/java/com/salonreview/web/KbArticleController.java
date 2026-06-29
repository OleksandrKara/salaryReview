package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.KbArticle;
import com.salonreview.domain.Role;
import com.salonreview.kb.KbAiDraftService;
import com.salonreview.kb.KbArticleService;
import com.salonreview.kb.KbSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * KB article endpoints. Coarse role gating lives in {@code SecurityConfig} (GET = any authenticated;
 * writes/sync/ai-draft = OWNER+MANAGER); per-article read access is enforced here via the service's
 * {@code visibleRoles} filter, so a provider can't fetch an article not shared with PROVIDER.
 */
@RestController
@RequestMapping("/api/kb-articles")
public class KbArticleController {

    private final KbArticleService articles;
    private final KbSyncService sync;
    private final KbAiDraftService aiDraft;

    public KbArticleController(KbArticleService articles, KbSyncService sync, KbAiDraftService aiDraft) {
        this.articles = articles;
        this.sync = sync;
        this.aiDraft = aiDraft;
    }

    @GetMapping
    public List<KbArticleDto> list(@AuthenticationPrincipal AppUserPrincipal me) {
        return articles.list(me.getRole()).stream().map(KbArticleController::toDto).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<KbArticleDto> get(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return articles.get(id, me.getRole()).map(a -> ResponseEntity.ok(toDto(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<KbArticleDto> create(@RequestBody WriteRequest body,
                                               @AuthenticationPrincipal AppUserPrincipal me) {
        if (isBlank(body.title()) || isBlank(body.category())) return ResponseEntity.badRequest().build();
        KbArticle a = articles.create(body.title(), body.category(), body.body(), body.bodyRu(),
                body.visibleRoles(), me.getUsername());
        return ResponseEntity.ok(toDto(a));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KbArticleDto> update(@PathVariable Long id, @RequestBody WriteRequest body) {
        if (isBlank(body.title()) || isBlank(body.category())) return ResponseEntity.badRequest().build();
        return articles.update(id, body.title(), body.category(), body.body(), body.bodyRu(), body.visibleRoles())
                .map(a -> ResponseEntity.ok(toDto(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return articles.delete(id, me.getUsername())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<KbArticleDto> syncOne(@PathVariable Long id,
                                                @AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return sync.syncOne(id, me.getUsername()).map(a -> ResponseEntity.ok(toDto(a)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (KbSyncService.SyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/sync-all")
    public ResponseEntity<List<KbArticleDto>> syncAll(@AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return ResponseEntity.ok(sync.syncAll(me.getUsername()).stream()
                    .map(KbArticleController::toDto).toList());
        } catch (KbSyncService.SyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/ai-draft")
    public ResponseEntity<AiDraftResponse> aiDraft(@RequestBody AiDraftRequest body) {
        if (isBlank(body.prompt())) return ResponseEntity.badRequest().build();
        try {
            return ResponseEntity.ok(new AiDraftResponse(aiDraft.draft(body.prompt(), body.currentBody())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** Translate the English body into Russian for staff, keeping customer-facing English intact. */
    @PostMapping("/ai-translate")
    public ResponseEntity<AiDraftResponse> aiTranslate(@RequestBody AiTranslateRequest body) {
        if (isBlank(body.body())) return ResponseEntity.badRequest().build();
        try {
            return ResponseEntity.ok(new AiDraftResponse(aiDraft.translateToRussian(body.body())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static KbArticleDto toDto(KbArticle a) {
        return new KbArticleDto(a.getId(), a.getTitle(), a.getCategory(), a.getBody(), a.getBodyRu(),
                a.getVisibleRoles(), a.getSyncStatus().name(), a.getRagDocId(),
                a.getLastSyncedAt(), a.getLastSyncedBy(), a.getLastSyncError(),
                a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedAt());
    }

    public record KbArticleDto(Long id, String title, String category, String body, String bodyRu,
                               List<Role> visibleRoles, String syncStatus, Long ragDocId,
                               Instant lastSyncedAt, String lastSyncedBy, String lastSyncError,
                               String createdBy, Instant createdAt, Instant updatedAt) {}

    public record WriteRequest(String title, String category, String body, String bodyRu,
                               List<Role> visibleRoles) {}

    public record AiDraftRequest(String prompt, String currentBody) {}

    public record AiTranslateRequest(String body) {}

    public record AiDraftResponse(String markdown) {}
}
