package com.salonreview.square;

import com.salonreview.domain.BankStatementImport;
import com.salonreview.domain.BankTransaction;
import com.salonreview.domain.ExpenseEntry;
import com.salonreview.repo.BankStatementImportRepository;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.MerchantRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrates the whole statement-import lifecycle (openspec design.md D3/D7/D10): parse + fingerprint
 * + auto-categorize on upload, write real {@code expense_entries} rows on completion, and cleanly
 * undo just that financial side effect on revert.
 */
@Service
public class ExpenseImportService {

    private final BankStatementImportRepository imports;
    private final BankTransactionRepository transactions;
    private final MerchantRuleRepository merchantRules;
    private final CsvStatementParser parser;
    private final MerchantRuleEngine ruleEngine;
    private final MerchantRuleService merchantRuleService;
    private final PayrollDisbursementDetector payrollDetector;
    private final ExpenseService expenseService;

    public ExpenseImportService(BankStatementImportRepository imports, BankTransactionRepository transactions,
                                 MerchantRuleRepository merchantRules, CsvStatementParser parser,
                                 MerchantRuleEngine ruleEngine, MerchantRuleService merchantRuleService,
                                 PayrollDisbursementDetector payrollDetector, ExpenseService expenseService) {
        this.imports = imports;
        this.transactions = transactions;
        this.merchantRules = merchantRules;
        this.parser = parser;
        this.ruleEngine = ruleEngine;
        this.merchantRuleService = merchantRuleService;
        this.payrollDetector = payrollDetector;
        this.expenseService = expenseService;
    }

    @Transactional
    public BankStatementImport importStatement(MultipartFile file, String uploadedBy) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file", e);
        }
        List<CsvStatementParser.ParsedTransaction> parsed = parser.parse(bytes);

        BankStatementImport imp = imports.save(BankStatementImport.builder()
                .originalFilename(file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename())
                .rawFile(bytes)
                .rowCount(parsed.size())
                .statementPeriodStart(parsed.stream().map(CsvStatementParser.ParsedTransaction::date)
                        .min(Comparator.naturalOrder()).orElse(null))
                .statementPeriodEnd(parsed.stream().map(CsvStatementParser.ParsedTransaction::date)
                        .max(Comparator.naturalOrder()).orElse(null))
                .status(BankStatementImport.STATUS_AWAITING_REVIEW)
                .uploadedBy(uploadedBy)
                .build());

        for (CsvStatementParser.ParsedTransaction p : parsed) {
            BankTransaction.BankTransactionBuilder b = BankTransaction.builder()
                    .importId(imp.getId())
                    .transactionDate(p.date())
                    .rawDescription(p.rawDescription())
                    .normalizedMerchant(p.normalizedMerchant())
                    .merchantKey(p.merchantKey())
                    .amount(p.amount())
                    .fingerprint(p.fingerprint())
                    .occurrenceIndex(p.occurrenceIndex());

            Optional<BankTransaction> duplicate = transactions.findNonRevertedDuplicate(p.fingerprint(), p.occurrenceIndex());
            if (duplicate.isPresent()) {
                b.status(BankTransaction.STATUS_DUPLICATE).duplicateOfTransactionId(duplicate.get().getId());
            } else {
                applyCategorization(b, p);
            }
            transactions.save(b.build());
        }
        return imp;
    }

    private void applyCategorization(BankTransaction.BankTransactionBuilder b, CsvStatementParser.ParsedTransaction p) {
        if (p.amount().signum() > 0) {
            // Money in is never an expense by definition — a deterministic override that bypasses
            // the rule engine entirely, rather than a fuzzy suggestion an owner has to confirm.
            b.status(BankTransaction.STATUS_EXCLUDED).excludedReason(BankTransaction.EXCLUDE_DEPOSIT)
                    .confidence(new BigDecimal("1.00"))
                    .matchReason("Matched because: positive amount (money in) is never an expense");
            return;
        }
        MerchantRuleEngine.MatchResult result = ruleEngine.evaluate(p);
        if (result.category() == null) {
            result = payrollDetector.suggest(p.rawDescription())
                    .or(() -> suggestOtherExcludeReason(p.rawDescription()))
                    .orElse(result);
        }
        if (result.category() == null) {
            b.status(BankTransaction.STATUS_NEEDS_REVIEW);
            return;
        }

        boolean isExclude = result.category().startsWith("EXCLUDE_");
        boolean autoApply = result.autoApply() && result.confidence() != null
                && result.confidence().compareTo(MerchantRuleEngine.AUTO_APPLY_THRESHOLD) >= 0;
        b.matchReason(result.matchReason()).confidence(result.confidence()).matchedRuleId(result.matchedRuleId());

        if (autoApply) {
            if (isExclude) {
                b.status(BankTransaction.STATUS_EXCLUDED).excludedReason(excludeReasonOf(result.category()));
            } else {
                b.status(BankTransaction.STATUS_AUTO_MATCHED).category(result.category());
            }
        } else {
            // A suggestion only — still surfaced to the owner (design.md §16/D9), but requires
            // explicit confirmation before it can move money out of Net Revenue or be finalized.
            b.status(BankTransaction.STATUS_NEEDS_REVIEW);
            if (isExclude) {
                b.excludedReason(excludeReasonOf(result.category()));
            } else {
                b.category(result.category());
            }
        }
    }

    /** Credit-card-payment and cash-withdrawal exclude suggestions (design.md Edge Cases [20]) —
     * simple description-pattern heuristics, same suggestion-only contract as
     * {@link PayrollDisbursementDetector}. */
    private static Optional<MerchantRuleEngine.MatchResult> suggestOtherExcludeReason(String rawDescription) {
        String upper = rawDescription.toUpperCase(Locale.US);
        if (upper.contains("ATM") && upper.contains("WITHDRAW")) {
            return Optional.of(new MerchantRuleEngine.MatchResult(
                    "EXCLUDE_" + BankTransaction.EXCLUDE_CASH_WITHDRAWAL, new BigDecimal("0.60"),
                    "Suggested because: description matches an ATM withdrawal pattern", null, false));
        }
        boolean looksLikeCardPayment = upper.contains("PAYMENT") && (upper.contains("CHASE") || upper.contains("AMEX")
                || upper.contains("AMERICAN EXPRESS") || upper.contains("CAPITAL ONE") || upper.contains("DISCOVER")
                || upper.contains("CITI") || upper.contains("VISA") || upper.contains("MASTERCARD"));
        if (looksLikeCardPayment) {
            return Optional.of(new MerchantRuleEngine.MatchResult(
                    "EXCLUDE_" + BankTransaction.EXCLUDE_CREDIT_CARD_PAYMENT, new BigDecimal("0.60"),
                    "Suggested because: description matches a credit card payment pattern", null, false));
        }
        return Optional.empty();
    }

    private static String excludeReasonOf(String pseudoCategory) {
        return pseudoCategory.substring("EXCLUDE_".length());
    }

    /** Whether [from, to] overlaps at least one COMPLETED statement import — the "statement-
     * covered month" test that makes {@code OwnerOverviewService} source that period's expenses
     * exclusively from the reconciliation instead of the generic/manual paths (design.md D11). */
    public boolean isPeriodStatementCovered(LocalDate from, LocalDate to) {
        return imports.existsCompletedOverlapping(from, to);
    }

    /** The {@code expense_entries} ids a completed statement reconciliation created for [from, to]
     * — what {@code ExpenseService}'s statement-derived totals sum over (design.md D11). */
    public List<Long> linkedExpenseEntryIds(LocalDate from, LocalDate to) {
        return transactions.findLinkedExpenseEntryIdsForCompletedImportsOverlapping(from, to);
    }

    public List<BankStatementImport> listImports() {
        return imports.findAllByOrderByUploadedAtDesc();
    }

    public Optional<BankStatementImport> getImport(Long id) {
        return imports.findById(id);
    }

    public List<BankTransaction> getTransactions(Long importId) {
        return transactions.findByImportIdOrderByTransactionDateAsc(importId);
    }

    /** Sets/changes a single transaction's category or exclude reason. Reinforces the already-
     * matched rule (times_applied/last_applied_at) if the owner confirmed it unchanged; otherwise
     * creates/replaces the merchant's plain rule when {@code rememberForMerchant} is set
     * (design.md D6/D9). */
    @Transactional
    public BankTransaction reviewTransaction(Long transactionId, String category, String excludeReason,
                                              boolean rememberForMerchant, boolean replaceExisting,
                                              List<String> rememberKeywords, String reviewedBy) {
        BankTransaction txn = transactions.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such transaction"));

        String resolvedCategory = excludeReason != null ? "EXCLUDE_" + excludeReason : category;
        boolean reinforced = false;
        if (txn.getMatchedRuleId() != null) {
            reinforced = merchantRules.findById(txn.getMatchedRuleId())
                    .filter(r -> r.getCategory().equals(resolvedCategory))
                    .map(r -> {
                        merchantRuleService.reinforce(r.getId());
                        return true;
                    }).orElse(false);
        }
        if (!reinforced && rememberKeywords != null && !rememberKeywords.isEmpty()) {
            var rule = merchantRuleService.rememberKeywords(rememberKeywords, resolvedCategory, txn.getId(), reviewedBy);
            txn.setMatchedRuleId(rule.getId());
        } else if (!reinforced && rememberForMerchant) {
            var rule = merchantRuleService.rememberForMerchant(
                    txn.getNormalizedMerchant(), resolvedCategory, txn.getId(), replaceExisting, reviewedBy);
            txn.setMatchedRuleId(rule.getId());
        }

        txn.setCategory(excludeReason != null ? null : category);
        txn.setExcludedReason(excludeReason);
        txn.setStatus(excludeReason != null ? BankTransaction.STATUS_EXCLUDED : BankTransaction.STATUS_REVIEWED);
        txn.setReviewedBy(reviewedBy);
        txn.setReviewedAt(Instant.now());
        return transactions.save(txn);
    }

    @Transactional
    public List<BankTransaction> bulkReviewTransactions(List<Long> transactionIds, String category, String excludeReason,
                                                         boolean rememberForMerchant, boolean replaceExisting,
                                                         List<String> rememberKeywords, String reviewedBy) {
        return transactionIds.stream()
                .map(id -> reviewTransaction(id, category, excludeReason, rememberForMerchant, replaceExisting,
                        rememberKeywords, reviewedBy))
                .toList();
    }

    /** Creates an ordinary {@code expense_entries} row for every categorized, non-excluded,
     * non-duplicate transaction (design.md D3) and marks the import COMPLETED. */
    @Transactional
    public BankStatementImport completeReconciliation(Long importId, String completedBy) {
        BankStatementImport imp = imports.findById(importId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such import"));
        if (BankStatementImport.STATUS_COMPLETED.equals(imp.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Import is already completed");
        }
        for (BankTransaction t : transactions.findByImportIdOrderByTransactionDateAsc(importId)) {
            boolean eligible = (BankTransaction.STATUS_AUTO_MATCHED.equals(t.getStatus())
                    || BankTransaction.STATUS_REVIEWED.equals(t.getStatus()))
                    && t.getCategory() != null;
            if (!eligible) continue;
            ExpenseEntry entry = expenseService.createExpenseEntry(t.getCategory(), t.getTransactionDate(),
                    t.getTransactionDate(), t.getAmount().abs(), t.getNormalizedMerchant(), completedBy);
            t.setLinkedExpenseEntryId(entry.getId());
            transactions.save(t);
        }
        imp.setStatus(BankStatementImport.STATUS_COMPLETED);
        imp.setCompletedAt(Instant.now());
        return imports.save(imp);
    }

    /** Undoes only this import's own financial effect (design.md D10): deletes every
     * {@code expense_entries} row it created and resets those transactions to {@code UNMATCHED},
     * leaving every other import's and every manually-entered row untouched. */
    @Transactional
    public BankStatementImport revertImport(Long importId) {
        BankStatementImport imp = imports.findById(importId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such import"));
        if (!BankStatementImport.STATUS_COMPLETED.equals(imp.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a completed import can be reverted");
        }
        for (BankTransaction t : transactions.findByImportIdAndLinkedExpenseEntryIdIsNotNull(importId)) {
            expenseService.deleteExpenseEntry(t.getLinkedExpenseEntryId());
            t.setLinkedExpenseEntryId(null);
            t.setStatus(BankTransaction.STATUS_UNMATCHED);
            transactions.save(t);
        }
        imp.setStatus(BankStatementImport.STATUS_REVERTED);
        imp.setRevertedAt(Instant.now());
        return imports.save(imp);
    }
}
