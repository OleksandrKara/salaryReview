package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.domain.LeadFollowUpSend;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.marketing.MarketingContactsRepository;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.LeadFollowUpSendRepository;
import com.salonreview.repo.MailchimpConfigRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Polls {@code marketing.contacts} for leads who haven't got an upcoming Square appointment within
 * 2 minutes of last leaving contact info — see openspec/changes/lead-followup-and-manager-inbox
 * design.md D1/D2. "Last leaving contact info" is {@code updated_at}, not {@code created_at} — see
 * {@link com.salonreview.marketing.MarketingContactsRepository#findPendingFollowUp} for why a
 * returning lead needs the poll keyed off the row's most recent write, not its first ever one.
 * Same imprecise-but-good-enough 15s poll cadence as {@link SmsReplyFlowScheduler}: a contact
 * becomes eligible at exactly 2:00 but is actually processed on the next tick, so the real send
 * window is ~2:00-2:15 (see design.md's "how exact is 2 minutes, really?" note under D1).
 *
 * <p>Extended 2026-09-05 (owner request) into a 3-step funnel for a lead who's still unbooked:
 * step 1 (above) an SMS at ~2 min, step 2 ({@link #sendDueEmailFollowUps}) an email at ~24h, step
 * 3 ({@link #sendDueSmsFinalFollowUps}) a final plain SMS at ~72h — each step independently
 * re-checks "still unbooked" and "automation still enabled" at its own send time, and each is
 * skipped (not retried) once its own poll window has passed, same "bounded scan, not indefinite"
 * shape {@link #MAX_AGE} already established for step 1.
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

    /** 2026-09-05 live incident: a lead who resubmitted contact info twice within 4 minutes (a
     * double form submit) got the identical nudge text twice — {@code contactUpdatedAt} moved
     * both times, so the per-touch idempotency check alone couldn't catch it (see {@link
     * LeadFollowUpSend}'s own doc on why that's by design for a genuinely later resubmission). A
     * day is long enough that a real same-day double-submit never gets a second identical text,
     * short enough that a lead who comes back and leaves their info again days later still does. */
    private static final Duration RESEND_COOLDOWN = Duration.ofHours(24);

    /** Funnel steps 2/3's own poll windows — generous (24h wide) so a scheduler outage of up to a
     * day doesn't skip a touch entirely, same reasoning as {@link #MAX_AGE} above. */
    private static final Duration EMAIL_FOLLOWUP_MIN_AGE = Duration.ofHours(24);
    private static final Duration EMAIL_FOLLOWUP_MAX_AGE = Duration.ofHours(48);
    private static final Duration SMS_FOLLOWUP_MIN_AGE = Duration.ofHours(72);
    private static final Duration SMS_FOLLOWUP_MAX_AGE = Duration.ofHours(96);

    private final MarketingContactsRepository contactsRepository;
    private final LeadFollowUpSendRepository sendRepository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final TwilioSmsService smsService;
    private final SquareUpcomingAppointmentService upcomingAppointmentService;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final BusinessRepository businessRepository;

    public LeadFollowUpScheduler(MarketingContactsRepository contactsRepository,
                                  LeadFollowUpSendRepository sendRepository,
                                  SquareClientProvider squareClientProvider,
                                  TwilioSmsConfigRepository twilioConfigs,
                                  SmsAutomationService automationService,
                                  TwilioSmsService smsService,
                                  SquareUpcomingAppointmentService upcomingAppointmentService,
                                  MailchimpConfigRepository mailchimpConfigRepository,
                                  MailchimpEmailService mailchimpEmailService,
                                  MailchimpEmailTemplateService templateService,
                                  BusinessRepository businessRepository) {
        this.contactsRepository = contactsRepository;
        this.sendRepository = sendRepository;
        this.squareClientProvider = squareClientProvider;
        this.twilioConfigs = twilioConfigs;
        this.automationService = automationService;
        this.smsService = smsService;
        this.upcomingAppointmentService = upcomingAppointmentService;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.businessRepository = businessRepository;
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
            save(contact, businessId, LeadFollowUpSend.STATE_SKIPPED_BOOKED);
            return;
        }
        if (sendRepository.existsByPhoneNumberAndStateAndCreatedAtAfter(
                contact.phoneNumber(), LeadFollowUpSend.STATE_SENT, Instant.now().minus(RESEND_COOLDOWN))) {
            save(contact, businessId, LeadFollowUpSend.STATE_SKIPPED_RECENTLY_SENT);
            return;
        }
        if (!automationService.isEnabled(businessId, "lead_follow_up")) {
            save(contact, businessId, LeadFollowUpSend.STATE_SKIPPED_DISABLED);
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
        save(contact, businessId, LeadFollowUpSend.STATE_SENT);
    }

    private void save(RawContact contact, Long businessId, String state) {
        sendRepository.save(LeadFollowUpSend.builder()
                .businessId(businessId)
                .contactId(contact.id())
                .contactUpdatedAt(contact.updatedAt())
                .phoneNumber(contact.phoneNumber())
                .state(state)
                .build());
    }

    /** Step 2 of the funnel — see class doc. Independent poll from step 1's, so a candidate here
     * is a {@code LeadFollowUpSend} row directly (already business-scoped, unlike step 1's own
     * fresh {@code RawContact} reads) rather than a re-query of {@code marketing.contacts}' own
     * pending-follow-up view. */
    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    @SchedulerLock(name = "LeadFollowUpScheduler_sendDueEmailFollowUps", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueEmailFollowUps() {
        Instant now = Instant.now();
        List<LeadFollowUpSend> candidates = sendRepository.findByStateAndEmailFollowupStateIsNullAndCreatedAtBetween(
                LeadFollowUpSend.STATE_SENT, now.minus(EMAIL_FOLLOWUP_MAX_AGE), now.minus(EMAIL_FOLLOWUP_MIN_AGE));
        for (LeadFollowUpSend touch : candidates) {
            try {
                processEmailFollowUp(touch);
            } catch (RuntimeException e) {
                log.warn("Lead follow-up email step failed for touch {} (skipped, not retried): {}",
                        touch.getId(), e.getMessage(), e);
            }
        }
    }

    private void processEmailFollowUp(LeadFollowUpSend touch) {
        Long businessId = touch.getBusinessId();
        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Lead follow-up email step skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return; // no state saved — retried next tick, same as step 1's own Square-failure handling
        }
        boolean upcoming;
        try {
            upcoming = upcomingAppointmentService.hasUpcomingAppointment(touch.getPhoneNumber(), square);
        } catch (RuntimeException ex) {
            log.warn("Failed to check upcoming Square bookings for lead follow-up touch {} ({}); retrying next poll",
                    touch.getId(), touch.getPhoneNumber(), ex);
            return;
        }
        if (upcoming) {
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SKIPPED_BOOKED);
            return;
        }
        if (!automationService.isEnabled(businessId, "lead_follow_up")) {
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SKIPPED_DISABLED);
            return;
        }
        MailchimpConfig config = mailchimpConfigRepository.findByBusinessId(businessId).orElse(null);
        if (config == null || !config.isConfigured()) {
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SKIPPED_NOT_CONFIGURED);
            return;
        }
        RawContact contact = contactsRepository.findByIds(List.of(touch.getContactId()), businessId)
                .stream().findFirst().orElse(null);
        String email = contact == null ? null : contact.emailAddress();
        if (email == null || email.isBlank()) {
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SKIPPED_NO_EMAIL);
            return;
        }

        String givenName = com.salonreview.util.Names.capitalizeFirst(contact.givenName());
        Business business = businessRepository.findById(businessId).orElse(null);
        String bookingLink = business == null || business.getPublicDomain() == null || business.getPublicDomain().isBlank()
                ? "" : "https://" + business.getPublicDomain() + "/";

        Map<String, String> vars = new HashMap<>();
        vars.put("FNAME", givenName == null ? "there" : givenName);
        vars.put("LINK", bookingLink);

        Optional<String> html = templateService.render(businessId, "lead_follow_up", vars);
        if (html.isEmpty()) {
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SKIPPED_NO_TEMPLATE);
            return;
        }

        String subjectLine = "Still thinking about it, " + vars.get("FNAME") + "?";
        String previewText = "No rush, just wanted to say hi";
        String campaignTitle = "lead_follow_up email follow-up — touch " + touch.getId();

        try {
            mailchimpEmailService.sendWinbackEmail(config, email, subjectLine, previewText, campaignTitle, html.get());
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SENT);
        } catch (Exception e) {
            log.warn("Lead follow-up email send failed for touch {} (not retried): {}", touch.getId(), e.getMessage());
            saveEmailState(touch, LeadFollowUpSend.EMAIL_STATE_SEND_FAILED);
        }
    }

    private void saveEmailState(LeadFollowUpSend touch, String state) {
        touch.setEmailFollowupState(state);
        sendRepository.save(touch);
    }

    /** Step 3 of the funnel — see class doc. Independent of whether step 2 ever completed (a
     * skipped/failed email doesn't block the final SMS from going out on its own schedule). */
    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    @SchedulerLock(name = "LeadFollowUpScheduler_sendDueSmsFinalFollowUps", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueSmsFinalFollowUps() {
        Instant now = Instant.now();
        List<LeadFollowUpSend> candidates = sendRepository.findByStateAndSmsFollowupStateIsNullAndCreatedAtBetween(
                LeadFollowUpSend.STATE_SENT, now.minus(SMS_FOLLOWUP_MAX_AGE), now.minus(SMS_FOLLOWUP_MIN_AGE));
        for (LeadFollowUpSend touch : candidates) {
            try {
                processSmsFinalFollowUp(touch);
            } catch (RuntimeException e) {
                log.warn("Lead follow-up final SMS step failed for touch {} (skipped, not retried): {}",
                        touch.getId(), e.getMessage(), e);
            }
        }
    }

    private void processSmsFinalFollowUp(LeadFollowUpSend touch) {
        Long businessId = touch.getBusinessId();
        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Lead follow-up final SMS step skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return;
        }
        boolean upcoming;
        try {
            upcoming = upcomingAppointmentService.hasUpcomingAppointment(touch.getPhoneNumber(), square);
        } catch (RuntimeException ex) {
            log.warn("Failed to check upcoming Square bookings for lead follow-up touch {} ({}); retrying next poll",
                    touch.getId(), touch.getPhoneNumber(), ex);
            return;
        }
        if (upcoming) {
            saveSmsFollowupState(touch, LeadFollowUpSend.SMS_FOLLOWUP_STATE_SKIPPED_BOOKED);
            return;
        }
        if (!automationService.isEnabled(businessId, "lead_follow_up")) {
            saveSmsFollowupState(touch, LeadFollowUpSend.SMS_FOLLOWUP_STATE_SKIPPED_DISABLED);
            return;
        }
        RawContact contact = contactsRepository.findByIds(List.of(touch.getContactId()), businessId)
                .stream().findFirst().orElse(null);
        String name = contact == null ? null : com.salonreview.util.Names.capitalizeFirst(contact.givenName());
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        var result = smsService.sendTemplated(businessId, "lead_follow_up_final_nudge", touch.getPhoneNumber(), Map.of("greeting", greeting));
        if (!result.sent()) {
            log.warn("lead_follow_up_final_nudge not sent for touch {} ({}): {}",
                    touch.getId(), touch.getPhoneNumber(), result.reason());
        }
        saveSmsFollowupState(touch, LeadFollowUpSend.SMS_FOLLOWUP_STATE_SENT);
    }

    private void saveSmsFollowupState(LeadFollowUpSend touch, String state) {
        touch.setSmsFollowupState(state);
        sendRepository.save(touch);
    }
}
