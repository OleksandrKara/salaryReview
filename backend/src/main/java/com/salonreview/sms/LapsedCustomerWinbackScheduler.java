package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.LapsedCustomerWinbackSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.LapsedCustomerWinbackSendRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Once-daily win-back nudge for customers with exactly one all-time visit, 21–35 days ago — see
 * openspec/changes/lapsed-customer-winback-automation design.md. Daily cron, not a 15-second poll
 * (see D1) — the eligibility window is 15 days wide, nowhere near the minutes-scale deadlines the
 * other schedulers in this package chase.
 *
 * <p>Bypasses {@link SmsTemplateRegistry}/{@link TwilioSmsService#sendTemplated} entirely, same
 * reasoning as {@link SameDayRebookingScheduler}: needs a self-referencing click-tracked short
 * link generated up front, and its consent check is dual-source (marketing.contacts OR Square's
 * own "Text Subscribers" segment), which the shared marketing-consent-only gate doesn't cover.
 */
@Component
public class LapsedCustomerWinbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(LapsedCustomerWinbackScheduler.class);
    static final String AUTOMATION_KEY = "lapsed_customer_winback";
    static final String TEMPLATE_KEY = "lapsed_customer_winback_nudge";
    /** Sent instead of {@link #TEMPLATE_KEY} when the customer has no marketing consent on file —
     * no discount language, same reasoning as {@code SameDayRebookingScheduler}'s own transactional
     * variant. The link is identical either way; the $5 coupon still silently applies on click —
     * see design.md D8. */
    static final String TEMPLATE_KEY_TRANSACTIONAL = "lapsed_customer_winback_reminder";

    /** DST-safe — always resolves to the salon's real local midnight, not a fixed UTC offset. Same
     * zone {@code SameDayRebookingTriggerService} uses — see design.md D10. */
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final LapsedCustomerWinbackEligibilityRepository eligibilityRepository;
    private final LapsedCustomerWinbackSendRepository sendRepository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final SmsConsentRepository consentRepository;
    private final RebookingProperties rebookingProperties;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final String publicBaseUrl;

    public LapsedCustomerWinbackScheduler(LapsedCustomerWinbackEligibilityRepository eligibilityRepository,
                                           LapsedCustomerWinbackSendRepository sendRepository,
                                           SquareClientProvider squareClientProvider, TwilioSmsConfigRepository twilioConfigs,
                                           SmsAutomationService automationService, SmsConsentRepository consentRepository,
                                           RebookingProperties rebookingProperties, SmsMessageLogService messageLogService,
                                           TwilioSmsConfigService configService, TwilioSmsClient client,
                                           @Value("${app.public-base-url}") String publicBaseUrl) {
        this.eligibilityRepository = eligibilityRepository;
        this.sendRepository = sendRepository;
        this.squareClientProvider = squareClientProvider;
        this.twilioConfigs = twilioConfigs;
        this.automationService = automationService;
        this.consentRepository = consentRepository;
        this.rebookingProperties = rebookingProperties;
        this.messageLogService = messageLogService;
        this.configService = configService;
        this.client = client;
        this.publicBaseUrl = publicBaseUrl;
    }

    // zone is mandatory — the container runs on UTC (confirmed via `date` on
    // salonreview-backend-blue), so an unzoned cron fires at 10:00 UTC = 3am Pacific, not 10am
    // Pacific as intended. This is what actually happened in production on 2026-08-07 (16 real
    // sends at 10:00:0x UTC). Every future @Scheduled(cron=...) in this codebase must set an
    // explicit zone for the same reason — never rely on the host's default timezone.
    //
    // Single lock covers the whole per-business loop below — same deliberate simplification as
    // SmsReplyFlowScheduler (tasks.md 3.7).
    @Scheduled(cron = "0 0 10 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "LapsedCustomerWinbackScheduler_sendDueWinbacks", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sendDueWinbacks() {
        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            SquareClient square;
            try {
                square = squareClientProvider.forBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Lapsed-customer-winback run skipped for business {} (will be retried at next scheduled run): {}",
                        businessId, e.getMessage());
                continue;
            }
            for (LapsedCustomerWinbackEligibilityRepository.EligibleCustomer customer
                    : eligibilityRepository.findEligibleCustomers(businessId)) {
                if (sendRepository.existsByBusinessIdAndSquareCustomerId(businessId, customer.squareCustomerId())) {
                    continue; // belt-and-suspenders vs. the eligibility query's own NOT EXISTS
                }
                process(customer, square, businessId);
            }
        }
    }

    private void process(LapsedCustomerWinbackEligibilityRepository.EligibleCustomer customer, SquareClient square,
                          Long businessId) {
        String phoneNumber = square.customerPhone(customer.squareCustomerId());
        if (phoneNumber == null || phoneNumber.isBlank()) {
            save(businessId, customer, null, null, LapsedCustomerWinbackSend.STATE_SKIPPED_UNRESOLVED);
            return;
        }

        if (messageLogService.hasNegativeFeedback(businessId, phoneNumber)) {
            save(businessId, customer, phoneNumber, null, LapsedCustomerWinbackSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
            return;
        }

        boolean upcoming;
        try {
            upcoming = hasUpcomingAppointment(customer.squareCustomerId(), square);
        } catch (RuntimeException ex) {
            // Fails closed, same as every other scheduler in this package: a transient Square
            // failure means "don't know," not "assume unbooked" — no row written, retried on the
            // next day's run (the eligibility query's own NOT EXISTS naturally picks it back up).
            log.warn("Failed to check upcoming Square bookings for lapsed-customer-winback candidate {} ({}); retrying next run",
                    customer.squareCustomerId(), phoneNumber, ex);
            return;
        }
        if (upcoming) {
            save(businessId, customer, phoneNumber, null, LapsedCustomerWinbackSend.STATE_SKIPPED_BOOKED);
            return;
        }

        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            save(businessId, customer, phoneNumber, null, LapsedCustomerWinbackSend.STATE_SKIPPED_DISABLED);
            return;
        }

        Instant promoExpiresAt = endOfTodayInSalonZone();
        // Claim the row BEFORE sending, not after — found live 2026-08-18 (a duplicate-key error
        // on lapsed_customer_winback_send_customer_idx at the exact cron second, most likely two
        // overlapping scheduler runs — see this class's own doc on ShedLock). Claiming first means
        // the unique constraint can only ever reject a *second* attempt before any SMS goes out,
        // never after — so the worst case becomes "processed once, logged once," not "a customer
        // silently gets the nudge twice because the loser of the race already sent before losing."
        if (!claim(businessId, customer, phoneNumber, promoExpiresAt)) {
            log.info("Lapsed-customer-winback: {} already claimed by a concurrent run — skipping to avoid a duplicate send",
                    customer.squareCustomerId());
            return;
        }
        String givenName = square.customerGivenNames(List.of(customer.squareCustomerId())).get(customer.squareCustomerId());
        sendNudge(customer, phoneNumber, givenName, promoExpiresAt, hasConsent(phoneNumber, customer.squareCustomerId(), square), businessId);
    }

    /** Inserts the {@code STATE_SENT} row first — true if this call won the race (safe to send),
     * false if a concurrent run already claimed this customer (the unique index on
     * {@code square_customer_id} enforces it; see {@link #process} for why this order matters). */
    private boolean claim(Long businessId, LapsedCustomerWinbackEligibilityRepository.EligibleCustomer customer,
                           String phoneNumber, Instant promoExpiresAt) {
        try {
            save(businessId, customer, phoneNumber, promoExpiresAt, LapsedCustomerWinbackSend.STATE_SENT);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false;
        }
    }

    /** Live check for any not-cancelled, not-yet-happened Square appointment — same helper every
     * other scheduler in this package uses. */
    private boolean hasUpcomingAppointment(String customerId, SquareClient square) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return square.bookingsForCustomer(customerId, Instant.now()).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today));
    }

    /** Consent from *either* source is sufficient — same dual-source check
     * {@code SameDayRebookingScheduler.hasConsent} uses, reusing the same consent-segment id (no
     * new Square segment needed — see design.md D8). */
    private boolean hasConsent(String phoneNumber, String squareCustomerId, SquareClient square) {
        if (consentRepository.hasMarketingConsent(phoneNumber)) {
            return true;
        }
        String segmentId = rebookingProperties.getConsentSegmentId();
        if (segmentId == null || segmentId.isBlank()) {
            return false;
        }
        return square.customerSegmentIds(squareCustomerId).contains(segmentId);
    }

    /** End of "today" (start of tomorrow) in the salon's local zone — see design.md D10. Computed
     * fresh at send time, not derived from the customer's visit date. */
    private static Instant endOfTodayInSalonZone() {
        return ZonedDateTime.now(SALON_ZONE).toLocalDate().plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
    }

    private void sendNudge(LapsedCustomerWinbackEligibilityRepository.EligibleCustomer customer, String phoneNumber,
                            String rawGivenName, Instant promoExpiresAt, boolean consented, Long businessId) {
        String clickToken = messageLogService.generateUniqueClickToken();
        long expEpochSeconds = promoExpiresAt.getEpochSecond();
        // Reconstructed deterministically by ShortLinkController at click time — see
        // RebookingPromoSigner and design.md D9. No signature stored here; it's recomputed.
        // Identical regardless of consent — the coupon is applied on click either way, it's only
        // the SMS wording that differs (see class doc).
        String linkTarget = "WINBACK:" + expEpochSeconds;
        String templateKey = consented ? TEMPLATE_KEY : TEMPLATE_KEY_TRANSACTIONAL;
        SmsMessage reserved = messageLogService.logOutboundWithLink(
                businessId, templateKey, AUTOMATION_KEY, phoneNumber, "", false, "pending", null, linkTarget, clickToken);

        String shortLink = publicBaseUrl + "/r/" + clickToken;
        String name = Names.capitalizeFirst(rawGivenName);
        // provider_visit.provider_name is the free-text Square team-member display name (e.g.
        // "Susan Alieva") — never send a technician's last name to a customer, same rule
        // TechnicianNameResolver already enforces for same_day_rebooking_discount. Missed here
        // originally; caused real sends with full names on 2026-08-07 (see sms_message ids
        // 109-124) before this fix.
        String technician = Names.firstNameOnly(customer.technicianName());
        String body = consented
                ? marketingBody(name, technician, shortLink)
                : transactionalBody(name, technician, shortLink);

        TwilioSmsConfig config = configService.get(businessId);
        if (!config.isConfigured()) {
            log.info("{} skipped — Twilio credentials not configured", templateKey);
            updateReserved(reserved, body, false, "not_configured", null);
            return;
        }
        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            updateReserved(reserved, body, true, null, twilioMessageSid);
        } catch (Exception e) {
            log.warn("{} send failed (caller unaffected): {}", templateKey, e.getMessage());
            updateReserved(reserved, body, false, "send_failed", null);
        }
    }

    /** {@code technician} is that customer's one-and-only visit's own provider name (see D5), null
     * if somehow blank — falls back to a technician-less "spots are filling up fast" framing rather
     * than an empty mention. Uses the technician's own name possessively ("Susan's schedule") so no
     * pronoun is ever needed — no "their"/"her" ambiguity to resolve either way (though if a
     * pronoun is ever wanted, "her" is confirmed correct — every current AK.LUX.NAILS technician is
     * a woman, see the sms_technician_gender memory note). The $99-minimum condition lives on the
     * linked promo page, not spelled out here — same convention
     * {@code same_day_rebooking_discount}'s copy already follows. */
    private static String marketingBody(String name, String technician, String shortLink) {
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        String body = (technician == null || technician.isBlank())
                ? "It's been 3+ weeks since your last visit. Spots are filling up fast right now, "
                        + "grabbed you $5 off if you book today"
                : "It's been 3+ weeks since your last visit and " + technician + "'s schedule is "
                        + "almost full. Grabbed you $5 off if you book today";
        return greeting + " It's Lucy from AK.LUX.NAILS 💛 " + body + ": " + shortLink + " -Lucy";
    }

    /** Same {@code technician}/pronoun reasoning as {@link #marketingBody}, no discount language —
     * see design.md D8. */
    private static String transactionalBody(String name, String technician, String shortLink) {
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        String body = (technician == null || technician.isBlank())
                ? "It's been 3+ weeks since your last visit. Spots are filling up fast right now, "
                        + "want me to grab you a spot"
                : "It's been 3+ weeks since your last visit and " + technician + "'s schedule is "
                        + "almost full. Want me to grab you a spot";
        return greeting + " It's Lucy from AK.LUX.NAILS 💛 " + body + "? " + shortLink + " -Lucy";
    }

    private void updateReserved(SmsMessage reserved, String body, boolean sent, String reason, String twilioMessageSid) {
        reserved.setBody(body);
        reserved.setStatus(sent ? "SENT" : "NOT_SENT");
        reserved.setReason(reason);
        reserved.setTwilioMessageSid(twilioMessageSid);
        messageLogService.save(reserved);
    }

    private void save(Long businessId, LapsedCustomerWinbackEligibilityRepository.EligibleCustomer customer, String phoneNumber,
                       Instant promoExpiresAt, String state) {
        sendRepository.save(LapsedCustomerWinbackSend.builder()
                .businessId(businessId)
                .squareCustomerId(customer.squareCustomerId())
                .phoneNumber(phoneNumber)
                .visitDate(customer.visitDate())
                .promoExpiresAt(promoExpiresAt)
                .state(state)
                .build());
    }
}
