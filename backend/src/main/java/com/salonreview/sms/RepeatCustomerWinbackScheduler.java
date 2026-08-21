package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.RepeatCustomerWinbackSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.RepeatCustomerWinbackSendRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import com.salonreview.util.PhoneNumbers;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Once-daily win-back nudge for customers with 2+ completed all-time visits who are 40+ days
 * overdue by their own last visit — the "next" retention automation after
 * {@link LapsedCustomerWinbackScheduler} (which only covers exactly-one-visit customers). The
 * 40-day threshold comes from an independent test of 30/35/40/45/50/60-day alternatives against
 * this salon's real visit #2 → #3 churn behavior: by day 40, roughly 81% of customers who return
 * naturally already have, without waiting so long that the trigger reads as an "already-lost"
 * formality.
 *
 * <p>Reuses {@code lapsed_customer_winback}'s own $5-off/$99-minimum WINBACK5 promo (same Square
 * customer-group/pricing-rule, same {@code WINBACK:<epochSeconds>} link-target shape resolved by
 * {@link ShortLinkController}) rather than standing up a separate one — the two automations offer
 * an identical coupon, and Square pricing rules are amount-specific, not parameterizable per
 * automation, so there's nothing to gain from a second group/rule for the same $5/$99 terms. Like
 * {@code lapsed_customer_winback}, marketing-consent-gated: a consented customer's text mentions
 * the $5 off, an unconsented one gets a plain "want a spot?" nudge with no discount language, but
 * the link is identical either way and the coupon silently applies on click regardless — see
 * {@link #hasConsent}.
 *
 * <p>Daily cron, not a fast poll — same reasoning as {@link LapsedCustomerWinbackScheduler}: the
 * eligibility window only moves day-to-day, nowhere near the minutes-scale deadlines the reply-flow
 * schedulers chase.
 *
 * <p>Bypasses {@link SmsTemplateRegistry}/{@link TwilioSmsService#sendTemplated} for the same
 * reason {@code LapsedCustomerWinbackScheduler} and {@code SameDayRebookingScheduler} do: this send
 * carries a self-referencing click-tracked short link generated per-send, which the registry's
 * fixed-string templates don't support. Because of that, it also does its own
 * {@link BlockedNumberRepository} check — {@link TwilioSmsService} is normally the single choke
 * point for that (see V61), but every hand-rolled sender in this package has to repeat it.
 */
@Component
public class RepeatCustomerWinbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(RepeatCustomerWinbackScheduler.class);
    static final String AUTOMATION_KEY = "repeat_customer_winback";
    static final String TEMPLATE_KEY = "repeat_customer_winback_nudge";
    /** Sent instead of {@link #TEMPLATE_KEY} when the customer has no marketing consent on file —
     * no discount language, same reasoning/pattern as {@code LapsedCustomerWinbackScheduler}'s own
     * transactional variant. The link is identical either way; the $5 coupon still silently
     * applies on click — see class doc. */
    static final String TEMPLATE_KEY_TRANSACTIONAL = "repeat_customer_winback_reminder";

    /** No SENT row within this many days of "now" is what makes a customer eligible again — see
     * the eligibility query's own matching 60-day window. Kept here too as the belt-and-suspenders
     * cutoff, same pattern {@code LapsedCustomerWinbackScheduler} uses for its own (permanent)
     * exclusion. */
    private static final long COOLDOWN_DAYS = 60;

    /** DST-safe — see design rationale on every other scheduler in this package that computes
     * "today" in the salon's own timezone rather than the container's (UTC). */
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final RepeatCustomerWinbackEligibilityRepository eligibilityRepository;
    private final RepeatCustomerWinbackSendRepository sendRepository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final SmsConsentRepository consentRepository;
    private final RebookingProperties rebookingProperties;
    private final SmsMessageLogService messageLogService;
    private final BlockedNumberRepository blockedNumberRepository;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final SmsMessageTemplateService templateService;
    private final String publicBaseUrl;
    private final BusinessRepository businessRepository;
    private final PromoConfigService promoConfigService;

    public RepeatCustomerWinbackScheduler(RepeatCustomerWinbackEligibilityRepository eligibilityRepository,
                                           RepeatCustomerWinbackSendRepository sendRepository,
                                           SquareClientProvider squareClientProvider, TwilioSmsConfigRepository twilioConfigs,
                                           SmsAutomationService automationService, SmsConsentRepository consentRepository,
                                           RebookingProperties rebookingProperties, SmsMessageLogService messageLogService,
                                           BlockedNumberRepository blockedNumberRepository, TwilioSmsConfigService configService,
                                           TwilioSmsClient client, SmsMessageTemplateService templateService,
                                           @Value("${app.public-base-url}") String publicBaseUrl,
                                           BusinessRepository businessRepository, PromoConfigService promoConfigService) {
        this.eligibilityRepository = eligibilityRepository;
        this.sendRepository = sendRepository;
        this.squareClientProvider = squareClientProvider;
        this.twilioConfigs = twilioConfigs;
        this.automationService = automationService;
        this.consentRepository = consentRepository;
        this.rebookingProperties = rebookingProperties;
        this.messageLogService = messageLogService;
        this.blockedNumberRepository = blockedNumberRepository;
        this.configService = configService;
        this.client = client;
        this.templateService = templateService;
        this.publicBaseUrl = publicBaseUrl;
        this.businessRepository = businessRepository;
        this.promoConfigService = promoConfigService;
    }

    // zone is mandatory here too — see LapsedCustomerWinbackScheduler's own comment on the
    // 2026-08-07 unzoned-cron incident. Every @Scheduled(cron=...) in this codebase must set an
    // explicit zone.
    //
    // Single lock covers the whole per-business loop below — same deliberate simplification as
    // SmsReplyFlowScheduler (tasks.md 3.7).
    @Scheduled(cron = "0 0 10 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "RepeatCustomerWinbackScheduler_sendDueWinbacks", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sendDueWinbacks() {
        Instant cooldownCutoff = Instant.now().minus(COOLDOWN_DAYS, ChronoUnit.DAYS);
        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            SquareClient square;
            try {
                square = squareClientProvider.forBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Repeat-customer-winback run skipped for business {} (will be retried at next scheduled run): {}",
                        businessId, e.getMessage());
                continue;
            }
            for (RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer
                    : eligibilityRepository.findEligibleCustomers(businessId)) {
                if (sendRepository.existsByBusinessIdAndSquareCustomerIdAndStateAndCreatedAtAfter(
                        businessId, customer.squareCustomerId(), RepeatCustomerWinbackSend.STATE_SENT, cooldownCutoff)) {
                    continue; // belt-and-suspenders vs. the eligibility query's own NOT EXISTS
                }
                process(customer, square, businessId);
            }
        }
    }

    private void process(RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer, SquareClient square,
                          Long businessId) {
        int daysSinceLastVisit = (int) ChronoUnit.DAYS.between(customer.lastVisitDate(), LocalDate.now(SALON_ZONE));
        boolean providerChanged = !Objects.equals(customer.lastProvider(), customer.previousProvider());

        String phoneNumber = square.customerPhone(customer.squareCustomerId());
        if (phoneNumber == null || phoneNumber.isBlank()) {
            save(businessId, customer, null, daysSinceLastVisit, providerChanged, null, null, RepeatCustomerWinbackSend.STATE_SKIPPED_UNRESOLVED);
            return;
        }

        if (blockedNumberRepository.existsById(PhoneNumbers.normalize(phoneNumber))) {
            save(businessId, customer, phoneNumber, daysSinceLastVisit, providerChanged, null, null, RepeatCustomerWinbackSend.STATE_SKIPPED_BLOCKED);
            return;
        }

        if (messageLogService.hasNegativeFeedback(businessId, phoneNumber)) {
            save(businessId, customer, phoneNumber, daysSinceLastVisit, providerChanged, null, null, RepeatCustomerWinbackSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
            return;
        }

        boolean upcoming;
        try {
            upcoming = hasUpcomingAppointment(customer.squareCustomerId(), square);
        } catch (RuntimeException ex) {
            // Fails closed, same as every other scheduler in this package: a transient Square
            // failure means "don't know," not "assume unbooked" — no row written, retried on the
            // next day's run (the eligibility query's own NOT EXISTS naturally picks it back up).
            log.warn("Failed to check upcoming Square bookings for repeat-customer-winback candidate {} ({}); retrying next run",
                    customer.squareCustomerId(), phoneNumber, ex);
            return;
        }
        if (upcoming) {
            save(businessId, customer, phoneNumber, daysSinceLastVisit, providerChanged, null, null, RepeatCustomerWinbackSend.STATE_SKIPPED_BOOKED);
            return;
        }

        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            save(businessId, customer, phoneNumber, daysSinceLastVisit, providerChanged, null, null, RepeatCustomerWinbackSend.STATE_SKIPPED_DISABLED);
            return;
        }

        var promoTerms = promoConfigService.get(businessId, PromoConfigService.WINBACK_PROMO_CODE);
        if (promoTerms.isEmpty()) {
            save(businessId, customer, phoneNumber, daysSinceLastVisit, providerChanged, null, null,
                    RepeatCustomerWinbackSend.STATE_SKIPPED_PROMO_NOT_CONFIGURED);
            return;
        }

        Instant promoExpiresAt = endOfTodayInSalonZone();
        String givenName = square.customerGivenNames(List.of(customer.squareCustomerId())).get(customer.squareCustomerId());
        String variant = sendNudge(customer, phoneNumber, givenName, providerChanged, promoExpiresAt, square, businessId, promoTerms.get());
        save(businessId, customer, phoneNumber, daysSinceLastVisit, providerChanged, promoExpiresAt, variant, RepeatCustomerWinbackSend.STATE_SENT);
    }

    /** Consent from *either* source is sufficient — same dual-source check
     * {@code LapsedCustomerWinbackScheduler.hasConsent}/{@code SameDayRebookingScheduler.hasConsent}
     * use, reusing the same consent-segment id (no new Square segment needed). */
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

    /** End of "today" (start of tomorrow) in the salon's local zone — same helper
     * {@code LapsedCustomerWinbackScheduler} uses. Computed fresh at send time. */
    private static Instant endOfTodayInSalonZone() {
        return ZonedDateTime.now(SALON_ZONE).toLocalDate().plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
    }

    /** Live check for any not-cancelled, not-yet-happened Square appointment — same helper every
     * other scheduler in this package uses. */
    private boolean hasUpcomingAppointment(String customerId, SquareClient square) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return square.bookingsForCustomer(customerId, Instant.now()).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today));
    }

    /** Returns the message variant actually used ("default" or "previous_provider"), for the
     * caller to record. */
    private String sendNudge(RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer, String phoneNumber,
                              String rawGivenName, boolean providerChanged, Instant promoExpiresAt, SquareClient square,
                              Long businessId, PromoConfigService.PromoTerms promoTerms) {
        String clickToken = messageLogService.generateUniqueClickToken();
        // Reconstructed deterministically by ShortLinkController at click time — see
        // RebookingPromoSigner and class doc. No signature stored here; it's recomputed. Identical
        // regardless of consent — the coupon is applied on click either way, it's only the SMS
        // wording that differs (see class doc).
        String linkTarget = "WINBACK:" + promoExpiresAt.getEpochSecond();
        boolean consented = hasConsent(phoneNumber, customer.squareCustomerId(), square);
        String templateKey = consented ? TEMPLATE_KEY : TEMPLATE_KEY_TRANSACTIONAL;
        SmsMessage reserved = messageLogService.logOutboundWithLink(
                businessId, templateKey, AUTOMATION_KEY, phoneNumber, "", false, "pending", null, linkTarget, clickToken);

        String shortLink = publicBaseUrl + "/r/" + clickToken;
        String name = Names.capitalizeFirst(rawGivenName);
        // provider_visit.provider_name is always "First Last" — never send a last name to a
        // customer, same rule TechnicianNameResolver/LapsedCustomerWinbackScheduler enforce.
        String previousProviderFirstName = Names.firstNameOnly(customer.previousProvider());
        String lastProviderFirstName = Names.firstNameOnly(customer.lastProvider());

        String variant = (providerChanged && previousProviderFirstName != null && !previousProviderFirstName.isBlank())
                ? "previous_provider" : "default";
        TwilioSmsConfig config = configService.get(businessId);
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        String overrideKey = templateKey.equals(TEMPLATE_KEY)
                ? ("previous_provider".equals(variant) ? "repeat_customer_winback_nudge_previous_provider"
                        : "repeat_customer_winback_nudge_default")
                : ("previous_provider".equals(variant) ? "repeat_customer_winback_reminder_previous_provider"
                        : "repeat_customer_winback_reminder_default");
        Business business = businessRepository.findById(businessId).orElse(null);
        String businessName = business == null ? "" : business.getName();
        String discountAmount = PromoConfigService.formatDollars(promoTerms.discountCents());
        Map<String, String> vars;
        if ("previous_provider".equals(variant)) {
            vars = Map.of("greeting", greeting, "sender", config.getSenderName(),
                    "previousProvider", previousProviderFirstName, "discountAmount", discountAmount,
                    "link", shortLink, "businessName", businessName);
        } else {
            boolean hasTechnician = lastProviderFirstName != null && !lastProviderFirstName.isBlank();
            String visitClause = hasTechnician
                    ? "It's been a while since your last visit with " + lastProviderFirstName
                    : "It's been a while since your last visit";
            vars = Map.of("greeting", greeting, "sender", config.getSenderName(), "visitClause", visitClause,
                    "discountAmount", discountAmount, "link", shortLink, "businessName", businessName);
        }
        String body = templateService.render(businessId, overrideKey, phoneNumber, vars);

        if (!config.isConfigured()) {
            log.info("{} skipped — Twilio credentials not configured", templateKey);
            updateReserved(reserved, body, false, "not_configured", null);
            return variant;
        }
        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            updateReserved(reserved, body, true, null, twilioMessageSid);
        } catch (Exception e) {
            log.warn("{} send failed (caller unaffected): {}", templateKey, e.getMessage());
            updateReserved(reserved, body, false, "send_failed", null);
        }
        return variant;
    }

    private void updateReserved(SmsMessage reserved, String body, boolean sent, String reason, String twilioMessageSid) {
        reserved.setBody(body);
        reserved.setStatus(sent ? "SENT" : "NOT_SENT");
        reserved.setReason(reason);
        reserved.setTwilioMessageSid(twilioMessageSid);
        messageLogService.save(reserved);
    }

    private void save(Long businessId, RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer, String phoneNumber,
                       int daysSinceLastVisit, boolean providerChanged, Instant promoExpiresAt, String messageVariant,
                       String state) {
        sendRepository.save(RepeatCustomerWinbackSend.builder()
                .businessId(businessId)
                .squareCustomerId(customer.squareCustomerId())
                .phoneNumber(phoneNumber)
                .lastVisitDate(customer.lastVisitDate())
                .daysSinceLastVisit(daysSinceLastVisit)
                .totalVisitCount(customer.totalVisitCount())
                .lastProvider(customer.lastProvider())
                .previousProvider(customer.previousProvider())
                .providerChanged(providerChanged)
                .rebookedSameDay(customer.rebookedSameDay())
                .messageVariant(messageVariant)
                .promoExpiresAt(promoExpiresAt)
                .state(state)
                .build());
    }
}
