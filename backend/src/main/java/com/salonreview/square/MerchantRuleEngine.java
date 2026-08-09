package com.salonreview.square;

import com.salonreview.domain.MerchantRule;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.MerchantRuleRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The six-tier priority rule engine (openspec design.md §16, D4/D9, extended with a merchant-
 * agnostic keyword tier): Fingerprint → Keyword (merchant-agnostic) → Merchant+Keyword →
 * Merchant+AmountRange → plain Merchant → fuzzy similarity → manual. Every match records a
 * human-readable {@code match_reason} so no automatic decision is ever silent. Reference-number-
 * only descriptions (bare check/ACH/wire numbers) skip evaluation entirely.
 */
@Component
public class MerchantRuleEngine {

    /** Confidence at/above this auto-applies without review (design.md §16). */
    public static final BigDecimal AUTO_APPLY_THRESHOLD = new BigDecimal("0.75");

    private static final BigDecimal CONF_FINGERPRINT = new BigDecimal("0.99");
    private static final BigDecimal CONF_KEYWORD_ONLY = new BigDecimal("0.85");
    private static final BigDecimal CONF_KEYWORD = new BigDecimal("0.85");
    private static final BigDecimal CONF_AMOUNT_RANGE = new BigDecimal("0.75");
    private static final BigDecimal CONF_MERCHANT_CLEAN = new BigDecimal("0.90");
    private static final BigDecimal CONF_MERCHANT_AMBIGUOUS = new BigDecimal("0.60");
    private static final double FUZZY_MIN_SIMILARITY_D = 0.6d;

    private static final DateTimeFormatter RULE_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.US);

    private final MerchantRuleRepository rules;
    private final BankTransactionRepository transactions;

    public MerchantRuleEngine(MerchantRuleRepository rules, BankTransactionRepository transactions) {
        this.rules = rules;
        this.transactions = transactions;
    }

    /** @param autoApply true when {@code confidence >= AUTO_APPLY_THRESHOLD} and this tier is
     *                   trustworthy enough to apply without review; a plain-merchant match that's
     *                   only reached because niche rules exist for the same merchant, and every
     *                   fuzzy-similarity suggestion, is never auto-applied regardless of score
     *                   (design.md §16). {@code category} is null for a genuine Unknown. */
    public record MatchResult(String category, BigDecimal confidence, String matchReason,
                               Long matchedRuleId, boolean autoApply) {
        public static MatchResult unknown() {
            return new MatchResult(null, null, null, null, false);
        }
    }

    public MatchResult evaluate(CsvStatementParser.ParsedTransaction txn) {
        if (MerchantNormalizer.isReferenceNumberOnly(txn.rawDescription())) {
            return MatchResult.unknown();
        }

        Optional<MerchantRule> fingerprintRule =
                rules.findByFingerprintAndRuleTypeAndActiveTrue(txn.fingerprint(), MerchantRule.TYPE_FINGERPRINT);
        if (fingerprintRule.isPresent()) {
            MerchantRule r = fingerprintRule.get();
            return new MatchResult(r.getCategory(), CONF_FINGERPRINT,
                    "Matched because: identical fingerprint" + ruleProvenance(r), r.getId(), true);
        }

        List<MerchantRule> keywordOnlyRules =
                rules.findAllByRuleTypeAndActiveTrueOrderByIdAsc(MerchantRule.TYPE_KEYWORD);
        String descriptionUpper = txn.rawDescription().toUpperCase(Locale.US);
        for (MerchantRule r : keywordOnlyRules) {
            if (r.getKeyword() == null) continue;
            String[] required = r.getKeyword().split(MerchantRule.KEYWORD_DELIMITER);
            boolean allPresent = required.length > 0;
            for (String part : required) {
                if (!descriptionUpper.contains(part.toUpperCase(Locale.US))) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return new MatchResult(r.getCategory(), CONF_KEYWORD_ONLY,
                        "Matched because: description contains all of "
                                + String.join(", ", required) + ruleProvenance(r), r.getId(), true);
            }
        }

        String merchant = txn.normalizedMerchant();
        List<MerchantRule> keywordRules = rules.findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc(
                merchant, MerchantRule.TYPE_MERCHANT_KEYWORD);
        for (MerchantRule r : keywordRules) {
            if (r.getKeyword() != null && txn.rawDescription().toUpperCase(Locale.US).contains(r.getKeyword().toUpperCase(Locale.US))) {
                return new MatchResult(r.getCategory(), CONF_KEYWORD,
                        "Matched because: Normalized Merchant = " + merchant + " + description contains '"
                                + r.getKeyword() + "'" + ruleProvenance(r), r.getId(), true);
            }
        }

        List<MerchantRule> rangeRules = rules.findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc(
                merchant, MerchantRule.TYPE_MERCHANT_AMOUNT_RANGE);
        BigDecimal absAmount = txn.amount().abs();
        for (MerchantRule r : rangeRules) {
            if (r.getAmountMin() != null && r.getAmountMax() != null
                    && absAmount.compareTo(r.getAmountMin()) >= 0 && absAmount.compareTo(r.getAmountMax()) <= 0) {
                return new MatchResult(r.getCategory(), CONF_AMOUNT_RANGE,
                        "Matched because: Normalized Merchant = " + merchant + " + amount in range ["
                                + r.getAmountMin() + ", " + r.getAmountMax() + "]" + ruleProvenance(r), r.getId(), true);
            }
        }

        Optional<MerchantRule> plainRule =
                rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue(merchant, MerchantRule.TYPE_MERCHANT);
        if (plainRule.isPresent()) {
            MerchantRule r = plainRule.get();
            boolean hasNicheRules = !keywordRules.isEmpty() || !rangeRules.isEmpty();
            String reason = "Matched because: Normalized Merchant = " + merchant + ruleProvenance(r);
            if (hasNicheRules) {
                // a near-miss on a merchant with known nuance deserves a human glance, not a guess
                return new MatchResult(r.getCategory(), CONF_MERCHANT_AMBIGUOUS, reason, r.getId(), false);
            }
            return new MatchResult(r.getCategory(), CONF_MERCHANT_CLEAN, reason, r.getId(), true);
        }

        List<BankTransactionRepository.MerchantSimilarity> fuzzy =
                transactions.findClosestMerchantByTrigram(txn.merchantKey());
        if (!fuzzy.isEmpty() && fuzzy.get(0).getSim() != null && fuzzy.get(0).getSim() >= FUZZY_MIN_SIMILARITY_D) {
            BankTransactionRepository.MerchantSimilarity best = fuzzy.get(0);
            int pct = (int) Math.round(best.getSim() * 100);
            BigDecimal confidence = BigDecimal.valueOf(Math.min(best.getSim(), FUZZY_MIN_SIMILARITY_D))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return new MatchResult(null, confidence,
                    "Possible match: " + best.getMerchant() + " (" + pct + "% similar)", null, false);
        }

        return MatchResult.unknown();
    }

    private static String ruleProvenance(MerchantRule r) {
        String created = r.getCreatedAt() == null ? "" :
                " (rule created " + RULE_DATE.format(r.getCreatedAt().atZone(java.time.ZoneOffset.UTC)) +
                        ", applied " + r.getTimesApplied() + " times)";
        return created;
    }
}
