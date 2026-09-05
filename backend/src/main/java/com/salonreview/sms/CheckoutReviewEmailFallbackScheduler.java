package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Email follow-up for the checkout-review-request ("Post-checkout satisfaction request")
 * automation — SMS goes out first (see {@link SmsReplyFlowScheduler}, 2 minutes after checkout,
 * with a 24h reply window); this scheduler fires only for a flow that reached {@link
 * SmsReplyFlow#STATE_EXPIRED} (24h, no reply at all) — never a parallel/simultaneous channel, and
 * never for a flow the customer already answered by text.
 *
 * <p>Timing is deliberately anchored to the existing 24h expiry rather than a shorter delay: a
 * live check against this account's own reply-timing history (113 real replies) found the 95th
 * percentile at ~9 hours, comfortably inside the 24h window already in place — sending sooner
 * would risk a duplicate ask to the ~5-10% of genuine repliers who are simply slow, not
 * non-responders (owner direction 2026-09-05).
 *
 * <p>Reuses {@link WinbackEmailSend} (not a dedicated table) — same shape, same Mailchimp-activity-
 * sync/metrics plumbing ({@code MailchimpActivitySyncScheduler}, {@code SmsAutomationService})
 * every other email-fallback automation already gets, keyed off {@link SmsReplyFlow#getAskSmsMessageId()}
 * (the flow's own "ask" SMS row) instead of {@code WinbackEmailFallbackScheduler}'s own
 * already-clicked-or-replied SMS row — this automation's SMS never carries a link at all, so
 * "clicked" isn't a meaningful gate here; "reached EXPIRED" already is one.
 *
 * <p>The email itself carries five pre-signed rating links (Very Satisfied .. Very Dissatisfied —
 * see {@link CheckoutReviewRatingSigner}/{@link CheckoutReviewRatingController}), each resolving
 * straight to the right destination in one click rather than asking for yet another reply.
 */
@Component
public class CheckoutReviewEmailFallbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReviewEmailFallbackScheduler.class);
    private static final String AUTOMATION_KEY = CheckoutReviewReplyService.AUTOMATION_KEY;

    /** Generous — a customer might open the email and click days after it's sent. The flow's own
     * state (COMPLETED once acted on) is the real gate against acting twice; this is only a
     * backstop against a link working indefinitely. */
    private static final Duration LINK_VALIDITY = Duration.ofDays(30);

    private final SmsReplyFlowRepository replyFlowRepository;
    private final WinbackEmailSendRepository winbackEmailSendRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final SquareClientProvider squareClientProvider;
    private final SmsAutomationService automationService;
    private final ProviderRepository providerRepository;
    private final CheckoutReviewRatingSigner ratingSigner;
    private final String publicBaseUrl;

    public CheckoutReviewEmailFallbackScheduler(SmsReplyFlowRepository replyFlowRepository,
                                                 WinbackEmailSendRepository winbackEmailSendRepository,
                                                 MailchimpConfigRepository mailchimpConfigRepository,
                                                 MailchimpEmailService mailchimpEmailService,
                                                 MailchimpEmailTemplateService templateService,
                                                 SquareClientProvider squareClientProvider,
                                                 SmsAutomationService automationService,
                                                 ProviderRepository providerRepository,
                                                 CheckoutReviewRatingSigner ratingSigner,
                                                 @Value("${app.public-base-url}") String publicBaseUrl) {
        this.replyFlowRepository = replyFlowRepository;
        this.winbackEmailSendRepository = winbackEmailSendRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.squareClientProvider = squareClientProvider;
        this.automationService = automationService;
        this.providerRepository = providerRepository;
        this.ratingSigner = ratingSigner;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "CheckoutReviewEmailFallbackScheduler_sendDueFollowUps", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueFollowUps() {
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            for (SmsReplyFlow flow : replyFlowRepository.findByBusinessIdAndAutomationKeyAndStateAndAskSmsMessageIdIsNotNull(
                    businessId, AUTOMATION_KEY, SmsReplyFlow.STATE_EXPIRED)) {
                try {
                    process(flow, config);
                } catch (RuntimeException e) {
                    log.warn("Checkout-review email fallback failed for flow {} (skipped, not retried): {}",
                            flow.getId(), e.getMessage(), e);
                }
            }
        }
    }

    private void process(SmsReplyFlow flow, MailchimpConfig config) {
        if (winbackEmailSendRepository.existsBySmsMessageId(flow.getAskSmsMessageId())) {
            return; // belt-and-suspenders; the state=EXPIRED query shouldn't reoffer the same flow twice
        }
        Long businessId = flow.getBusinessId();
        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            save(flow, null, null, WinbackEmailSend.STATE_SKIPPED_DISABLED, null, null);
            return;
        }

        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Checkout-review email fallback skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return; // no row saved — acceptable for a bonus fallback channel, not core (same as WinbackEmailFallbackScheduler)
        }

        String customerId = flow.getSquareCustomerId() != null ? flow.getSquareCustomerId()
                : square.customerIdsForPhone(flow.getPhoneNumber()).stream().findFirst().orElse(null);
        if (customerId == null) {
            save(flow, null, null, WinbackEmailSend.STATE_SKIPPED_NO_EMAIL, null, null);
            return;
        }
        String email = square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            save(flow, customerId, null, WinbackEmailSend.STATE_SKIPPED_NO_EMAIL, null, null);
            return;
        }

        long expEpochSeconds = Instant.now().plus(LINK_VALIDITY).getEpochSecond();
        Map<String, String> ratingLinks = new HashMap<>();
        for (int rating = 1; rating <= 5; rating++) {
            String signature = ratingSigner.sign(flow.getId(), rating, expEpochSeconds);
            if (signature == null) {
                save(flow, customerId, email, WinbackEmailSend.STATE_SKIPPED_NOT_CONFIGURED, null, null);
                return; // signing secret not configured — never send a link that can't verify
            }
            ratingLinks.put("LINK_" + rating, publicBaseUrl + "/api/public/checkout-review/rate?flow=" + flow.getId()
                    + "&rating=" + rating + "&exp=" + expEpochSeconds + "&sig=" + signature);
        }

        String givenName = Names.capitalizeFirst(flow.getCustomerName());
        String technician = flow.getProviderId() == null ? null
                : providerRepository.findByIdAndBusinessId(flow.getProviderId(), businessId)
                        .map(Provider::getDisplayName).map(Names::firstNameOnly).orElse(null);

        Map<String, String> vars = new HashMap<>(ratingLinks);
        vars.put("FNAME", givenName == null ? "there" : givenName);
        vars.put("TECHNICIAN_CLAUSE", technician == null ? "" : " with " + technician);

        Optional<String> html = templateService.render(businessId, AUTOMATION_KEY, vars);
        if (html.isEmpty()) {
            save(flow, customerId, email, WinbackEmailSend.STATE_SKIPPED_NO_TEMPLATE, null, null);
            return;
        }

        String subjectLine = "How was your visit, " + vars.get("FNAME") + "?";
        String previewText = "One tap tells us everything — takes 2 seconds";
        String campaignTitle = AUTOMATION_KEY + " email follow-up — flow " + flow.getId();

        try {
            String campaignId = mailchimpEmailService.sendWinbackEmail(
                    config, email, subjectLine, previewText, campaignTitle, html.get());
            save(flow, customerId, email, WinbackEmailSend.STATE_SENT, campaignId, html.get());
        } catch (Exception e) {
            log.warn("Checkout-review email send failed for flow {} (not retried): {}", flow.getId(), e.getMessage());
            save(flow, customerId, email, WinbackEmailSend.STATE_SEND_FAILED, null, null);
        }
    }

    private void save(SmsReplyFlow flow, String squareCustomerId, String email, String state, String campaignId,
                       String contentHtml) {
        winbackEmailSendRepository.save(WinbackEmailSend.builder()
                .businessId(flow.getBusinessId())
                .automationKey(AUTOMATION_KEY)
                .smsMessageId(flow.getAskSmsMessageId())
                .squareCustomerId(squareCustomerId == null ? "" : squareCustomerId)
                .emailAddress(email)
                .state(state)
                .mailchimpCampaignId(campaignId)
                .contentHtml(contentHtml)
                .build());
    }
}
