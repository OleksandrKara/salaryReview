package com.salonreview.web;

import com.salonreview.domain.MerchantRule;
import com.salonreview.square.MerchantRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Merchant rule management (openspec change expense-import-reconciliation) — view, edit, or delete
 * any learned rule directly, not only reactively through a transaction correction. Falls under the
 * {@code /api/owner/**} OWNER-only catch-all in SecurityConfig — no dedicated matcher needed.
 */
@RestController
@RequestMapping("/api/owner/expenses/rules")
public class MerchantRuleController {

    private final MerchantRuleService service;

    public MerchantRuleController(MerchantRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<MerchantRuleDto> list() {
        return service.listAll().stream().map(MerchantRuleController::toDto).toList();
    }

    @PutMapping("/{id}")
    public ResponseEntity<MerchantRuleDto> update(@PathVariable Long id, @RequestBody UpdateRuleRequest req) {
        return service.update(id, req.category(), req.keyword(), req.amountMin(), req.amountMax(), req.active())
                .map(MerchantRuleController::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static MerchantRuleDto toDto(MerchantRule r) {
        return new MerchantRuleDto(r.getId(), r.getRuleType(), r.getNormalizedMerchant(), r.getKeyword(),
                r.getAmountMin(), r.getAmountMax(), r.getCategory(), r.isActive(), r.getCreatedBy(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getTimesApplied(), r.getLastAppliedAt());
    }

    public record MerchantRuleDto(Long id, String ruleType, String normalizedMerchant, String keyword,
                                   BigDecimal amountMin, BigDecimal amountMax, String category, boolean active,
                                   String createdBy, Instant createdAt, Instant updatedAt, int timesApplied,
                                   Instant lastAppliedAt) {}

    public record UpdateRuleRequest(String category, String keyword, BigDecimal amountMin, BigDecimal amountMax,
                                     Boolean active) {}
}
