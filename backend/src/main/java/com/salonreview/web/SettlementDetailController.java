package com.salonreview.web;

import com.salonreview.square.SettlementPreviewService;
import com.salonreview.square.SettlementPreviewService.ProviderDetail;
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
 * Owner/manager line-level trace for one provider/month: every attributed service (with discount,
 * net, prepaid flag, channel) plus the salon's unattributed order lines — for chasing a payment that
 * looks short or missing. Backs the {@code /reports/[provider]} drill-down.
 *
 * <p>{@code GET /api/settlements/detail?year=2026&month=5&providerId=19}
 */
@RestController
@RequestMapping("/api/settlements/detail")
public class SettlementDetailController {

    private final SettlementPreviewService previews;

    public SettlementDetailController(SettlementPreviewService previews) {
        this.previews = previews;
    }

    @GetMapping
    public ResponseEntity<?> detail(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam Long providerId) {

        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();

        try {
            ProviderDetail detail = previews.providerDetail(y, m, providerId);
            return ResponseEntity.ok(detail);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Square API call failed",
                    "squareStatus", e.getStatusCode().value(),
                    "squareBody", e.getResponseBodyAsString()));
        }
    }
}
