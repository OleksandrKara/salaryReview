package com.salonreview.web;

import com.salonreview.domain.Half;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.square.SettlementPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Owner/manager action on a provider's settlement feedback. Currently just "undo": clear a provider's
 * approval / change-request for a period (e.g. they approved by mistake, or after a correction was
 * handled). Gated owner+manager via {@code /api/settlements/**} in SecurityConfig — distinct from the
 * provider's own {@code /api/settlements/me/feedback}.
 */
@RestController
@RequestMapping("/api/settlements/feedback")
public class SettlementFeedbackAdminController {

    private final SettlementFeedbackRepository feedback;
    private final SettlementPreviewService settlementPreview;

    public SettlementFeedbackAdminController(SettlementFeedbackRepository feedback, SettlementPreviewService settlementPreview) {
        this.feedback = feedback;
        this.settlementPreview = settlementPreview;
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> clear(@RequestParam Long providerId, @RequestParam int year,
                                      @RequestParam int month, @RequestParam Half half) {
        feedback.deleteByProviderIdAndYearAndMonthAndHalf(providerId, year, month, half);
        settlementPreview.invalidateCache();
        return ResponseEntity.noContent().build();
    }
}
