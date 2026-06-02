package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.NoShowFeeService;
import com.salonreview.square.NoShowFeeService.ConfirmRequest;
import com.salonreview.square.NoShowFeeService.NoShowRow;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * No-show fee tracking (owner/manager — gated in SecurityConfig). Lists a month's no-shows with their
 * fee status; the override actions handle the messy real-world cases (a fee collected off-signal, or a
 * false-positive auto-match). The common case — an auto-detected paid fee — needs no action here.
 */
@RestController
@RequestMapping("/api/no-show-fees")
public class NoShowFeeController {

    private final NoShowFeeService service;

    public NoShowFeeController(NoShowFeeService service) {
        this.service = service;
    }

    @GetMapping
    public List<NoShowRow> list(@RequestParam int year, @RequestParam int month) {
        return service.rowsForMonth(year, month);
    }

    /** Credit a provider for a fee collected off-signal (cash / quick-sale / paid > 2 months later). */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestBody ConfirmRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        service.confirm(req, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    /** Do not credit an auto-detected fee (false positive / disputed). */
    @PostMapping("/suppress")
    public ResponseEntity<Void> suppress(@RequestParam String bookingId, @AuthenticationPrincipal AppUserPrincipal me) {
        service.suppress(bookingId, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    /** Remove a prior override (un-do a confirm or a suppress). */
    @DeleteMapping("/{bookingId}")
    public ResponseEntity<Void> clear(@PathVariable String bookingId) {
        service.clearOverride(bookingId);
        return ResponseEntity.noContent().build();
    }
}
