package com.salonreview.web;

import com.salonreview.domain.TierGrant;
import com.salonreview.repo.TierGrantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public TierGrantController(TierGrantRepository grants,
                               com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.grants = grants;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public List<TierGrant> list(@RequestParam int year, @RequestParam int month) {
        return grants.findByBusinessIdAndYearAndMonth(currentBusinessContext.id(), year, month);
    }

    /** Grant the tier (idempotent — re-granting returns the existing row). */
    @PostMapping
    @Transactional
    public ResponseEntity<TierGrant> grant(@RequestParam Long providerId,
                                           @RequestParam int year, @RequestParam int month) {
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
        grants.deleteByProviderIdAndYearAndMonth(providerId, year, month);
        return ResponseEntity.noContent().build();
    }
}
