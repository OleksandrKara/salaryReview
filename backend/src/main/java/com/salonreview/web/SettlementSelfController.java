package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.FeedbackStatus;
import com.salonreview.domain.Half;
import com.salonreview.domain.SettlementFeedback;
import com.salonreview.repo.SettlementFeedbackRepository;
import com.salonreview.square.SettlementPreviewService;
import com.salonreview.square.SettlementPreviewService.ProviderDetail;
import com.salonreview.square.SettlementPreviewService.ProviderPayout;
import com.salonreview.square.SuspiciousBookingService;
import com.salonreview.web.dto.SuspiciousBookingDto;
import java.util.List;
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
    private final SuspiciousBookingService suspiciousBookings;

    public SettlementSelfController(SettlementPreviewService previews,
                                    SettlementFeedbackRepository feedback,
                                    SuspiciousBookingService suspiciousBookings) {
        this.previews = previews;
        this.feedback = feedback;
        this.suspiciousBookings = suspiciousBookings;
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

    /**
     * The provider's own line-level breakdown (appointments, discounts, prepaid, cash notes) plus the
     * {@code #salary} blocks — so they can trace their own numbers. The salon-wide unattributed lines
     * and orphan payments are withheld here (they reference other customers); that view stays
     * owner/manager-only.
     */
    @GetMapping("/detail")
    public ResponseEntity<ProviderDetail> myDetail(
            @AuthenticationPrincipal AppUserPrincipal me,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        Long providerId = requireProvider(me);
        LocalDate today = LocalDate.now();
        int y = year != null ? year : today.getYear();
        int m = month != null ? month : today.getMonthValue();
        ProviderDetail d = previews.providerDetail(y, m, providerId);
        // Strip the salon-wide unattributed lines and orphan payments for the provider's own view.
        ProviderDetail scoped = new ProviderDetail(d.year(), d.month(), d.providerId(), d.name(),
                d.payout(), d.services(), List.of(), List.of(), d.firstHalfMessage(), d.secondHalfMessage(),
                d.priceCutoff(), d.timezone(), d.syncedAt(), d.noShows());
        return ResponseEntity.ok(scoped);
    }

    /**
     * The provider's own actionable suspicious-bookings list — read-only, no-notes-only. Provider
     * cannot clear (only owner/manager can); they can only fix the source by adding a {@code cashew $nn}
     * note in Square. Always scoped to the authenticated provider.
     */
    @GetMapping("/suspicious")
    public List<SuspiciousBookingDto> mySuspicious(
            @AuthenticationPrincipal AppUserPrincipal me,
            @RequestParam int year, @RequestParam int month, @RequestParam Half half) {
        Long providerId = requireProvider(me);
        return suspiciousBookings.listForSelf(year, month, half, providerId);
    }

    @PostMapping("/feedback")
    @Transactional
    public SettlementFeedback submitFeedback(
            @AuthenticationPrincipal AppUserPrincipal me,
            @RequestParam int year, @RequestParam int month, @RequestParam Half half,
            @RequestBody FeedbackRequest req) {
        Long providerId = requireProvider(me);
        if (req.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        SettlementFeedback fb = feedback.findByProviderIdAndYearAndMonthAndHalf(providerId, year, month, half)
                .orElseGet(() -> SettlementFeedback.builder()
                        .providerId(providerId).year(year).month(month).half(half).build());
        fb.setStatus(req.status());
        fb.setComment(req.comment());
        fb.setUpdatedAt(Instant.now());
        SettlementFeedback saved = feedback.save(fb);
        previews.invalidateCache();
        return saved;
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
