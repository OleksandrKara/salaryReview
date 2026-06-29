package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Sop;
import com.salonreview.sop.SopSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Owner-only endpoints to push SOPs into the assistant's RAG corpus. Lives under {@code /api/sops}
 * but is OWNER-gated in {@code SecurityConfig} (the GET has its own matcher; the POSTs fall to the
 * {@code /api/sops/**}=OWNER catch-all). Status mapping mirrors KB sync (409 on a concurrent bulk run).
 */
@RestController
@RequestMapping("/api/sops")
public class SopSyncController {

    private final SopSyncService sync;

    public SopSyncController(SopSyncService sync) {
        this.sync = sync;
    }

    @GetMapping("/rag-sync")
    public List<SopSyncDto> list() {
        return sync.list().stream().map(this::toDto).toList();
    }

    @PostMapping("/{id}/rag-sync")
    public ResponseEntity<SopSyncDto> syncOne(@PathVariable Long id,
                                              @AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return sync.syncOne(id, me.getUsername()).map(s -> ResponseEntity.ok(toDto(s)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (SopSyncService.SyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/rag-sync-all")
    public ResponseEntity<List<SopSyncDto>> syncAll(@AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return ResponseEntity.ok(sync.syncAll(me.getUsername()).stream().map(this::toDto).toList());
        } catch (SopSyncService.SyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    private SopSyncDto toDto(Sop s) {
        return new SopSyncDto(s.getId(), s.getTitle(), s.getCategory(),
                sync.effectiveStatus(s).name(), s.getLastSyncError(), s.getCurrentVersionId() != null);
    }

    /** {@code published} = has a live version to sync (a draft-only SOP can't be synced yet). */
    public record SopSyncDto(Long id, String title, String category, String syncStatus,
                             String lastSyncError, boolean published) {}
}
