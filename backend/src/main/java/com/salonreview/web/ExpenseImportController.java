package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.BankStatementImport;
import com.salonreview.domain.BankTransaction;
import com.salonreview.square.ExpenseImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Bank statement import + reconciliation (openspec change expense-import-reconciliation). Falls
 * under the {@code /api/owner/**} OWNER-only catch-all in SecurityConfig — no dedicated matcher
 * needed, same as {@link ExpenseController}.
 */
@RestController
@RequestMapping("/api/owner/expenses/imports")
public class ExpenseImportController {

    private final ExpenseImportService service;

    public ExpenseImportController(ExpenseImportService service) {
        this.service = service;
    }

    @PostMapping
    public ImportDto upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AppUserPrincipal me) {
        return toImportDto(service.importStatement(file, me.getUsername()));
    }

    @GetMapping
    public List<ImportDto> list() {
        return service.listImports().stream().map(ExpenseImportController::toImportDto).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImportDetailDto> get(@PathVariable Long id) {
        return service.getImport(id)
                .map(imp -> ResponseEntity.ok(new ImportDetailDto(toImportDto(imp),
                        service.getTransactions(id).stream().map(ExpenseImportController::toTransactionDto).toList())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long id) {
        return service.getImport(id)
                .map(imp -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + imp.getOriginalFilename() + "\"")
                        .body(imp.getRawFile()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** {@code id} (the import) is purely a path-namespacing convenience here — {@code txnId} alone
     * already uniquely identifies the transaction to review, and this whole area is OWNER-only. */
    @PatchMapping("/{id}/transactions/{txnId}")
    public TransactionDto reviewTransaction(@PathVariable Long id, @PathVariable Long txnId,
                                             @RequestBody ReviewRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        BankTransaction txn = service.reviewTransaction(txnId, req.category(), req.excludeReason(),
                Boolean.TRUE.equals(req.rememberForMerchant()), Boolean.TRUE.equals(req.replaceExisting()),
                req.rememberKeywords(), me.getUsername());
        return toTransactionDto(txn);
    }

    @PostMapping("/{id}/transactions/bulk")
    public List<TransactionDto> bulkReview(@PathVariable Long id, @RequestBody BulkReviewRequest req,
                                            @AuthenticationPrincipal AppUserPrincipal me) {
        return service.bulkReviewTransactions(req.transactionIds(), req.category(), req.excludeReason(),
                        Boolean.TRUE.equals(req.rememberForMerchant()), Boolean.TRUE.equals(req.replaceExisting()),
                        req.rememberKeywords(), me.getUsername())
                .stream().map(ExpenseImportController::toTransactionDto).toList();
    }

    @PostMapping("/{id}/complete")
    public ImportDto complete(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return toImportDto(service.completeReconciliation(id, me.getUsername()));
    }

    @PostMapping("/{id}/revert")
    public ImportDto revert(@PathVariable Long id) {
        return toImportDto(service.revertImport(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteImport(id);
    }

    private static ImportDto toImportDto(BankStatementImport i) {
        return new ImportDto(i.getId(), i.getOriginalFilename(), i.getRowCount(), i.getStatementPeriodStart(),
                i.getStatementPeriodEnd(), i.getStatus(), i.getUploadedBy(), i.getUploadedAt(),
                i.getCompletedAt(), i.getRevertedAt(), i.getOpeningBalance(), i.getClosingBalance());
    }

    private static TransactionDto toTransactionDto(BankTransaction t) {
        return new TransactionDto(t.getId(), t.getImportId(), t.getTransactionDate(), t.getRawDescription(),
                t.getNormalizedMerchant(), t.getAmount(), t.getStatus(), t.getMatchReason(), t.getConfidence(),
                t.getCategory(), t.getExcludedReason(), t.getLinkedExpenseEntryId(), t.getDuplicateOfTransactionId(),
                t.getReviewedBy(), t.getReviewedAt());
    }

    public record ImportDto(Long id, String originalFilename, int rowCount, LocalDate statementPeriodStart,
                             LocalDate statementPeriodEnd, String status, String uploadedBy, Instant uploadedAt,
                             Instant completedAt, Instant revertedAt,
                             java.math.BigDecimal openingBalance, java.math.BigDecimal closingBalance) {}

    public record ImportDetailDto(ImportDto importSummary, List<TransactionDto> transactions) {}

    public record TransactionDto(Long id, Long importId, LocalDate transactionDate, String rawDescription,
                                  String normalizedMerchant, BigDecimal amount, String status, String matchReason,
                                  BigDecimal confidence, String category, String excludedReason,
                                  Long linkedExpenseEntryId, Long duplicateOfTransactionId, String reviewedBy,
                                  Instant reviewedAt) {}

    public record ReviewRequest(String category, String excludeReason, Boolean rememberForMerchant,
                                 Boolean replaceExisting, List<String> rememberKeywords) {}

    public record BulkReviewRequest(List<Long> transactionIds, String category, String excludeReason,
                                     Boolean rememberForMerchant, Boolean replaceExisting,
                                     List<String> rememberKeywords) {}
}
