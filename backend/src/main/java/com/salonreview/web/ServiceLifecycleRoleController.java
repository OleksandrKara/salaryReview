package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.ServiceLifecycleRole;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Owner-editable mapping from a Square service (variation) to the role it plays in this
 * business's customer service lifecycle (touch-up, color booster, etc. — see
 * {@link ServiceLifecycleRole}). Falls under the existing {@code /api/owner/**} matcher in
 * {@link com.salonreview.config.SecurityConfig}, no new security config needed. Resolves the
 * business via the session, so each business manages its own list independently — never a
 * hardcoded business or a Java-level catalog of which services qualify (see V123/V124's own doc:
 * found live 2026-08-24 baked into a migration seed, moved here so the owner can add/remove
 * entries — including the eventual other 8 real touch-up/color-booster variations — without a
 * deploy).
 *
 * <p>The owner never types a raw Square id: {@link #search} lets the frontend offer a
 * type-to-search picker over the business's own live catalog, so the id actually stored always
 * comes from Square itself (see {@code SquareClient#searchCatalogItemVariations}'s own doc for why
 * that matters — a hand-copied id is easy to get wrong in a way that silently never matches
 * anything, which is exactly what happened to this feature's own first seeded row, fixed in V125).
 */
@RestController
@RequestMapping("/api/owner/settings/service-lifecycle-roles")
public class ServiceLifecycleRoleController {

    private static final Logger log = LoggerFactory.getLogger(ServiceLifecycleRoleController.class);
    private static final int SEARCH_LIMIT = 20;

    private final ServiceLifecycleRoleRepository repository;
    private final SquareClientProvider squareClientProvider;
    private final CurrentBusinessContext currentBusinessContext;

    public ServiceLifecycleRoleController(ServiceLifecycleRoleRepository repository,
                                           SquareClientProvider squareClientProvider,
                                           CurrentBusinessContext currentBusinessContext) {
        this.repository = repository;
        this.squareClientProvider = squareClientProvider;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record ServiceLifecycleRoleDto(Long id, String role, String squareVariationId, String displayName,
                                          String createdBy) {
    }

    @GetMapping
    public List<ServiceLifecycleRoleDto> list() {
        Long businessId = currentBusinessContext.id();
        List<ServiceLifecycleRole> rows = repository.findAllByBusinessId(businessId);
        Map<String, String> names = resolveNames(businessId, rows);
        return rows.stream()
                .map(r -> new ServiceLifecycleRoleDto(r.getId(), r.getRole(), r.getSquareVariationId(),
                        names.getOrDefault(r.getSquareVariationId(), r.getSquareVariationId()), r.getCreatedBy()))
                .toList();
    }

    @GetMapping("/search")
    public List<SquareClient.CatalogSearchResult> search(@RequestParam String q) {
        try {
            return squareClientProvider.forBusiness(currentBusinessContext.id())
                    .searchCatalogItemVariations(q, SEARCH_LIMIT);
        } catch (RuntimeException e) {
            log.warn("Catalog search failed for business {} (returning no results): {}",
                    currentBusinessContext.id(), e.getMessage());
            return List.of();
        }
    }

    public record CreateRequest(String role, String squareVariationId) {
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest body, Principal principal) {
        String role = body.role() == null ? null : body.role().trim();
        String variationId = body.squareVariationId() == null ? null : body.squareVariationId().trim();
        if (role == null || role.isBlank() || variationId == null || variationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "role and squareVariationId are required"));
        }
        Long businessId = currentBusinessContext.id();
        try {
            ServiceLifecycleRole saved = repository.save(ServiceLifecycleRole.builder()
                    .businessId(businessId)
                    .role(role.toUpperCase(java.util.Locale.US))
                    .squareVariationId(variationId)
                    .createdBy(principal.getName())
                    .build());
            Map<String, String> names = resolveNames(businessId, List.of(saved));
            return ResponseEntity.ok(new ServiceLifecycleRoleDto(saved.getId(), saved.getRole(), saved.getSquareVariationId(),
                    names.getOrDefault(saved.getSquareVariationId(), saved.getSquareVariationId()), saved.getCreatedBy()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("error", "This service is already mapped to this role"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long businessId = currentBusinessContext.id();
        return repository.findById(id)
                .filter(r -> r.getBusinessId().equals(businessId))
                .map(r -> {
                    repository.deleteById(id);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, String> resolveNames(Long businessId, List<ServiceLifecycleRole> rows) {
        if (rows.isEmpty()) return Map.of();
        try {
            List<String> ids = rows.stream().map(ServiceLifecycleRole::getSquareVariationId).toList();
            return squareClientProvider.forBusiness(businessId).catalogNames(ids);
        } catch (RuntimeException e) {
            log.warn("Could not resolve service names for business {} (falling back to raw ids): {}", businessId, e.getMessage());
            return Map.of();
        }
    }
}
