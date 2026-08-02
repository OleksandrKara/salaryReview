package com.salonreview.square;

import com.salonreview.domain.MerchantRule;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.MerchantRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** One scenario per priority tier in isolation, plus precedence/conflict scenarios (openspec
 * design.md §16, D4/D9, tasks.md 6.3). */
class MerchantRuleEngineTest {

    private MerchantRuleRepository rules;
    private BankTransactionRepository transactions;
    private MerchantRuleEngine engine;

    @BeforeEach
    void setUp() {
        rules = mock(MerchantRuleRepository.class);
        transactions = mock(BankTransactionRepository.class);
        engine = new MerchantRuleEngine(rules, transactions);

        when(rules.findByFingerprintAndRuleTypeAndActiveTrue(anyString(), anyString())).thenReturn(Optional.empty());
        when(rules.findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc(anyString(), anyString())).thenReturn(List.of());
        when(rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue(anyString(), anyString())).thenReturn(Optional.empty());
        when(transactions.findClosestMerchantByTrigram(anyString())).thenReturn(List.of());
    }

    private static CsvStatementParser.ParsedTransaction txn(String merchant, String description, BigDecimal amount, String fingerprint) {
        return new CsvStatementParser.ParsedTransaction(LocalDate.of(2026, 8, 14), description,
                amount, merchant, merchant, fingerprint, 0);
    }

    private static MerchantRule rule(String type, String merchant, String category) {
        return MerchantRule.builder().id(1L).ruleType(type).normalizedMerchant(merchant).category(category)
                .active(true).createdAt(Instant.now()).timesApplied(0).build();
    }

    @Test
    @DisplayName("Fingerprint tier matches with confidence 0.99")
    void fingerprintTierMatches() {
        when(rules.findByFingerprintAndRuleTypeAndActiveTrue("fp1", MerchantRule.TYPE_FINGERPRINT))
                .thenReturn(Optional.of(rule(MerchantRule.TYPE_FINGERPRINT, "NETFLIX", "OTHER")));

        var result = engine.evaluate(txn("NETFLIX", "NETFLIX.COM", new BigDecimal("-9.99"), "fp1"));

        assertThat(result.category()).isEqualTo("OTHER");
        assertThat(result.confidence()).isEqualByComparingTo("0.99");
        assertThat(result.autoApply()).isTrue();
    }

    @Test
    @DisplayName("Keyword rule takes precedence over plain merchant rule for a matching description")
    void keywordBeatsPlainMerchant() {
        when(rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue("COSTCO", MerchantRule.TYPE_MERCHANT))
                .thenReturn(Optional.of(rule(MerchantRule.TYPE_MERCHANT, "COSTCO", "OTHER")));
        MerchantRule keywordRule = rule(MerchantRule.TYPE_MERCHANT_KEYWORD, "COSTCO", "UTILITIES");
        keywordRule.setKeyword("GAS");
        when(rules.findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc("COSTCO", MerchantRule.TYPE_MERCHANT_KEYWORD))
                .thenReturn(List.of(keywordRule));

        var result = engine.evaluate(txn("COSTCO", "COSTCO GAS #123", new BigDecimal("-40.00"), "fp2"));

        assertThat(result.category()).isEqualTo("UTILITIES");
        assertThat(result.confidence()).isEqualByComparingTo("0.85");
        assertThat(result.autoApply()).isTrue();
    }

    @Test
    @DisplayName("Amount-range rule takes precedence when the amount is in range")
    void amountRangeBeatsPlainMerchantWhenInRange() {
        when(rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue("COSTCO", MerchantRule.TYPE_MERCHANT))
                .thenReturn(Optional.of(rule(MerchantRule.TYPE_MERCHANT, "COSTCO", "OTHER")));
        MerchantRule rangeRule = rule(MerchantRule.TYPE_MERCHANT_AMOUNT_RANGE, "COSTCO", "MATERIALS");
        rangeRule.setAmountMin(new BigDecimal("100.00"));
        rangeRule.setAmountMax(new BigDecimal("500.00"));
        when(rules.findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc("COSTCO", MerchantRule.TYPE_MERCHANT_AMOUNT_RANGE))
                .thenReturn(List.of(rangeRule));

        var result = engine.evaluate(txn("COSTCO", "COSTCO WHSE", new BigDecimal("-200.00"), "fp3"));

        assertThat(result.category()).isEqualTo("MATERIALS");
        assertThat(result.confidence()).isEqualByComparingTo("0.75");
        assertThat(result.autoApply()).isTrue();
    }

    @Test
    @DisplayName("Falls through to plain merchant when amount is out of range")
    void fallsThroughToPlainMerchantWhenOutOfRange() {
        when(rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue("COSTCO", MerchantRule.TYPE_MERCHANT))
                .thenReturn(Optional.of(rule(MerchantRule.TYPE_MERCHANT, "COSTCO", "OTHER")));
        MerchantRule rangeRule = rule(MerchantRule.TYPE_MERCHANT_AMOUNT_RANGE, "COSTCO", "MATERIALS");
        rangeRule.setAmountMin(new BigDecimal("100.00"));
        rangeRule.setAmountMax(new BigDecimal("500.00"));
        when(rules.findAllByNormalizedMerchantAndRuleTypeAndActiveTrueOrderByIdAsc("COSTCO", MerchantRule.TYPE_MERCHANT_AMOUNT_RANGE))
                .thenReturn(List.of(rangeRule));

        var result = engine.evaluate(txn("COSTCO", "COSTCO WHSE", new BigDecimal("-40.00"), "fp4"));

        // out of range -> no niche rule matched, but a niche rule DOES exist for this merchant,
        // so plain-merchant is only an ambiguous (0.60, review) suggestion, not auto-applied
        assertThat(result.category()).isEqualTo("OTHER");
        assertThat(result.confidence()).isEqualByComparingTo("0.60");
        assertThat(result.autoApply()).isFalse();
    }

    @Test
    @DisplayName("A clean plain-merchant match (no niche rules at all) auto-applies at 0.90")
    void cleanPlainMerchantAutoApplies() {
        when(rules.findByNormalizedMerchantAndRuleTypeAndActiveTrue("COSTCO", MerchantRule.TYPE_MERCHANT))
                .thenReturn(Optional.of(rule(MerchantRule.TYPE_MERCHANT, "COSTCO", "MATERIALS")));

        var result = engine.evaluate(txn("COSTCO", "COSTCO WHSE", new BigDecimal("-40.00"), "fp5"));

        assertThat(result.category()).isEqualTo("MATERIALS");
        assertThat(result.confidence()).isEqualByComparingTo("0.90");
        assertThat(result.autoApply()).isTrue();
    }

    @Test
    @DisplayName("Fuzzy tier never exceeds 0.75 confidence and is never auto-applied")
    void fuzzyTierNeverExceedsThresholdAndNeverAutoApplies() {
        BankTransactionRepository.MerchantSimilarity sim = mock(BankTransactionRepository.MerchantSimilarity.class);
        when(sim.getMerchant()).thenReturn("COSTCO");
        when(sim.getSim()).thenReturn(0.95d); // even a near-perfect fuzzy score is capped
        when(transactions.findClosestMerchantByTrigram("COSTC0")).thenReturn(List.of(sim));

        var result = engine.evaluate(txn("COSTC0", "COSTC0 WHSE", new BigDecimal("-40.00"), "fp6"));

        assertThat(result.confidence()).isLessThanOrEqualTo(new BigDecimal("0.60"));
        assertThat(result.autoApply()).isFalse();
        assertThat(result.category()).isNull(); // fuzzy is a suggestion, not a category assignment
    }

    @Test
    @DisplayName("A reference-number-only description skips straight to manual review")
    void referenceNumberOnlySkipsEvaluation() {
        var result = engine.evaluate(txn("CHECK1042", "CHECK #1042", new BigDecimal("-650.00"), "fp7"));

        assertThat(result.category()).isNull();
        assertThat(result.confidence()).isNull();
        verifyNoInteractions(transactions);
    }

    @Test
    @DisplayName("No match at all is Unknown")
    void noMatchIsUnknown() {
        var result = engine.evaluate(txn("MYSTERYSHOP", "MYSTERY SHOP LLC", new BigDecimal("-15.00"), "fp8"));

        assertThat(result.category()).isNull();
        assertThat(result.confidence()).isNull();
        assertThat(result.autoApply()).isFalse();
    }
}
