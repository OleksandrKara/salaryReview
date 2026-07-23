package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Role;
import com.salonreview.domain.StaffDocument;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.service.StaffDocumentService;
import com.salonreview.web.dto.StaffDocumentDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A provider/manager's own read-only view of their staff documents (contracts, licenses, NDAs,
 * etc.) — list and download only, never create/edit/delete (that stays owner-only, see
 * {@link StaffDocumentController}). The person is always taken from the authenticated principal,
 * never a request parameter, so nobody can list or download another person's documents; download
 * additionally re-checks ownership on the fetched row itself (see {@link #belongsToMe}) since the
 * document id in the URL is otherwise just a guessable integer.
 */
@RestController
@RequestMapping("/api/staff-documents/me")
public class StaffDocumentSelfController {

    private final StaffDocumentService service;
    private final ProviderRepository providers;
    private final AppUserRepository users;

    public StaffDocumentSelfController(StaffDocumentService service, ProviderRepository providers,
                                        AppUserRepository users) {
        this.service = service;
        this.providers = providers;
        this.users = users;
    }

    @GetMapping
    public List<StaffDocumentDto> list(@AuthenticationPrincipal AppUserPrincipal me) {
        List<StaffDocument> docs = myDocuments(me);
        Map<Long, String> providerNames = me.getRole() == Role.PROVIDER
                ? providers.findById(me.getProviderId()).map(p -> Map.of(me.getProviderId(), p.getDisplayName())).orElse(Map.of())
                : Map.of();
        Map<Long, String> userNames = me.getRole() == Role.MANAGER
                ? users.findById(me.getUserId()).map(u -> Map.of(me.getUserId(), u.getUsername())).orElse(Map.of())
                : Map.of();
        LocalDate today = LocalDate.now();
        return docs.stream().map(d -> StaffDocumentController.toDto(d, providerNames, userNames, today)).toList();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal me) {
        StaffDocument d = service.get(id).filter(doc -> belongsToMe(doc, me)).orElse(null);
        // 404 (not 403) whether the id doesn't exist at all or just isn't this person's — doesn't
        // confirm or deny that some other person's document with this id exists.
        if (d == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.getFileName() + "\"")
                .body(d.getFileData());
    }

    private List<StaffDocument> myDocuments(AppUserPrincipal me) {
        if (me.getRole() == Role.PROVIDER) {
            return service.listForProvider(requireProviderId(me));
        }
        return service.listForManager(me.getUserId());
    }

    private static boolean belongsToMe(StaffDocument d, AppUserPrincipal me) {
        if (me.getRole() == Role.PROVIDER) {
            return me.getProviderId() != null && me.getProviderId().equals(d.getProviderId());
        }
        return me.getUserId() != null && me.getUserId().equals(d.getAppUserId());
    }

    private static Long requireProviderId(AppUserPrincipal me) {
        Long providerId = me.getProviderId();
        if (providerId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not linked to a provider");
        }
        return providerId;
    }
}
