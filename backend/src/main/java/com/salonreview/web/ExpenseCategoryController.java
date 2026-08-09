package com.salonreview.web;

import com.salonreview.domain.ExpenseCategoryDefinition;
import com.salonreview.square.ExpenseCategoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Owner-editable expense categories (follow-up to openspec change expense-import-reconciliation).
 * Falls under the {@code /api/owner/**} OWNER-only catch-all in SecurityConfig — no dedicated
 * matcher needed, same as {@link ExpenseController}.
 */
@RestController
@RequestMapping("/api/owner/expenses/categories")
public class ExpenseCategoryController {

    private final ExpenseCategoryService service;

    public ExpenseCategoryController(ExpenseCategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryDto> list() {
        return service.list().stream().map(ExpenseCategoryController::toDto).toList();
    }

    @PostMapping
    public CategoryDto create(@RequestBody CategoryRequest req) {
        return toDto(service.create(req.label()));
    }

    @PutMapping("/{id}")
    public CategoryDto rename(@PathVariable Long id, @RequestBody CategoryRequest req) {
        return toDto(service.rename(id, req.label()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    private static CategoryDto toDto(ExpenseCategoryDefinition c) {
        return new CategoryDto(c.getId(), c.getCode(), c.getLabel(), c.isProtectedCategory(), c.getSortOrder());
    }

    public record CategoryDto(Long id, String code, String label, boolean locked, int sortOrder) {}

    public record CategoryRequest(String label) {}
}
