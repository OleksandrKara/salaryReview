package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.ExpenseEntry;
import com.salonreview.square.ExpenseCategoryService;
import com.salonreview.square.ExpenseService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Business expense entries (materials/supplies today, other categories as they come up) — the
 * cost side of the Overview dashboard's net-revenue figure. Salon-wide, not landing-page-scoped,
 * so this lives under its own {@code /api/owner/expenses} route rather than nested under
 * {@code /api/owner/marketing/**}. OWNER-only via SecurityConfig's {@code /api/owner/**}
 * catch-all — no ADS_MANAGER-style carve-out here, unlike ad spend.
 */
@RestController
@RequestMapping("/api/owner/expenses")
public class ExpenseController {

    private final ExpenseService service;
    private final ExpenseCategoryService categoryService;

    public ExpenseController(ExpenseService service, ExpenseCategoryService categoryService) {
        this.service = service;
        this.categoryService = categoryService;
    }

    @PostMapping
    public ExpenseEntryDto create(@RequestBody ExpenseEntryRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        categoryService.assertValidCode(req.category());
        ExpenseEntry saved = service.createExpenseEntry(
                req.category(), req.periodStart(), req.periodEnd(), req.amount(), req.note(), me.getUsername());
        return toDto(saved);
    }

    @GetMapping
    public List<ExpenseEntryDto> list() {
        return service.listExpenseEntries().stream().map(ExpenseController::toDto).toList();
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExpenseEntryDto> update(@PathVariable Long id, @RequestBody ExpenseEntryRequest req) {
        categoryService.assertValidCode(req.category());
        return service.updateExpenseEntry(id, req.category(), req.periodStart(), req.periodEnd(), req.amount(), req.note())
                .map(ExpenseController::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return service.deleteExpenseEntry(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static ExpenseEntryDto toDto(ExpenseEntry e) {
        return new ExpenseEntryDto(e.getId(), e.getCategory(), e.getPeriodStart(), e.getPeriodEnd(),
                e.getAmount(), e.getNote(), e.getEnteredBy(), e.getEnteredAt());
    }

    public record ExpenseEntryRequest(String category, LocalDate periodStart, LocalDate periodEnd,
                                       BigDecimal amount, String note) {}

    public record ExpenseEntryDto(Long id, String category, LocalDate periodStart, LocalDate periodEnd,
                                   BigDecimal amount, String note, String enteredBy, Instant enteredAt) {}
}
