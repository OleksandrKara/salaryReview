package com.salonreview.square;

import com.salonreview.domain.MerchantRule;
import com.salonreview.repo.MerchantRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CRUD for learned {@link MerchantRule}s, plus the "remember this for {merchant}" mutation path
 * triggered from a transaction review decision (openspec design.md D6/D9). Enforces at most one
 * active plain-merchant-level rule per merchant at the application layer too (the DB partial
 * unique index is the hard backstop) so a conflicting second categorization surfaces as an
 * explicit, actionable conflict rather than either a constraint-violation 500 or a silent
 * overwrite.
 */
@Service
public class MerchantRuleService {

    private final MerchantRuleRepository rules;

    public MerchantRuleService(MerchantRuleRepository rules) {
        this.rules = rules;
    }

    /** Bumps usage stats on an existing rule without changing its category — the "confirmed an
     * already-matched transaction without changes" reinforcement path (design.md §"Reconciliation
     * Workflow", D6). */
    @Transactional
    public MerchantRule reinforce(Long ruleId) {
        MerchantRule r = rules.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rule"));
        r.setTimesApplied(r.getTimesApplied() + 1);
        r.setLastAppliedAt(Instant.now());
        return rules.save(r);
    }

    /** Creates or updates the single active plain-merchant rule for a merchant. If one already
     * exists with a *different* category and {@code replaceExisting} is false, throws 409 so the
     * caller can show the before/after prompt and re-submit with {@code replaceExisting=true}
     * (design.md D9's Costco example) — never silently creates a second, conflicting rule. */
    @Transactional
    public MerchantRule rememberForMerchant(String normalizedMerchant, String category, Long sourceTransactionId,
                                             boolean replaceExisting, String createdBy) {
        Optional<MerchantRule> existing =
                rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue(normalizedMerchant, MerchantRule.TYPE_MERCHANT);
        if (existing.isPresent()) {
            MerchantRule r = existing.get();
            if (r.getCategory().equals(category)) {
                return reinforce(r.getId());
            }
            if (!replaceExisting) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Existing rule for " + normalizedMerchant + " is '" + r.getCategory() + "'; new category is '"
                                + category + "'. Resubmit with replaceExisting=true to replace it, or use a "
                                + "keyword/amount-range rule instead to let both coexist.");
            }
            r.setCategory(category);
            r.setSourceTransactionId(sourceTransactionId);
            return rules.save(r);
        }
        return rules.save(MerchantRule.builder()
                .ruleType(MerchantRule.TYPE_MERCHANT)
                .normalizedMerchant(normalizedMerchant)
                .category(category)
                .active(true)
                .createdBy(createdBy)
                .sourceTransactionId(sourceTransactionId)
                .build());
    }

    /** Creates a new merchant-agnostic {@code KEYWORD} rule requiring every one of {@code keywords}
     * to appear (case-insensitive) in a transaction's raw description — no merchant scoping, no
     * conflict-replace logic, since (like MERCHANT_KEYWORD/MERCHANT_AMOUNT_RANGE) several of these
     * rules may coexist rather than being mutually exclusive per merchant. Blank/duplicate entries
     * are dropped and the rest trimmed before saving. */
    @Transactional
    public MerchantRule rememberKeywords(List<String> keywords, String category, Long sourceTransactionId,
                                          String createdBy) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : keywords) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) distinct.add(trimmed);
        }
        if (distinct.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one non-blank keyword is required");
        }
        String joined = String.join(MerchantRule.KEYWORD_DELIMITER, distinct);
        return rules.save(MerchantRule.builder()
                .ruleType(MerchantRule.TYPE_KEYWORD)
                .keyword(joined)
                .category(category)
                .active(true)
                .createdBy(createdBy)
                .sourceTransactionId(sourceTransactionId)
                .build());
    }

    public List<MerchantRule> listAll() {
        return rules.findAllByOrderByNormalizedMerchantAscRuleTypeAsc();
    }

    @Transactional
    public Optional<MerchantRule> update(Long id, String category, String keyword, BigDecimal amountMin,
                                          BigDecimal amountMax, Boolean active) {
        return rules.findById(id).map(r -> {
            if (category != null) r.setCategory(category);
            if (keyword != null) r.setKeyword(keyword);
            if (amountMin != null) r.setAmountMin(amountMin);
            if (amountMax != null) r.setAmountMax(amountMax);
            if (active != null) r.setActive(active);
            return rules.save(r);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!rules.existsById(id)) return false;
        rules.deleteById(id);
        return true;
    }
}
