package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SameDayRebookingSendRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Durable, DB-backed delayed-send poller for the {@code same_day_rebooking_discount} automation —
 * see openspec/changes/same-day-rebooking-discount design.md D1/D2/D3. Same 15s-poll-against-a-
 * {@code send_due_at} shape as {@code SmsReplyFlowScheduler}/{@code LeadFollowUpScheduler}, but a
 * distinct table/scheduler (see design.md D1 for why neither existing one is reused).
 *
 * <p>Bypasses {@link SmsTemplateRegistry}/{@link TwilioSmsService#sendTemplated} entirely — like
 * {@link CheckoutReviewReplyService}, this message needs a self-referencing click-tracked short
 * link generated up front, and its consent check is dual-source (marketing.contacts OR Square's
 * own "Text Subscribers" segment), which the shared marketing-consent-only gate doesn't cover.
 */
@Component
public class SameDayRebookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(SameDayRebookingScheduler.class);
    static final String AUTOMATION_KEY = "same_day_rebooking_discount";
    static final String TEMPLATE_KEY = "same_day_rebooking_nudge";
    /** Sent instead of {@link #TEMPLATE_KEY} when the customer has no marketing consent on file —
     * a plain appointment-reminder framing (no discount/offer language), which TCPA/CA law doesn't
     * gate behind marketing consent. The signed link is identical either way, so a click still
     * silently applies the same-day discount — see design.md D3 and sendNudge below. */
    static final String TEMPLATE_KEY_TRANSACTIONAL = "same_day_rebooking_reminder";

    private final SameDayRebookingSendRepository repository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final SmsConsentRepository consentRepository;
    private final RebookingProperties rebookingProperties;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final TechnicianNameResolver technicianNameResolver;
    private final SmsMessageTemplateService templateService;
    private final String publicBaseUrl;
    private final PromoConfigService promoConfigService;

    public SameDayRebookingScheduler(SameDayRebookingSendRepository repository, SquareClientProvider squareClientProvider,
                                      TwilioSmsConfigRepository twilioConfigs,
                                      SmsAutomationService automationService, SmsConsentRepository consentRepository,
                                      RebookingProperties rebookingProperties, SmsMessageLogService messageLogService,
                                      TwilioSmsConfigService configService, TwilioSmsClient client,
                                      TechnicianNameResolver technicianNameResolver, SmsMessageTemplateService templateService,
                                      @Value("${app.public-base-url}") String publicBaseUrl,
                                      PromoConfigService promoConfigService) {
        this.repository = repository;
        this.squareClientProvider = squareClientProvider;
        this.twilioConfigs = twilioConfigs;
        this.automationService = automationService;
        this.consentRepository = consentRepository;
        this.rebookingProperties = rebookingProperties;
        this.messageLogService = messageLogService;
        this.configService = configService;
        this.client = client;
        this.technicianNameResolver = technicianNameResolver;
        this.templateService = templateService;
        this.publicBaseUrl = publicBaseUrl;
        this.promoConfigService = promoConfigService;
    }

    // initialDelay: @Scheduled triggers can start firing before ApplicationRunners (incl.
    // SquareConnectionBootstrap's backfill) finish — without this, the very first tick after
    // every deploy hits "No Square connection configured" and logs an ERROR, self-healing only on
    // the next tick 15s later. A short delay gives that ApplicationRunner (a single fast DB
    // read/write) comfortable margin to finish first.
    //
    // Single lock covers the whole per-business loop below — still correct (no duplicate sends
    // across blue/green), just not maximally parallel across businesses; fine given today's
    // business count (same deliberate simplification as SmsReplyFlowScheduler, tasks.md 3.7).
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @SchedulerLock(name = "SameDayRebookingScheduler_sendDueRebookingNudges", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueRebookingNudges() {
        Instant now = Instant.now();
        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            SquareClient square;
            try {
                square = squareClientProvider.forBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Same-day-rebooking nudges skipped for business {} (will be retried at next scheduled run): {}",
                        businessId, e.getMessage());
                continue;
            }
            List<SameDayRebookingSend> due = repository.findByBusinessIdAndStateAndSendDueAtBefore(
                    businessId, SameDayRebookingSend.STATE_AWAITING_SEND, now);
            for (SameDayRebookingSend send : due) {
                process(send, now, square, businessId);
            }
        }
    }

    private void process(SameDayRebookingSend send, Instant now, SquareClient square, Long businessId) {
        // Never send an already-dead offer — see design.md D2's "checkout very late in the day"
        // edge case.
        if (send.getPromoExpiresAt().isBefore(now)) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_EXPIRED);
            return;
        }

        boolean upcoming;
        try {
            upcoming = hasUpcomingAppointment(send.getSquareCustomerId(), square);
        } catch (RuntimeException ex) {
            // Fails closed, same as LeadFollowUpScheduler: a transient Square failure means
            // "don't know," not "assume unbooked" — no row update, retried next poll tick.
            log.warn("Failed to check upcoming Square bookings for same-day-rebooking send {} ({}); retrying next poll",
                    send.getId(), send.getPhoneNumber(), ex);
            return;
        }
        if (upcoming) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_BOOKED);
            return;
        }

        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_DISABLED);
            return;
        }

        // A customer who ever rated a low star count on the checkout-review-request automation
        // is never re-approached with this win-back nudge, regardless of how long ago that was or
        // how this particular visit went — see negative-feedback-tracking design.
        if (messageLogService.hasNegativeFeedback(businessId, send.getPhoneNumber())) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
            return;
        }

        var promoTerms = promoConfigService.get(businessId, PromoConfigService.REBOOK_PROMO_CODE);
        if (promoTerms.isEmpty()) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_PROMO_NOT_CONFIGURED);
            return;
        }

        sendNudge(send, hasConsent(send, square), businessId, promoTerms.get());
        save(send, SameDayRebookingSend.STATE_SENT);
    }

    /** Live check for any not-cancelled, not-yet-happened Square appointment — same helper
     * {@code lead_follow_up} uses, but the customer id here is already known from the triggering
     * order, no phone-number-lookup fallback needed. */
    private boolean hasUpcomingAppointment(String customerId, SquareClient square) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return square.bookingsForCustomer(customerId, Instant.now()).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today));
    }

    /** Consent from *either* source is sufficient — see design.md D3. */
    private boolean hasConsent(SameDayRebookingSend send, SquareClient square) {
        if (consentRepository.hasMarketingConsent(send.getPhoneNumber())) {
            return true;
        }
        String segmentId = rebookingProperties.getConsentSegmentId();
        if (segmentId == null || segmentId.isBlank()) {
            return false;
        }
        return square.customerSegmentIds(send.getSquareCustomerId()).contains(segmentId);
    }

    private void sendNudge(SameDayRebookingSend send, boolean consented, Long businessId, PromoConfigService.PromoTerms promoTerms) {
        String clickToken = messageLogService.generateUniqueClickToken();
        long expEpochSeconds = send.getPromoExpiresAt().getEpochSecond();
        // Reconstructed deterministically by ShortLinkController at click time — see
        // RebookingPromoSigner and design.md D8/D9. No signature stored here; it's recomputed.
        // Identical regardless of consent — the discount is applied on click either way, it's
        // only the SMS wording that differs (see class doc).
        String linkTarget = "REBOOK:" + expEpochSeconds;
        String templateKey = consented ? TEMPLATE_KEY : TEMPLATE_KEY_TRANSACTIONAL;
        SmsMessage reserved = messageLogService.logOutboundWithLink(
                businessId, templateKey, AUTOMATION_KEY, send.getPhoneNumber(), "", false, "pending", null, linkTarget, clickToken);

        String shortLink = publicBaseUrl + "/r/" + clickToken;
        String technician = technicianNameResolver
                .resolveForCustomer(businessId, send.getSquareCustomerId(), Instant.now())
                .orElse(null);
        boolean hasTechnician = technician != null && !technician.isBlank();
        Map<String, String> vars = consented
                ? Map.of("spotClause", hasTechnician ? "want to lock in your next spot with " + technician
                        : "want to lock in your next spot",
                        "discountAmount", PromoConfigService.formatDollars(promoTerms.discountCents()), "link", shortLink)
                : Map.of("urgencyClause", hasTechnician ? technician + "'s spots are filling up fast this time of year"
                        : "Spots are filling up fast this time of year", "link", shortLink);
        String body = templateService.render(businessId, templateKey, vars);

        TwilioSmsConfig config = configService.get(businessId);
        if (!config.isConfigured()) {
            log.info("{} skipped — Twilio credentials not configured", templateKey);
            updateReserved(reserved, body, false, "not_configured", null);
            return;
        }
        try {
            String twilioMessageSid = client.send(config, send.getPhoneNumber(), body);
            updateReserved(reserved, body, true, null, twilioMessageSid);
        } catch (Exception e) {
            log.warn("{} send failed (caller unaffected): {}", templateKey, e.getMessage());
            updateReserved(reserved, body, false, "send_failed", null);
        }
    }

    private void updateReserved(SmsMessage reserved, String body, boolean sent, String reason, String twilioMessageSid) {
        reserved.setBody(body);
        reserved.setStatus(sent ? "SENT" : "NOT_SENT");
        reserved.setReason(reason);
        reserved.setTwilioMessageSid(twilioMessageSid);
        messageLogService.save(reserved);
    }

    private void save(SameDayRebookingSend send, String state) {
        send.setState(state);
        repository.save(send);
    }
}
