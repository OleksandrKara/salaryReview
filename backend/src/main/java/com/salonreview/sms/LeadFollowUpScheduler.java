package com.salonreview.sms;

import com.salonreview.domain.LeadFollowUpSend;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.marketing.MarketingContactsRepository;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.repo.LeadFollowUpSendRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Polls {@code marketing.contacts} for leads who haven't got an upcoming Square appointment within
 * 2 minutes of last leaving contact info — see openspec/changes/lead-followup-and-manager-inbox
 * design.md D1/D2. "Last leaving contact info" is {@code updated_at}, not {@code created_at} — see
 * {@link com.salonreview.marketing.MarketingContactsRepository#findPendingFollowUp} for why a
 * returning lead needs the poll keyed off the row's most recent write, not its first ever one.
 * Same imprecise-but-good-enough 15s poll cadence as {@link SmsReplyFlowScheduler}: a contact
 * becomes eligible at exactly 2:00 but is actually processed on the next tick, so the real send
 * window is ~2:00-2:15 (see design.md's "how exact is 2 minutes, really?" note under D1).
 */
@Component
public class LeadFollowUpScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeadFollowUpScheduler.class);

    /** Lower bound on age — a contact must be at least this old to be considered. */
    private static final Duration MIN_AGE = Duration.ofMinutes(2);
    /** Upper bound on age — bounds the poll's scan window (see design.md D1); a contact older
     * than this that somehow never got processed is simply never followed up on rather than kept
     * in an ever-growing scan. */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final MarketingContactsRepository contactsRepository;
    private final LeadFollowUpSendRepository sendRepository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final TwilioSmsService smsService;
    private final SquareUpcomingAppointmentService upcomingAppointmentService;

    public LeadFollowUpScheduler(MarketingContactsRepository contactsRepository,
                                  LeadFollowUpSendRepository sendRepository,
                                  SquareClientProvider squareClientProvider,
                                  TwilioSmsConfigRepository twilioConfigs,
                                  SmsAutomationService automationService,
                                  TwilioSmsService smsService,
                                  SquareUpcomingAppointmentService upcomingAppointmentService) {
        this.contactsRepository = contactsRepository;
        this.sendRepository = sendRepository;
        this.squareClientProvider = squareClientProvider;
        this.twilioConfigs = twilioConfigs;
        this.automationService = automationService;
        this.smsService = smsService;
        this.upcomingAppointmentService = upcomingAppointmentService;
    }

    // initialDelay: see SameDayRebookingScheduler's identical comment — gives
    // SquareConnectionBootstrap's ApplicationRunner time to finish before the first tick.
    // Single lock covers the whole per-business loop below — same deliberate simplification
    // SameDayRebookingScheduler's identical loop already makes (see its own doc comment).
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @SchedulerLock(name = "LeadFollowUpScheduler_sendDueFollowUps", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueFollowUps() {
        Instant now = Instant.now();
        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            SquareClient square;
            try {
                square = squareClientProvider.forBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Lead follow-up nudges skipped for business {} (will be retried at next scheduled run): {}",
                        businessId, e.getMessage());
                continue;
            }
            List<RawContact> pending = contactsRepository.findPendingFollowUp(now.minus(MIN_AGE), now.minus(MAX_AGE), businessId);
            for (RawContact contact : pending) {
                if (sendRepository.existsByContactIdAndContactUpdatedAtGreaterThanEqual(contact.id(), contact.updatedAt())) {
                    continue; // belt-and-suspenders vs. the poll query's own NOT EXISTS
                }
                process(contact, square, businessId);
            }
        }
    }

    private void process(RawContact contact, SquareClient square, Long businessId) {
        boolean upcoming;
        try {
            upcoming = upcomingAppointmentService.hasUpcomingAppointment(contact.phoneNumber(), square);
        } catch (RuntimeException ex) {
            // Fails closed: a transient Square failure means "don't know," not "assume unbooked."
            // No row is written, so this contact is simply retried on the next poll tick rather
            // than either wrongly texted or permanently skipped.
            log.warn("Failed to check upcoming Square bookings for contact {} ({}); retrying next poll",
                    contact.id(), contact.phoneNumber(), ex);
            return;
        }
        if (upcoming) {
            save(contact, LeadFollowUpSend.STATE_SKIPPED_BOOKED);
            return;
        }
        if (!automationService.isEnabled(businessId, "lead_follow_up")) {
            save(contact, LeadFollowUpSend.STATE_SKIPPED_DISABLED);
            return;
        }
        String name = com.salonreview.util.Names.capitalizeFirst(contact.givenName());
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        Map<String, String> vars = Map.of("greeting", greeting);
        var result = smsService.sendTemplated(businessId, "lead_follow_up_nudge", contact.phoneNumber(), vars);
        if (!result.sent()) {
            log.warn("lead_follow_up_nudge not sent for contact {} ({}): {}",
                    contact.id(), contact.phoneNumber(), result.reason());
        }
        save(contact, LeadFollowUpSend.STATE_SENT);
    }


    private void save(RawContact contact, String state) {
        sendRepository.save(LeadFollowUpSend.builder()
                .contactId(contact.id())
                .contactUpdatedAt(contact.updatedAt())
                .phoneNumber(contact.phoneNumber())
                .state(state)
                .build());
    }
}
