package com.salonreview.web;

import com.salonreview.ai.SuspiciousBookingTriageService;
import com.salonreview.ai.TriageResult;
import com.salonreview.domain.SuspiciousBookingClearance;
import com.salonreview.repo.SuspiciousBookingClearanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-time admin endpoint that bootstraps the LangSmith eval dataset by running the v1 triage
 * prompt against every cleared-with-note booking in the requested month. The owner triggers it
 * once after enabling the feature flag; subsequent triages come from the normal owner workflow.
 *
 * <p>Why bootstrap at all: without it we'd have zero labeled rows in LangSmith on day one and
 * couldn't measure Haiku's agreement rate until owners had organically clicked Explain on enough
 * historical bookings. With it, the labeled set is ready before the first user touches the UI.
 *
 * <p>Each call to {@link SuspiciousBookingTriageService#triage} either (a) returns the cached row
 * (if this booking was already triaged), (b) calls Claude + persists + ships a LangSmith trace
 * tagged with the current prompt version, or (c) returns empty when the booking is no longer in
 * the suspicious set (Square may have caught up). The owner's clearance note travels in the
 * triage's persisted row via the clearance hook ({@link com.salonreview.ai.TriageFeedbackPublisher})
 * and ships to LangSmith as a graded feedback event the next time someone clicks Clear/Undo.
 *
 * <p>The endpoint lives under {@code /api/owner/**} so it inherits the OWNER-only matcher in
 * {@code SecurityConfig} — only the salon owner can trigger this.
 */
@RestController
@RequestMapping("/api/owner")
@ConditionalOnProperty(prefix = "ai.triage", name = "enabled", havingValue = "true")
public class TriageEvalBootstrapController {

    private static final Logger log = LoggerFactory.getLogger(TriageEvalBootstrapController.class);

    private final SuspiciousBookingClearanceRepository clearances;
    private final SuspiciousBookingTriageService triageService;

    public TriageEvalBootstrapController(SuspiciousBookingClearanceRepository clearances,
                                         SuspiciousBookingTriageService triageService) {
        this.clearances = clearances;
        this.triageService = triageService;
    }

    @PostMapping("/triage-eval-bootstrap")
    public Map<String, Object> bootstrap(@RequestParam int year, @RequestParam int month) {
        List<SuspiciousBookingClearance> labeled = clearances.findAll().stream()
                .filter(c -> c.getNote() != null && !c.getNote().isBlank())
                .toList();

        int triaged = 0;
        int notFlagged = 0;
        List<String> errors = new ArrayList<>();

        for (SuspiciousBookingClearance c : labeled) {
            try {
                Optional<TriageResult> result =
                        triageService.triage(c.getSquareBookingId(), year, month);
                if (result.isPresent()) {
                    triaged++;
                } else {
                    notFlagged++;
                }
            } catch (Exception e) {
                log.warn("bootstrap triage failed for booking {}: {}",
                        c.getSquareBookingId(), e.toString());
                errors.add(c.getSquareBookingId() + ": " + e.getMessage());
            }
        }

        // LinkedHashMap to preserve a stable key order in the response JSON.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("month", year + "-" + month);
        summary.put("labeledClearances", labeled.size());
        summary.put("triaged", triaged);
        summary.put("notFlaggedThisMonth", notFlagged);
        summary.put("errorCount", errors.size());
        summary.put("errors", errors);
        log.info("Bootstrap summary: {}", summary);
        return summary;
    }
}
