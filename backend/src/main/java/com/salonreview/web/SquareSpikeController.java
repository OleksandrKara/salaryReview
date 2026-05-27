package com.salonreview.web;

import com.salonreview.square.SquareSpikeService;
import com.salonreview.square.SquareSpikeService.SpikeReport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Read-only Square reconciliation spike. Not part of the eventual product surface — a diagnostic to
 * judge whether the salon's real data supports full automation. Defaults to the current month.
 *
 * <p>{@code GET /api/square/spike?from=2026-05-01&to=2026-05-31&cutoff=20}
 */
@RestController
@RequestMapping("/api/square/spike")
public class SquareSpikeController {

    private final SquareSpikeService spike;

    public SquareSpikeController(SquareSpikeService spike) {
        this.spike = spike;
    }

    @GetMapping
    public ResponseEntity<?> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "20") BigDecimal cutoff) {

        LocalDate today = LocalDate.now();
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;

        try {
            SpikeReport report = spike.run(start, end, cutoff);
            return ResponseEntity.ok(report);
        } catch (RestClientResponseException e) {
            // Surface Square's own error (bad token, scope, version) instead of a generic 500.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Square API call failed",
                    "squareStatus", e.getStatusCode().value(),
                    "squareBody", e.getResponseBodyAsString()));
        }
    }
}
