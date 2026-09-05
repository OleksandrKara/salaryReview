package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.ServiceLifecycleReminderSend;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.ServiceLifecycleReminderSendRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OWNER-only read view of the win-back email fallback's activity — which email went to which
 * customer, when, whether they opened/clicked it, and whether they actually came back (a real
 * completed visit, not just a click — see
 * {@link WinbackEmailSendRepository#hasConversionSince}). Falls under the existing
 * {@code /api/owner/**} matcher in {@link com.salonreview.config.SecurityConfig}.
 *
 * <p>Also merges in any one-off email campaigns logged on {@code service_lifecycle_reminder_send}
 * (e.g. {@code color_booster_winback_oneoff}, see {@code ColorBoosterWinbackOneOffService}) — same
 * "which email went to which customer, when" question, just a table with no opened/clicked
 * tracking of its own (those two columns always report {@code null} for a merged-in row). The
 * aggregate {@code stats} block deliberately stays scoped to {@link WinbackEmailSend} only: mixing
 * in a channel that structurally can never report opens/clicks would silently drag down the real
 * automations' own open/click rates.
 */
@RestController
@RequestMapping("/api/owner/settings/mailchimp/activity")
public class MailchimpActivityController {

    /** Any one-off campaign logged on {@code service_lifecycle_reminder_send} that should appear
     * here — add a key as each new one-off ships. */
    private static final List<String> ONE_OFF_AUTOMATION_KEYS = List.of("color_booster_winback_oneoff");

    /** Both the listing and the aggregate stats look back this far — matches the 30-day window the
     * SMS automation cards already use elsewhere in this package. */
    private static final Duration WINDOW = Duration.ofDays(30);

    private final WinbackEmailSendRepository repo;
    private final ServiceLifecycleReminderSendRepository oneOffRepo;
    private final CurrentBusinessContext currentBusinessContext;

    public MailchimpActivityController(WinbackEmailSendRepository repo, ServiceLifecycleReminderSendRepository oneOffRepo,
                                        CurrentBusinessContext currentBusinessContext) {
        this.repo = repo;
        this.oneOffRepo = oneOffRepo;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public ResponseEntity<MailchimpActivityResponse> get() {
        Long businessId = currentBusinessContext.id();
        Instant since = Instant.now().minus(WINDOW);

        List<WinbackEmailSend> rows = repo.findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(businessId, since);
        List<SendView> sends = new ArrayList<>(rows.stream().map(r -> new SendView(
                "w" + r.getId(), r.getAutomationKey(), r.getEmailAddress(), r.getState(), r.getCreatedAt(),
                r.getOpenedAt(), r.getEmailClickedAt(),
                WinbackEmailSend.STATE_SENT.equals(r.getState()) && !r.getSquareCustomerId().isBlank()
                        && repo.hasConversionSince(businessId, r.getSquareCustomerId(), r.getCreatedAt())
        )).toList());

        for (String automationKey : ONE_OFF_AUTOMATION_KEYS) {
            List<ServiceLifecycleReminderSend> oneOffRows = oneOffRepo
                    .findByBusinessIdAndAutomationKeyAndCreatedAtAfterOrderByCreatedAtDesc(businessId, automationKey, since);
            for (ServiceLifecycleReminderSend r : oneOffRows) {
                boolean converted = ServiceLifecycleReminderSend.STATE_SENT.equals(r.getState())
                        && oneOffRepo.hasConversionSince(businessId, r.getSquareCustomerId(), r.getTriggerServiceDate());
                sends.add(new SendView("o" + r.getId(), r.getAutomationKey(), r.getPhoneNumber(), r.getState(),
                        r.getCreatedAt(), null, null, converted));
            }
        }
        sends.sort(Comparator.comparing(SendView::sentAt).reversed());

        long sentCount = repo.countByBusinessIdAndStateAndCreatedAtAfter(businessId, WinbackEmailSend.STATE_SENT, since);
        long openedCount = repo.countByBusinessIdAndStateAndOpenedAtIsNotNullAndCreatedAtAfter(
                businessId, WinbackEmailSend.STATE_SENT, since);
        long clickedCount = repo.countByBusinessIdAndStateAndEmailClickedAtIsNotNullAndCreatedAtAfter(
                businessId, WinbackEmailSend.STATE_SENT, since);
        long convertedCount = rows.stream().filter(r -> WinbackEmailSend.STATE_SENT.equals(r.getState())
                && !r.getSquareCustomerId().isBlank() && repo.hasConversionSince(businessId, r.getSquareCustomerId(), r.getCreatedAt())).count();

        MailchimpActivityStats stats = new MailchimpActivityStats(
                30, sentCount, openedCount, clickedCount, convertedCount,
                rate(openedCount, sentCount), rate(clickedCount, sentCount), rate(convertedCount, sentCount));

        return ResponseEntity.ok(new MailchimpActivityResponse(sends, stats));
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    public record SendView(String id, String automationKey, String emailAddress, String state, Instant sentAt,
                            Instant openedAt, Instant clickedAt, boolean converted) {
    }

    public record MailchimpActivityStats(int windowDays, long sentCount, long openedCount, long clickedCount,
                                          long convertedCount, double openRate, double clickRate, double conversionRate) {
    }

    public record MailchimpActivityResponse(List<SendView> sends, MailchimpActivityStats stats) {
    }
}
