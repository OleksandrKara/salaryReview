package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.KbRequest;
import com.salonreview.domain.KbRequestStatus;
import com.salonreview.domain.KbRequestTarget;
import com.salonreview.rag.KbRequestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Knowledge-gap requests. Creating one is allowed for any assistant user (the {@code /api/rag/**}
 * matcher = OWNER+MANAGER); listing, triaging and deleting sit under {@code /api/rag/admin/**} so
 * they're OWNER-only. Gated on {@code rag.enabled} like the rest of the assistant.
 */
@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class KbRequestController {

    private final KbRequestService requests;

    public KbRequestController(KbRequestService requests) {
        this.requests = requests;
    }

    /** File a request (owner/manager) — typically when the assistant returned no answer. */
    @PostMapping("/requests")
    public ResponseEntity<KbRequestDto> create(@RequestBody CreateRequest body,
                                               @AuthenticationPrincipal AppUserPrincipal me) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        KbRequest r = requests.create(body.question(), body.note(), parseTarget(body.target()), me.getUsername());
        return ResponseEntity.ok(toDto(r));
    }

    /** Owner list of all requests (newest first). */
    @GetMapping("/admin/requests")
    public List<KbRequestDto> list() {
        return requests.list().stream().map(KbRequestController::toDto).toList();
    }

    /** Count of OPEN (not yet triaged) requests — powers the nav badge, cheaper than fetching the list. */
    @GetMapping("/admin/requests/open-count")
    public OpenCountDto openCount() {
        return new OpenCountDto(requests.openCount());
    }

    /** Owner triage: resolve / dismiss / reopen (OPEN). */
    @PostMapping("/admin/requests/{id}/status")
    public ResponseEntity<KbRequestDto> setStatus(@PathVariable Long id, @RequestBody StatusRequest body,
                                                  @AuthenticationPrincipal AppUserPrincipal me) {
        KbRequestStatus status = parseStatus(body == null ? null : body.status());
        if (status == null) return ResponseEntity.badRequest().build();
        return requests.setStatus(id, status, me.getUsername())
                .map(r -> ResponseEntity.ok(toDto(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/requests/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return requests.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static KbRequestTarget parseTarget(String raw) {
        if (raw == null) return KbRequestTarget.UNSURE;
        try {
            return KbRequestTarget.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return KbRequestTarget.UNSURE;
        }
    }

    private static KbRequestStatus parseStatus(String raw) {
        if (raw == null) return null;
        try {
            return KbRequestStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static KbRequestDto toDto(KbRequest r) {
        return new KbRequestDto(r.getId(), r.getQuestion(), r.getNote(), r.getTarget().name(),
                r.getStatus().name(), r.getRequestedBy(), r.getCreatedAt(), r.getResolvedAt(), r.getResolvedBy());
    }

    public record KbRequestDto(Long id, String question, String note, String target, String status,
                               String requestedBy, Instant createdAt, Instant resolvedAt, String resolvedBy) {}

    public record CreateRequest(String question, String note, String target) {}

    public record StatusRequest(String status) {}

    public record OpenCountDto(long count) {}
}
