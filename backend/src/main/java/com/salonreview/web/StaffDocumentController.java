package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Provider;
import com.salonreview.domain.StaffDocument;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.service.StaffDocumentService;
import com.salonreview.web.dto.StaffDocumentDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owner-only document storage for service providers and managers (contracts, licenses, NDAs,
 * etc.), each with a required expiration date. Falls under the {@code /api/owner/**} OWNER-only
 * catch-all in SecurityConfig — no dedicated matcher needed.
 */
@RestController
@RequestMapping("/api/owner/staff-documents")
public class StaffDocumentController {

    private final StaffDocumentService service;
    private final ProviderRepository providers;
    private final AppUserRepository users;

    public StaffDocumentController(StaffDocumentService service, ProviderRepository providers,
                                   AppUserRepository users) {
        this.service = service;
        this.providers = providers;
        this.users = users;
    }

    @GetMapping
    public List<StaffDocumentDto> list() {
        List<StaffDocument> docs = service.listAll();
        Set<Long> providerIds = docs.stream().map(StaffDocument::getProviderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> userIds = docs.stream().map(StaffDocument::getAppUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> providerNames = providers.findAllById(providerIds).stream()
                .collect(Collectors.toMap(Provider::getId, Provider::getDisplayName));
        Map<Long, String> userNames = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername));
        LocalDate today = LocalDate.now();
        return docs.stream().map(d -> toDto(d, providerNames, userNames, today)).toList();
    }

    @PostMapping
    public StaffDocumentDto create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) Long appUserId,
            @RequestParam String documentType,
            @RequestParam(required = false) String label,
            @RequestParam String expirationDate,
            @AuthenticationPrincipal AppUserPrincipal me) throws IOException {
        StaffDocument saved = service.create(providerId, appUserId, documentType, label,
                LocalDate.parse(expirationDate), file.getOriginalFilename(), file.getContentType(),
                file.getBytes(), me.getUsername());
        Map<Long, String> providerNames = providerId == null ? Map.of()
                : providers.findById(providerId).map(p -> Map.of(providerId, p.getDisplayName())).orElse(Map.of());
        Map<Long, String> userNames = appUserId == null ? Map.of()
                : users.findById(appUserId).map(u -> Map.of(appUserId, u.getUsername())).orElse(Map.of());
        return toDto(saved, providerNames, userNames, LocalDate.now());
    }

    /** Corrects an existing document in place — expiration date and/or type/label — without
     * deleting and re-uploading the same file just to fix a mistyped date or rename it. Every
     * field is optional; an absent one is left untouched (see StaffDocumentService#update). */
    @PatchMapping("/{id}")
    public ResponseEntity<StaffDocumentDto> update(
            @PathVariable Long id, @RequestBody UpdateStaffDocumentRequest req) {
        LocalDate expirationDate = req.expirationDate() == null ? null : LocalDate.parse(req.expirationDate());
        return service.update(id, expirationDate, req.documentType(), req.label())
                .map(d -> {
                    Map<Long, String> providerNames = d.getProviderId() == null ? Map.of()
                            : providers.findById(d.getProviderId()).map(p -> Map.of(d.getProviderId(), p.getDisplayName())).orElse(Map.of());
                    Map<Long, String> userNames = d.getAppUserId() == null ? Map.of()
                            : users.findById(d.getAppUserId()).map(u -> Map.of(d.getAppUserId(), u.getUsername())).orElse(Map.of());
                    return ResponseEntity.ok(toDto(d, providerNames, userNames, LocalDate.now()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return service.get(id)
                .map(d -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(d.getContentType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.getFileName() + "\"")
                        .body(d.getFileData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Package-private (not private) so StaffDocumentSelfController's own read-only endpoints can
     * build the exact same DTO shape for a person's own documents. */
    static StaffDocumentDto toDto(StaffDocument d, Map<Long, String> providerNames,
                                  Map<Long, String> userNames, LocalDate today) {
        boolean isProvider = d.getProviderId() != null;
        Long personId = isProvider ? d.getProviderId() : d.getAppUserId();
        String personName = isProvider
                ? providerNames.getOrDefault(personId, "Unknown provider")
                : userNames.getOrDefault(personId, "Unknown manager");
        return new StaffDocumentDto(
                d.getId(),
                isProvider ? "PROVIDER" : "MANAGER",
                personId,
                personName,
                d.getDocumentType(),
                d.getLabel(),
                d.getFileName(),
                d.getExpirationDate(),
                StaffDocumentService.statusFor(d.getExpirationDate(), today).name(),
                d.getCreatedBy(),
                d.getCreatedAt());
    }

    /** Every field optional — null means "leave as-is" (see StaffDocumentService#update).
     * expirationDate is yyyy-MM-dd, same wire format as the multipart create() form's own field. */
    public record UpdateStaffDocumentRequest(String expirationDate, String documentType, String label) {}
}
