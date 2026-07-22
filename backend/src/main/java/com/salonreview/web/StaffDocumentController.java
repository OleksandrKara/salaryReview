package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Provider;
import com.salonreview.domain.StaffDocument;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.service.StaffDocumentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
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

    private static StaffDocumentDto toDto(StaffDocument d, Map<Long, String> providerNames,
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

    public record StaffDocumentDto(
            Long id,
            /** "PROVIDER" or "MANAGER". */
            String personType,
            Long personId,
            String personName,
            String documentType,
            String label,
            String fileName,
            LocalDate expirationDate,
            /** "OK" | "EXPIRING_SOON" | "EXPIRED" — see StaffDocumentService#statusFor. */
            String status,
            String createdBy,
            Instant createdAt) {}
}
