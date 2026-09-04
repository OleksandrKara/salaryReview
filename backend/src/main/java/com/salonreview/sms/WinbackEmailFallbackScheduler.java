package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evening email follow-up for the SMS automations that offer a discount to come back — SMS goes
 * out first (see {@link LapsedCustomerWinbackScheduler}/{@link RepeatCustomerWinbackScheduler},
 * both once-daily at 10am; {@link SameDayRebookingScheduler} fires 3 hours after checkout, same
 * day); this scheduler runs at a fixed evening time and emails only the customers who neither
 * clicked their SMS link nor replied by then. Deliberately not a parallel/simultaneous channel —
 * SMS is the higher-converting channel for this business (see the 2026-08-27 open-rate/CTR
 * analysis), so email is a second touch for non-responders, not a duplicate one, and it's framed
 * in the templates as a "last call" follow-up ("the $10 off I texted you about earlier") rather
 * than a cold open.
 *
 * <p>Reuses the exact same click-tracked short link the SMS carried ({@link SmsMessage#getClickToken()})
 * rather than minting a new one — a click from either channel marks the same {@code sms_message}
 * row's {@code clicked_at}, so click tracking stays in one place with no parallel system needed.
 *
 * <p>7pm, not right at the coupon's midnight expiry — late enough that most people who were going
 * to see and act on the morning SMS already have, early enough that the email doesn't arrive after
 * most people have stopped checking their inbox for the night. Same fixed time regardless of which
 * automation sent the morning SMS, same-day-rebooking included, even though that one's own SMS
 * fires at a variable time (3 hours after checkout) rather than a fixed 10am — the email leg's own
 * timing was never meant to track the SMS's send time, just to land in the evening either way.
 */
@Component
public class WinbackEmailFallbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(WinbackEmailFallbackScheduler.class);
    private static final List<String> AUTOMATION_KEYS = List.of(
            LapsedCustomerWinbackScheduler.AUTOMATION_KEY, RepeatCustomerWinbackScheduler.AUTOMATION_KEY,
            SameDayRebookingScheduler.AUTOMATION_KEY);
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final SmsMessageRepository smsMessageRepository;
    private final WinbackEmailSendRepository winbackEmailSendRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final SquareClientProvider squareClientProvider;
    private final SmsAutomationService automationService;
    private final PromoConfigService promoConfigService;
    private final ProviderVisitRepository providerVisitRepository;
    private final String publicBaseUrl;

    public WinbackEmailFallbackScheduler(SmsMessageRepository smsMessageRepository,
                                          WinbackEmailSendRepository winbackEmailSendRepository,
                                          MailchimpConfigRepository mailchimpConfigRepository,
                                          MailchimpEmailService mailchimpEmailService,
                                          MailchimpEmailTemplateService templateService,
                                          SquareClientProvider squareClientProvider,
                                          SmsAutomationService automationService,
                                          PromoConfigService promoConfigService,
                                          ProviderVisitRepository providerVisitRepository,
                                          @Value("${app.public-base-url}") String publicBaseUrl) {
        this.smsMessageRepository = smsMessageRepository;
        this.winbackEmailSendRepository = winbackEmailSendRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.squareClientProvider = squareClientProvider;
        this.automationService = automationService;
        this.promoConfigService = promoConfigService;
        this.providerVisitRepository = providerVisitRepository;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Scheduled(cron = "0 0 19 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "WinbackEmailFallbackScheduler_sendDueFollowUps", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
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
                    log.warn("Winback email fallback failed for sms_message {} (skipped, not retried): {}",
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
            log.warn("Winback email fallback skipped for business {} (Square unavailable this run): {}",
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

        // same_day_rebooking_discount's own coupon (REBOOK10, $10) is a different one from the two
        // winback automations' (WINBACK5, $5) — see PromoConfigService's own constants and
        // SmsAutomationRegistry's per-automation descriptions.
        String promoCode = SameDayRebookingScheduler.AUTOMATION_KEY.equals(automationKey)
                ? PromoConfigService.REBOOK_PROMO_CODE : PromoConfigService.WINBACK_PROMO_CODE;
        var promoTerms = promoConfigService.get(businessId, promoCode);
        String discountAmount = promoTerms.map(t -> PromoConfigService.formatDollars(t.discountCents())).orElse("$5");

        String givenName = Names.capitalizeFirst(
                square.customerGivenNames(List.of(customerId)).getOrDefault(customerId, null));
        String technician = lastTechnicianFirstName(businessId, customerId);

        String offerLabel = technician == null ? "Your next visit" : "Your next visit with " + technician;
        String technicianClause = technician == null ? "for your next visit" : "with " + technician;

        Map<String, String> vars = Map.of(
                "FNAME", givenName == null ? "there" : givenName,
                "DISCOUNT", discountAmount,
                "OFFER_LABEL", offerLabel,
                "TECHNICIAN_CLAUSE", technicianClause,
                "LINK", publicBaseUrl + "/r/" + sms.getClickToken());

        Optional<String> html = templateService.render(businessId, automationKey, vars);
        if (html.isEmpty()) {
            save(sms, customerId, email, WinbackEmailSend.STATE_SKIPPED_NO_TEMPLATE, null, null);
            return;
        }

        String subjectLine = "Last call, " + vars.get("FNAME") + " 💛";
        String previewText = "Your " + discountAmount + " off expires tonight";
        String campaignTitle = automationKey + " email follow-up — " + sms.getId();

        try {
            String campaignId = mailchimpEmailService.sendWinbackEmail(
                    config, email, subjectLine, previewText, campaignTitle, html.get());
            save(sms, customerId, email, WinbackEmailSend.STATE_SENT, campaignId, html.get());
        } catch (Exception e) {
            log.warn("Winback email send failed for sms_message {} (not retried): {}", sms.getId(), e.getMessage());
            save(sms, customerId, email, WinbackEmailSend.STATE_SEND_FAILED, null, null);
        }
    }

    /** Same-day best-effort re-derivation of who to name in the email — neither {@code sms_message}
     * nor the win-back send-log tables persist the technician from the morning's SMS, so this reads
     * the customer's own most recent completed visit fresh, same source those tables' own
     * eligibility queries used a few hours earlier. {@code null} if unresolvable — the templates
     * degrade to technician-free copy, same fallback {@link LapsedCustomerWinbackScheduler} uses. */
    private String lastTechnicianFirstName(Long businessId, String customerId) {
        List<ProviderVisit> visits = providerVisitRepository.findByBusinessIdAndCustomerIdOrderByServiceDateDesc(
                businessId, customerId, PageRequest.of(0, 1));
        if (visits.isEmpty()) {
            return null;
        }
        String name = Names.firstNameOnly(visits.get(0).getProviderName());
        return (name == null || name.isBlank()) ? null : name;
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
