package com.salonreview.sms;

import com.salonreview.domain.RepeatCustomerWinbackSend;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.RepeatCustomerWinbackSendRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Once-daily win-back nudge for customers with 2+ completed all-time visits who are 40+ days
 * overdue by their own last visit — the "next" retention automation after
 * {@link LapsedCustomerWinbackScheduler} (which only covers exactly-one-visit customers). The
 * 40-day threshold comes from an independent test of 30/35/40/45/50/60-day alternatives against
 * this salon's real visit #2 → #3 churn behavior: by day 40, roughly 81% of customers who return
 * naturally already have, without waiting so long that the trigger reads as an "already-lost"
 * formality. Unlike {@code lapsed_customer_winback}, this send carries no discount
 * — it's a plain, friendly check-in — so it's TRANSACTIONAL by nature and doesn't need a
 * marketing-consent gate (same reasoning as {@code lead_follow_up_nudge}/{@code checkout_rating_request}).
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
    private final SquareClient square;
    private final SmsAutomationService automationService;
    private final SmsMessageLogService messageLogService;
    private final BlockedNumberRepository blockedNumberRepository;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final String publicBaseUrl;

    public RepeatCustomerWinbackScheduler(RepeatCustomerWinbackEligibilityRepository eligibilityRepository,
                                           RepeatCustomerWinbackSendRepository sendRepository, SquareClient square,
                                           SmsAutomationService automationService, SmsMessageLogService messageLogService,
                                           BlockedNumberRepository blockedNumberRepository, TwilioSmsConfigService configService,
                                           TwilioSmsClient client, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.eligibilityRepository = eligibilityRepository;
        this.sendRepository = sendRepository;
        this.square = square;
        this.automationService = automationService;
        this.messageLogService = messageLogService;
        this.blockedNumberRepository = blockedNumberRepository;
        this.configService = configService;
        this.client = client;
        this.publicBaseUrl = publicBaseUrl;
    }

    // zone is mandatory here too — see LapsedCustomerWinbackScheduler's own comment on the
    // 2026-08-07 unzoned-cron incident. Every @Scheduled(cron=...) in this codebase must set an
    // explicit zone.
    @Scheduled(cron = "0 0 10 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "RepeatCustomerWinbackScheduler_sendDueWinbacks", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sendDueWinbacks() {
        Instant cooldownCutoff = Instant.now().minus(COOLDOWN_DAYS, ChronoUnit.DAYS);
        for (RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer : eligibilityRepository.findEligibleCustomers()) {
            if (sendRepository.existsBySquareCustomerIdAndStateAndCreatedAtAfter(
                    customer.squareCustomerId(), RepeatCustomerWinbackSend.STATE_SENT, cooldownCutoff)) {
                continue; // belt-and-suspenders vs. the eligibility query's own NOT EXISTS
            }
            process(customer);
        }
    }

    private void process(RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer) {
        int daysSinceLastVisit = (int) ChronoUnit.DAYS.between(customer.lastVisitDate(), LocalDate.now(SALON_ZONE));
        boolean providerChanged = !Objects.equals(customer.lastProvider(), customer.previousProvider());

        String phoneNumber = square.customerPhone(customer.squareCustomerId());
        if (phoneNumber == null || phoneNumber.isBlank()) {
            save(customer, null, daysSinceLastVisit, providerChanged, null, RepeatCustomerWinbackSend.STATE_SKIPPED_UNRESOLVED);
            return;
        }

        if (blockedNumberRepository.existsById(PhoneNumbers.normalize(phoneNumber))) {
            save(customer, phoneNumber, daysSinceLastVisit, providerChanged, null, RepeatCustomerWinbackSend.STATE_SKIPPED_BLOCKED);
            return;
        }

        if (messageLogService.hasNegativeFeedback(phoneNumber)) {
            save(customer, phoneNumber, daysSinceLastVisit, providerChanged, null, RepeatCustomerWinbackSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
            return;
        }

        boolean upcoming;
        try {
            upcoming = hasUpcomingAppointment(customer.squareCustomerId());
        } catch (RuntimeException ex) {
            // Fails closed, same as every other scheduler in this package: a transient Square
            // failure means "don't know," not "assume unbooked" — no row written, retried on the
            // next day's run (the eligibility query's own NOT EXISTS naturally picks it back up).
            log.warn("Failed to check upcoming Square bookings for repeat-customer-winback candidate {} ({}); retrying next run",
                    customer.squareCustomerId(), phoneNumber, ex);
            return;
        }
        if (upcoming) {
            save(customer, phoneNumber, daysSinceLastVisit, providerChanged, null, RepeatCustomerWinbackSend.STATE_SKIPPED_BOOKED);
            return;
        }

        if (!automationService.isEnabled(AUTOMATION_KEY)) {
            save(customer, phoneNumber, daysSinceLastVisit, providerChanged, null, RepeatCustomerWinbackSend.STATE_SKIPPED_DISABLED);
            return;
        }

        String givenName = square.customerGivenNames(List.of(customer.squareCustomerId())).get(customer.squareCustomerId());
        String variant = sendNudge(customer, phoneNumber, givenName, providerChanged);
        save(customer, phoneNumber, daysSinceLastVisit, providerChanged, variant, RepeatCustomerWinbackSend.STATE_SENT);
    }

    /** Live check for any not-cancelled, not-yet-happened Square appointment — same helper every
     * other scheduler in this package uses. */
    private boolean hasUpcomingAppointment(String customerId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return square.bookingsForCustomer(customerId, Instant.now()).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today));
    }

    /** Returns the message variant actually used ("default" or "previous_provider"), for the
     * caller to record. */
    private String sendNudge(RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer, String phoneNumber,
                              String rawGivenName, boolean providerChanged) {
        String clickToken = messageLogService.generateUniqueClickToken();
        SmsMessage reserved = messageLogService.logOutboundWithLink(
                TEMPLATE_KEY, AUTOMATION_KEY, phoneNumber, "", false, "pending", null, ShortLinkController.BOOK_NOW_TARGET, clickToken);

        String shortLink = publicBaseUrl + "/r/" + clickToken;
        String name = Names.capitalizeFirst(rawGivenName);
        // provider_visit.provider_name is always "First Last" — never send a last name to a
        // customer, same rule TechnicianNameResolver/LapsedCustomerWinbackScheduler enforce.
        String previousProviderFirstName = Names.firstNameOnly(customer.previousProvider());
        String lastProviderFirstName = Names.firstNameOnly(customer.lastProvider());

        String variant = (providerChanged && previousProviderFirstName != null && !previousProviderFirstName.isBlank())
                ? "previous_provider" : "default";
        String body = "previous_provider".equals(variant)
                ? previousProviderBody(name, previousProviderFirstName, shortLink)
                : defaultBody(name, lastProviderFirstName, shortLink);

        TwilioSmsConfig config = configService.get();
        if (!config.isConfigured()) {
            log.info("{} skipped — Twilio credentials not configured", TEMPLATE_KEY);
            updateReserved(reserved, body, false, "not_configured", null);
            return variant;
        }
        try {
            String twilioMessageSid = client.send(config, phoneNumber, body);
            updateReserved(reserved, body, true, null, twilioMessageSid);
        } catch (Exception e) {
            log.warn("{} send failed (caller unaffected): {}", TEMPLATE_KEY, e.getMessage());
            updateReserved(reserved, body, false, "send_failed", null);
        }
        return variant;
    }

    /** Used when the customer stayed with the same technician across their last two visits, or
     * when no technician name is available at all — names {@code technician} (their apparent
     * regular) if known, otherwise a technician-less fallback. No discount language: this
     * automation carries no coupon (see class doc). */
    private static String defaultBody(String name, String technician, String shortLink) {
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        String body = (technician == null || technician.isBlank())
                ? "It's been a while since your last visit"
                : "It's been a while since your last visit with " + technician;
        return greeting + " It's Lucy from AK.LUX.NAILS 💛 " + body + " — book your next mani here: " + shortLink + " -Lucy";
    }

    /** Used when the customer's technician changed between their last two visits — offers to check
     * with the earlier/previous technician by name, per design.md's "may personalize using the
     * previous provider name" guidance. Deliberately offers to "check," not a guarantee — we don't
     * know that technician's actual current availability at send time. */
    private static String previousProviderBody(String name, String previousProvider, String shortLink) {
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        return greeting + " It's Lucy from AK.LUX.NAILS 💛 It's been a while since we've seen you — want me to check "
                + "if " + previousProvider + " has an opening for you? Book here: " + shortLink + " -Lucy";
    }

    private void updateReserved(SmsMessage reserved, String body, boolean sent, String reason, String twilioMessageSid) {
        reserved.setBody(body);
        reserved.setStatus(sent ? "SENT" : "NOT_SENT");
        reserved.setReason(reason);
        reserved.setTwilioMessageSid(twilioMessageSid);
        messageLogService.save(reserved);
    }

    private void save(RepeatCustomerWinbackEligibilityRepository.EligibleCustomer customer, String phoneNumber,
                       int daysSinceLastVisit, boolean providerChanged, String messageVariant, String state) {
        sendRepository.save(RepeatCustomerWinbackSend.builder()
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
                .state(state)
                .build());
    }
}
