package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evening email follow-up for the two lifecycle-reminder nudges (see
 * {@link ColorBoosterReminderScheduler}, {@link TouchupReminderScheduler}, both once-daily at
 * 10am) — same shape as {@link WinbackEmailFallbackScheduler}, but deliberately a separate class
 * rather than another entry in that one's {@code AUTOMATION_KEYS}: these two carry no discount,
 * no click-tracked link, and no expiring-tonight urgency framing, so a shared {@code process()}
 * would have to special-case around all three of those (discount lookup, {@code
 * sms.getClickToken()}, "Last call... expires tonight" subject) for automations that use none of
 * them. Only their own real per-technique deep link (see the two email templates' own comments)
 * to the salonLandings PMU booking widget — see {@code PmuDeepLinkOpener.tsx} and {@code
 * pmu_catalog.py}'s {@code touch-up}/{@code color-booster} slugs, which this app's PR #118 built
 * specifically so an emailed reminder can link straight to that one technique (both are
 * deliberately left off the public landing page's own technique list, so they have no other
 * on-page button to reach from).
 *
 * <p>Same 7pm fixed time as {@link WinbackEmailFallbackScheduler}, for the same reason: late
 * enough that most people who were going to see and act on the morning SMS already have, early
 * enough to still land in the evening. These two automations are the same "wants a booking
 * decision" genre as {@code same_day_rebooking_discount} (already on that evening cadence), not
 * the quick-single-tap genre {@link CheckoutReviewEmailFallbackScheduler} waits a full 24h for.
 *
 * <p>"No reply" is the only gate available (unlike the discount automations, which also gate on
 * "no click" — these carry no link to click) — an inbound SMS from the same number after the
 * morning reminder went out counts as a reply, same check {@link WinbackEmailFallbackScheduler}
 * already uses.
 */
@Component
public class LifecycleReminderEmailFallbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(LifecycleReminderEmailFallbackScheduler.class);
    private static final List<String> AUTOMATION_KEYS = List.of(
            ColorBoosterReminderScheduler.AUTOMATION_KEY, TouchupReminderScheduler.AUTOMATION_KEY);
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    /** The real, deployed salonLandings deep links each template's own comment documents —
     * touch-up/color-booster are deliberately not on the public landing page's technique list, so
     * this is the only way either email's CTA button can reach the right pre-set booking modal. */
    private static final Map<String, String> BOOKING_LINKS = Map.of(
            ColorBoosterReminderScheduler.AUTOMATION_KEY, "https://book.pmu-annakara.com/?book=color-booster",
            TouchupReminderScheduler.AUTOMATION_KEY, "https://book.pmu-annakara.com/?book=touch-up"
    );

    private static final Map<String, String> SUBJECT_LINES = Map.of(
            ColorBoosterReminderScheduler.AUTOMATION_KEY, "Time for your color booster",
            TouchupReminderScheduler.AUTOMATION_KEY, "Time for your touch-up"
    );

    private final SmsMessageRepository smsMessageRepository;
    private final WinbackEmailSendRepository winbackEmailSendRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final SquareClientProvider squareClientProvider;
    private final SmsAutomationService automationService;

    public LifecycleReminderEmailFallbackScheduler(SmsMessageRepository smsMessageRepository,
                                                    WinbackEmailSendRepository winbackEmailSendRepository,
                                                    MailchimpConfigRepository mailchimpConfigRepository,
                                                    MailchimpEmailService mailchimpEmailService,
                                                    MailchimpEmailTemplateService templateService,
                                                    SquareClientProvider squareClientProvider,
                                                    SmsAutomationService automationService) {
        this.smsMessageRepository = smsMessageRepository;
        this.winbackEmailSendRepository = winbackEmailSendRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.squareClientProvider = squareClientProvider;
        this.automationService = automationService;
    }

    @Scheduled(cron = "0 0 19 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "LifecycleReminderEmailFallbackScheduler_sendDueFollowUps", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sendDueFollowUps() {
        Instant startOfToday = LocalDate.now(SALON_ZONE).atStartOfDay(SALON_ZONE).toInstant();
        Instant now = Instant.now();
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            List<SmsMessage> candidates = smsMessageRepository
                    .findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                            businessId, AUTOMATION_KEYS, "OUTBOUND", "SENT", startOfToday, now);
            for (SmsMessage sms : candidates) {
                try {
                    process(sms, config);
                } catch (RuntimeException e) {
                    log.warn("Lifecycle-reminder email fallback failed for sms_message {} (skipped, not retried): {}",
                            sms.getId(), e.getMessage(), e);
                }
            }
        }
    }

    private void process(SmsMessage sms, MailchimpConfig config) {
        if (winbackEmailSendRepository.existsBySmsMessageId(sms.getId())) {
            return; // belt-and-suspenders; the query window shouldn't reoffer the same row twice in one run
        }
        Long businessId = sms.getBusinessId();
        String automationKey = sms.getAutomationKey();

        if (smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                businessId, sms.getPhoneNumber(), "INBOUND", sms.getCreatedAt())) {
            save(sms, null, null, WinbackEmailSend.STATE_SKIPPED_REPLIED, null, null);
            return;
        }
        if (!automationService.isEnabled(businessId, automationKey)) {
            save(sms, null, null, WinbackEmailSend.STATE_SKIPPED_DISABLED, null, null);
            return;
        }

        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Lifecycle-reminder email fallback skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return; // no row saved — but the query window is "today only", so this customer won't
                    // be reconsidered tomorrow; acceptable for a bonus fallback channel, not core.
        }

        String customerId = square.customerIdsForPhone(sms.getPhoneNumber()).stream().findFirst().orElse(null);
        if (customerId == null) {
            save(sms, null, null, WinbackEmailSend.STATE_SKIPPED_NO_EMAIL, null, null);
            return;
        }
        String email = square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            save(sms, customerId, null, WinbackEmailSend.STATE_SKIPPED_NO_EMAIL, null, null);
            return;
        }

        String givenName = Names.capitalizeFirst(
                square.customerGivenNames(List.of(customerId)).getOrDefault(customerId, null));

        Map<String, String> vars = Map.of(
                "FNAME", givenName == null ? "there" : givenName,
                "LINK", BOOKING_LINKS.get(automationKey));

        Optional<String> html = templateService.render(businessId, automationKey, vars);
        if (html.isEmpty()) {
            save(sms, customerId, email, WinbackEmailSend.STATE_SKIPPED_NO_TEMPLATE, null, null);
            return;
        }

        String fname = vars.get("FNAME");
        String subjectLine = SUBJECT_LINES.get(automationKey) + (fname.equals("there") ? "" : ", " + fname);
        String previewText = "A personal note from Anna";
        String campaignTitle = automationKey + " email follow-up — " + sms.getId();

        try {
            String campaignId = mailchimpEmailService.sendWinbackEmail(
                    config, email, subjectLine, previewText, campaignTitle, html.get());
            save(sms, customerId, email, WinbackEmailSend.STATE_SENT, campaignId, html.get());
        } catch (Exception e) {
            log.warn("Lifecycle-reminder email send failed for sms_message {} (not retried): {}", sms.getId(), e.getMessage());
            save(sms, customerId, email, WinbackEmailSend.STATE_SEND_FAILED, null, null);
        }
    }

    private void save(SmsMessage sms, String squareCustomerId, String email, String state, String campaignId,
                       String contentHtml) {
        winbackEmailSendRepository.save(WinbackEmailSend.builder()
                .businessId(sms.getBusinessId())
                .automationKey(sms.getAutomationKey())
                .smsMessageId(sms.getId())
                .squareCustomerId(squareCustomerId == null ? "" : squareCustomerId)
                .emailAddress(email)
                .state(state)
                .mailchimpCampaignId(campaignId)
                .contentHtml(contentHtml)
                .build());
    }
}
