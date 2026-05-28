package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.FeedbackStatus;
import com.salonreview.domain.SettlementFeedback;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.square.SettlementPreviewService;
import com.salonreview.square.SettlementPreviewService.ProviderPayout;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A provider's read-only view of their own month, plus their approve / request-correction action.
 * The provider is always taken from the authenticated principal — never a request parameter — so a
 * provider can only ever see and act on their own settlement.
 */
@RestController
@RequestMapping("/api/settlements/me")
public class SettlementSelfController {

    private final SettlementPreviewService previews;
    private final SettlementFeedbackRepository feedback;

    public SettlementSelfController(SettlementPreviewService previews, SettlementFeedbackRepository feedback) {
        this.previews = previews;
        this.feedback = feedback;
    }

    @GetMapping
    public ResponseEntity<ProviderPayout> mySettlement(
            @AuthenticationPrincipal AppUserPrincipal me,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        Long providerId = requireProvider(me);
        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();
        ProviderPayout payout = previews.previewForProvider(y, m, providerId);
        return payout != null ? ResponseEntity.ok(payout) : ResponseEntity.noContent().build();
    }

    @PostMapping("/feedback")
    @Transactional
    public SettlementFeedback submitFeedback(
            @AuthenticationPrincipal AppUserPrincipal me,
            @RequestParam int year, @RequestParam int month,
            @RequestBody FeedbackRequest req) {
        Long providerId = requireProvider(me);
        if (req.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        SettlementFeedback fb = feedback.findByProviderIdAndYearAndMonth(providerId, year, month)
                .orElseGet(() -> SettlementFeedback.builder()
                        .providerId(providerId).year(year).month(month).build());
        fb.setStatus(req.status());
        fb.setComment(req.comment());
        fb.setUpdatedAt(Instant.now());
        return feedback.save(fb);
    }

    private Long requireProvider(AppUserPrincipal me) {
        Long providerId = me.getProviderId();
        if (providerId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not linked to a provider");
        }
        return providerId;
    }

    public record FeedbackRequest(FeedbackStatus status, String comment) {}
}
