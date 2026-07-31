package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SameDayRebookingSendRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

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

    private final SameDayRebookingSendRepository repository;
    private final SquareClient square;
    private final SmsAutomationService automationService;
    private final SmsConsentRepository consentRepository;
    private final RebookingProperties rebookingProperties;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final String publicBaseUrl;

    public SameDayRebookingScheduler(SameDayRebookingSendRepository repository, SquareClient square,
                                      SmsAutomationService automationService, SmsConsentRepository consentRepository,
                                      RebookingProperties rebookingProperties, SmsMessageLogService messageLogService,
                                      TwilioSmsConfigService configService, TwilioSmsClient client,
                                      @Value("${app.public-base-url}") String publicBaseUrl) {
        this.repository = repository;
        this.square = square;
        this.automationService = automationService;
        this.consentRepository = consentRepository;
        this.rebookingProperties = rebookingProperties;
        this.messageLogService = messageLogService;
        this.configService = configService;
        this.client = client;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Scheduled(fixedDelay = 15_000)
    public void sendDueRebookingNudges() {
        Instant now = Instant.now();
        List<SameDayRebookingSend> due = repository.findByStateAndSendDueAtBefore(
                SameDayRebookingSend.STATE_AWAITING_SEND, now);
        for (SameDayRebookingSend send : due) {
            process(send, now);
        }
    }

    private void process(SameDayRebookingSend send, Instant now) {
        // Never send an already-dead offer — see design.md D2's "checkout very late in the day"
        // edge case.
        if (send.getPromoExpiresAt().isBefore(now)) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_EXPIRED);
            return;
        }

        boolean upcoming;
        try {
            upcoming = hasUpcomingAppointment(send.getSquareCustomerId());
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

        if (!automationService.isEnabled(AUTOMATION_KEY)) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_DISABLED);
            return;
        }

        if (!hasConsent(send)) {
            save(send, SameDayRebookingSend.STATE_SKIPPED_NO_CONSENT);
            return;
        }

        sendNudge(send);
        save(send, SameDayRebookingSend.STATE_SENT);
    }

    /** Live check for any not-cancelled, not-yet-happened Square appointment — same helper
     * {@code lead_follow_up} uses, but the customer id here is already known from the triggering
     * order, no phone-number-lookup fallback needed. */
    private boolean hasUpcomingAppointment(String customerId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return square.bookingsForCustomer(customerId, Instant.now()).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today));
    }

    /** Consent from *either* source is sufficient — see design.md D3. */
    private boolean hasConsent(SameDayRebookingSend send) {
        if (consentRepository.hasMarketingConsent(send.getPhoneNumber())) {
            return true;
        }
        String segmentId = rebookingProperties.getConsentSegmentId();
        if (segmentId == null || segmentId.isBlank()) {
            return false;
        }
        return square.customerSegmentIds(send.getSquareCustomerId()).contains(segmentId);
    }

    private void sendNudge(SameDayRebookingSend send) {
        String clickToken = messageLogService.generateUniqueClickToken();
        long expEpochSeconds = send.getPromoExpiresAt().getEpochSecond();
        // Reconstructed deterministically by ShortLinkController at click time — see
        // RebookingPromoSigner and design.md D8/D9. No signature stored here; it's recomputed.
        String linkTarget = "REBOOK:" + expEpochSeconds;
        SmsMessage reserved = messageLogService.logOutboundWithLink(
                TEMPLATE_KEY, AUTOMATION_KEY, send.getPhoneNumber(), "", false, "pending", null, linkTarget, clickToken);

        String shortLink = publicBaseUrl + "/r/" + clickToken;
        String name = send.getCustomerName();
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        String body = greeting + " So glad you loved your visit today 💅 Rebook before midnight and take "
                + "$10 off your next appointment (min. $99 service total) — grab your spot: " + shortLink
                + " — AK.LUX.NAILS";

        TwilioSmsConfig config = configService.get();
        if (!config.isConfigured()) {
            log.info("same_day_rebooking_nudge skipped — Twilio credentials not configured");
            updateReserved(reserved, body, false, "not_configured", null);
            return;
        }
        try {
            String twilioMessageSid = client.send(config, send.getPhoneNumber(), body);
            updateReserved(reserved, body, true, null, twilioMessageSid);
        } catch (Exception e) {
            log.warn("same_day_rebooking_nudge send failed (caller unaffected): {}", e.getMessage());
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
