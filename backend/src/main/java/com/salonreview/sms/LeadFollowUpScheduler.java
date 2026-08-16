package com.salonreview.sms;

import com.salonreview.domain.LeadFollowUpSend;
import com.salonreview.marketing.MarketingContactsRepository;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.LeadFollowUpSendRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private final BusinessRepository businesses;
    private final SmsAutomationService automationService;
    private final TwilioSmsService smsService;

    public LeadFollowUpScheduler(MarketingContactsRepository contactsRepository,
                                  LeadFollowUpSendRepository sendRepository,
                                  SquareClientProvider squareClientProvider,
                                  BusinessRepository businesses,
                                  SmsAutomationService automationService,
                                  TwilioSmsService smsService) {
        this.contactsRepository = contactsRepository;
        this.sendRepository = sendRepository;
        this.squareClientProvider = squareClientProvider;
        this.businesses = businesses;
        this.automationService = automationService;
        this.smsService = smsService;
    }

    // initialDelay: see SameDayRebookingScheduler's identical comment — gives
    // SquareConnectionBootstrap's ApplicationRunner time to finish before the first tick.
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @SchedulerLock(name = "LeadFollowUpScheduler_sendDueFollowUps", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueFollowUps() {
        // See BusinessRepository#legacySmsBusiness — marketing.contacts has no business_id of its
        // own yet, so there's no correct per-business Square routing until that schema is scoped
        // too (tracked separately from this migration).
        Long businessId = businesses.legacySmsBusiness().getId();
        SquareClient square = squareClientProvider.forBusiness(businessId);
        Instant now = Instant.now();
        List<RawContact> pending = contactsRepository.findPendingFollowUp(now.minus(MIN_AGE), now.minus(MAX_AGE));
        for (RawContact contact : pending) {
            if (sendRepository.existsByContactIdAndContactUpdatedAtGreaterThanEqual(contact.id(), contact.updatedAt())) {
                continue; // belt-and-suspenders vs. the poll query's own NOT EXISTS
            }
            process(contact, square, businessId);
        }
    }

    private void process(RawContact contact, SquareClient square, Long businessId) {
        boolean upcoming;
        try {
            upcoming = hasUpcomingAppointment(contact, square);
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
        if (!automationService.isEnabled("lead_follow_up")) {
            save(contact, LeadFollowUpSend.STATE_SKIPPED_DISABLED);
            return;
        }
        Map<String, String> vars = contact.givenName() == null ? Map.of() : Map.of("name", contact.givenName());
        var result = smsService.sendTemplated(businessId, "lead_follow_up_nudge", contact.phoneNumber(), vars);
        if (!result.sent()) {
            log.warn("lead_follow_up_nudge not sent for contact {} ({}): {}",
                    contact.id(), contact.phoneNumber(), result.reason());
        }
        save(contact, LeadFollowUpSend.STATE_SENT);
    }

    /** Live check for any not-cancelled, not-yet-happened Square appointment — not limited to a
     * booking made through this specific contact-capture session (see design.md D2). Resolves a
     * Square customer via the contact's own {@code squareCustomerId} if the tracked flow already
     * set it, otherwise falls back to a live phone lookup, same as
     * {@code MarketingContactsService.syncSquareLinks}. */
    private boolean hasUpcomingAppointment(RawContact contact, SquareClient square) {
        String customerId = contact.squareCustomerId();
        if (customerId == null) {
            List<String> candidates = square.customerIdsForPhone(contact.phoneNumber());
            if (candidates.isEmpty()) {
                return false;
            }
            customerId = candidates.get(0);
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return square.bookingsForCustomer(customerId, Instant.now()).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today));
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
