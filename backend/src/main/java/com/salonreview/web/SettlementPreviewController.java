package com.salonreview.web;

import com.salonreview.square.SettlementPreviewService;
import com.salonreview.square.SettlementPreviewService.SettlementPreview;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Map;

/**
 * Month settlement computed from Square, using the salon's stored commission config and any persisted
 * manual 50/50 grants (managed via {@code /api/settlements/grants}).
 *
 * <p>{@code GET /api/settlements/preview?year=2026&month=5}
 */
@RestController
@RequestMapping("/api/settlements/preview")
public class SettlementPreviewController {

    private final SettlementPreviewService previews;

    public SettlementPreviewController(SettlementPreviewService previews) {
        this.previews = previews;
    }

    @GetMapping
    public ResponseEntity<?> preview(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();

        try {
            SettlementPreview report = previews.preview(y, m);
            return ResponseEntity.ok(report);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Square API call failed",
                    "squareStatus", e.getStatusCode().value(),
                    "squareBody", e.getResponseBodyAsString()));
        }
    }
}
