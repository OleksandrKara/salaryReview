package com.salonreview.repo;

import com.salonreview.domain.MerchantRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantRuleRepository extends JpaRepository<MerchantRule, Long> {

    /** The single active plain-merchant-level rule for a merchant, if any (uniqueness enforced by
     * a partial DB index — see V65 — so this is safe to treat as at-most-one). */
    Optional<MerchantRule> findByNormalizedMerchantAndRuleTypeAndActiveTrue(String normalizedMerchant, String ruleType);

    /** Every active keyword/amount-range rule for a merchant — several may coexist (openspec
     * design.md D4/D9), unlike the plain-merchant tier. */
    List<MerchantRule> findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc(String normalizedMerchant, String ruleType);

    Optional<MerchantRule> findByFingerprintAndRuleTypeAndActiveTrue(String fingerprint, String ruleType);

    /** Every merchant with at least one active rule of any tier — the fuzzy-match fallback (§16)
     * only suggests against merchants that already have some learned rule. */
    List<MerchantRule> findAllByActiveTrue();

    /** Full roster for the Merchant Rules management screen. */
    List<MerchantRule> findAllByOrderByNormalizedMerchantAscRuleTypeAsc();

    /** Every active merchant-agnostic KEYWORD rule — not scoped by normalized_merchant, since this
     * tier exists specifically for descriptors whose normalized merchant is never stable (e.g. it
     * embeds a per-transaction reference number). */
    List<MerchantRule> findAllByRuleTypeAndActiveTrueOrderByIdAsc(String ruleType);

    boolean existsByCategory(String category);
}
