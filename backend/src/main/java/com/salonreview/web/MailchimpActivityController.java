package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.WinbackEmailSendRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * OWNER-only read view of the win-back email fallback's activity — which email went to which
 * customer, when, whether they opened/clicked it, and whether they actually came back (a real
 * completed visit, not just a click — see
 * {@link WinbackEmailSendRepository#hasConversionSince}). Falls under the existing
 * {@code /api/owner/**} matcher in {@link com.salonreview.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/owner/settings/mailchimp/activity")
public class MailchimpActivityController {

    /** Both the listing and the aggregate stats look back this far — matches the 30-day window the
     * SMS automation cards already use elsewhere in this package. */
    private static final Duration WINDOW = Duration.ofDays(30);

    private final WinbackEmailSendRepository repo;
    private final CurrentBusinessContext currentBusinessContext;

    public MailchimpActivityController(WinbackEmailSendRepository repo, CurrentBusinessContext currentBusinessContext) {
        this.repo = repo;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public ResponseEntity<MailchimpActivityResponse> get() {
        Long businessId = currentBusinessContext.id();
        Instant since = Instant.now().minus(WINDOW);

        List<WinbackEmailSend> rows = repo.findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(businessId, since);
        List<SendView> sends = rows.stream().map(r -> new SendView(
                r.getId(), r.getAutomationKey(), r.getEmailAddress(), r.getState(), r.getCreatedAt(),
                r.getOpenedAt(), r.getEmailClickedAt(),
                WinbackEmailSend.STATE_SENT.equals(r.getState()) && !r.getSquareCustomerId().isBlank()
                        && repo.hasConversionSince(businessId, r.getSquareCustomerId(), r.getCreatedAt())
        )).toList();

        long sentCount = repo.countByBusinessIdAndStateAndCreatedAtAfter(businessId, WinbackEmailSend.STATE_SENT, since);
        long openedCount = repo.countByBusinessIdAndStateAndOpenedAtIsNotNullAndCreatedAtAfter(
                businessId, WinbackEmailSend.STATE_SENT, since);
        long clickedCount = repo.countByBusinessIdAndStateAndEmailClickedAtIsNotNullAndCreatedAtAfter(
                businessId, WinbackEmailSend.STATE_SENT, since);
        long convertedCount = sends.stream().filter(SendView::converted).count();

        MailchimpActivityStats stats = new MailchimpActivityStats(
                30, sentCount, openedCount, clickedCount, convertedCount,
                rate(openedCount, sentCount), rate(clickedCount, sentCount), rate(convertedCount, sentCount));

        return ResponseEntity.ok(new MailchimpActivityResponse(sends, stats));
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    public record SendView(Long id, String automationKey, String emailAddress, String state, Instant sentAt,
                            Instant openedAt, Instant clickedAt, boolean converted) {
    }

    public record MailchimpActivityStats(int windowDays, long sentCount, long openedCount, long clickedCount,
                                          long convertedCount, double openRate, double clickRate, double conversionRate) {
    }

    public record MailchimpActivityResponse(List<SendView> sends, MailchimpActivityStats stats) {
    }
}
