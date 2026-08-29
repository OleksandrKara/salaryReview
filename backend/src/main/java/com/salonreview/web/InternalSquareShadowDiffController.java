package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.square.SquareMonthAggregatorShadowDiffService;
import com.salonreview.square.SquareMonthAggregatorShadowDiffService.ShadowDiffResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milestone 2g throwaway migration tool: runs {@link SquareMonthAggregatorShadowDiffService} on
 * demand. Not owner-facing (no UI ever calls this) — {@code permitAll()} + {@code X-Internal-Api-Key}
 * gating, same pattern as {@link InternalBusinessController}, since this exists purely to verify the
 * Phase 2 mirror-backed aggregate path before cutover and will be deleted in Milestone 2j once the
 * cutover has burned in cleanly.
 */
@RestController
@RequestMapping("/api/internal/square-shadow-diff")
public class InternalSquareShadowDiffController {

    private final InternalApiProperties internalApi;
    private final SquareMonthAggregatorShadowDiffService shadowDiff;

    public InternalSquareShadowDiffController(InternalApiProperties internalApi,
                                              SquareMonthAggregatorShadowDiffService shadowDiff) {
        this.internalApi = internalApi;
        this.shadowDiff = shadowDiff;
    }

    @GetMapping
    public ResponseEntity<?> month(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestParam Long businessId, @RequestParam int year, @RequestParam int month,
            @RequestParam(defaultValue = "0") BigDecimal cutoff) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(shadowDiff.diff(businessId, year, month, cutoff));
    }

    /** Runs the diff across the last {@code months} calendar months (ending this month), for one
     * business, in one call — this is the shape the plan's "run across every month the mirror
     * currently has real backfilled data for" step actually uses. */
    @GetMapping("/range")
    public ResponseEntity<?> range(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestParam Long businessId, @RequestParam(defaultValue = "24") int months,
            @RequestParam(defaultValue = "0") BigDecimal cutoff) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();

        YearMonth cursor = YearMonth.now();
        List<ShadowDiffResult> results = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            YearMonth ym = cursor.minusMonths(i);
            results.add(shadowDiff.diff(businessId, ym.getYear(), ym.getMonthValue(), cutoff));
        }
        long dirtyCount = results.stream().filter(r -> !r.clean()).count();
        return ResponseEntity.ok(Map.of(
                "businessId", businessId, "monthsChecked", results.size(),
                "cleanMonths", results.size() - dirtyCount, "dirtyMonths", dirtyCount,
                "results", results));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
