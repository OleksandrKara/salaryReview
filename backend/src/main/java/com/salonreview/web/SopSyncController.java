package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Sop;
import com.salonreview.sop.SopExportService;
import com.salonreview.sop.SopSyncService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
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
    private final SopExportService export;
    private final CurrentBusinessContext currentBusinessContext;

    public SopSyncController(SopSyncService sync, SopExportService export, CurrentBusinessContext currentBusinessContext) {
        this.sync = sync;
        this.export = export;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping("/rag-sync")
    public List<SopSyncDto> list() {
        return sync.list(currentBusinessContext.id()).stream().map(this::toDto).toList();
    }

    /** One SOP's current published version as a standalone .md file. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadOne(@PathVariable Long id) {
        return export.exportOne(id, currentBusinessContext.id())
                .map(e -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                        .body(e.markdown().getBytes(StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Every ACTIVE, published SOP zipped into one archive of .md files. */
    @GetMapping("/download-all")
    public ResponseEntity<byte[]> downloadAll() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sops.zip\"")
                .body(export.exportAllAsZip(currentBusinessContext.id()));
    }

    @PostMapping("/{id}/rag-sync")
    public ResponseEntity<SopSyncDto> syncOne(@PathVariable Long id,
                                              @AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return sync.syncOne(id, me.getUsername(), currentBusinessContext.id()).map(s -> ResponseEntity.ok(toDto(s)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (SopSyncService.SyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/rag-sync-all")
    public ResponseEntity<List<SopSyncDto>> syncAll(@AuthenticationPrincipal AppUserPrincipal me) {
        try {
            return ResponseEntity.ok(sync.syncAll(me.getUsername(), currentBusinessContext.id()).stream()
                    .map(this::toDto).toList());
        } catch (SopSyncService.SyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    private SopSyncDto toDto(Sop s) {
        return new SopSyncDto(s.getId(), s.getTitle(), s.getCategory(),
                sync.effectiveStatus(s).name(), s.getLastSyncError(),
                s.getCurrentVersionId() != null, sync.currentVersionHasTranslation(s));
    }

    /**
     * {@code published} = has a live version to sync (a draft-only SOP can't be synced yet).
     * {@code hasTranslation} = the current version has a Russian body (drives the EN/RU chip).
     */
    public record SopSyncDto(Long id, String title, String category, String syncStatus,
                             String lastSyncError, boolean published, boolean hasTranslation) {}
}
