package com.salonreview.web;

import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.UnmatchedLine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Line-level debug view of the Square attribution for a month, to reconcile computed numbers against
 * the salon's manual tallies. Filter to one provider by name substring.
 *
 * <p>{@code GET /api/settlements/debug?year=2026&month=5&provider=Tatiana}
 */
@RestController
@RequestMapping("/api/settlements/debug")
public class SettlementDebugController {

    private final SquareMonthAggregator aggregator;

    public SettlementDebugController(SquareMonthAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @GetMapping
    public ResponseEntity<?> debug(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false, defaultValue = "60") BigDecimal cutoff) {

        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();

        try {
            MonthAggregation agg = aggregator.aggregate(y, m, cutoff);
            List<AttributedService> services = agg.services().stream()
                    .filter(s -> provider == null || s.providerName().toLowerCase().contains(provider.toLowerCase()))
                    .sorted((a, b) -> a.date().compareTo(b.date()))
                    .toList();
            return ResponseEntity.ok(Map.of(
                    "year", y, "month", m, "provider", provider == null ? "(all)" : provider,
                    "matchedCount", services.size(),
                    "services", services,
                    "unmatched", agg.unmatched()));
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Square API call failed",
                    "squareStatus", e.getStatusCode().value(),
                    "squareBody", e.getResponseBodyAsString()));
        }
    }
}
