package com.salonreview.web;

import com.salonreview.domain.TierGrant;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.TierGrantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Manage manual 50/50 tier grants (owner/manager action). A grant forces the tier for a provider in
 * a given month; deleting it reverts to the automatic service-count decision. The settlement preview
 * reads these automatically.
 */
@RestController
@RequestMapping("/api/settlements/grants")
public class TierGrantController {

    private final TierGrantRepository grants;
    private final ProviderRepository providers;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public TierGrantController(TierGrantRepository grants, ProviderRepository providers,
                               com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.grants = grants;
        this.providers = providers;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public List<TierGrant> list(@RequestParam int year, @RequestParam int month) {
        return grants.findByBusinessIdAndYearAndMonth(currentBusinessContext.id(), year, month);
    }

    /** Grant the tier (idempotent — re-granting returns the existing row).
     *
     * @throws ResponseStatusException 404 if {@code providerId} isn't a provider of the current
     * business — found live 2026-08-19 (security-review pass): a bare {@code providerId} param
     * reached {@code TierGrantRepository}'s unscoped {@code findByProviderIdAndYearAndMonth}/
     * {@code deleteByProviderIdAndYearAndMonth} with no ownership check, letting any business force
     * or revoke another business's provider's 50/50 tier for a real month. */
    @PostMapping
    @Transactional
    public ResponseEntity<TierGrant> grant(@RequestParam Long providerId,
                                           @RequestParam int year, @RequestParam int month) {
        requireOwnProvider(providerId);
        TierGrant existing = grants.findByProviderIdAndYearAndMonth(providerId, year, month).orElse(null);
        if (existing != null) return ResponseEntity.ok(existing);
        TierGrant saved = grants.save(TierGrant.builder()
                .providerId(providerId).year(year).month(month).build());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> revoke(@RequestParam Long providerId,
                                       @RequestParam int year, @RequestParam int month) {
        requireOwnProvider(providerId);
        grants.deleteByProviderIdAndYearAndMonth(providerId, year, month);
        return ResponseEntity.noContent().build();
    }

    private void requireOwnProvider(Long providerId) {
        if (!providers.existsByIdAndBusinessId(providerId, currentBusinessContext.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such provider");
        }
    }
}
