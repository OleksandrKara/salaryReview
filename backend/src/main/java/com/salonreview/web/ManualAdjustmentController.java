package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.ManualAdjustmentService;
import com.salonreview.square.ManualAdjustmentService.CreateRequest;
import com.salonreview.square.ManualAdjustmentService.ManualAdjustmentView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manual settlement adjustments (owner/manager — gated in SecurityConfig). A deliberate exception
 * for money Square can't reflect on its own — crediting a messily-recorded service (positive) or
 * deducting a provider's commission for something like a refund (negative).
 */
@RestController
@RequestMapping("/api/manual-adjustments")
public class ManualAdjustmentController {

    private final ManualAdjustmentService adjustments;

    public ManualAdjustmentController(ManualAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @GetMapping
    public List<ManualAdjustmentView> list() {
        return adjustments.list();
    }

    @PostMapping
    public ManualAdjustmentView create(@RequestBody CreateRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        return adjustments.create(req, me == null ? null : me.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adjustments.delete(id);
        return ResponseEntity.noContent().build();
    }
}
