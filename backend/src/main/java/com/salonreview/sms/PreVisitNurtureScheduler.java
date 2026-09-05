package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.PreVisitNurtureSend;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.PreVisitNurtureSendRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
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
 * Pre-visit nurture email sequence (owner request 2026-09-05): a customer who just booked gets a
 * warm welcome email shortly after (step 1, {@link #sendDueWelcomeEmails}), and — only if their
 * appointment is far enough out to have a real "day before" — a reminder email the day before
 * (step 2, {@link #sendDueReminderEmails}). Goal is fewer cancellations/no-shows through
 * familiarity with the studio before the visit, not a booking-conversion ask (the customer has
 * already booked); framed that way in both templates.
 *
 * <p>Reads {@link SquareBookingMirror} (the already-synced local copy of Square's own bookings —
 * see that class's own doc) rather than hooking a new webhook path: this automation only needs to
 * notice a booking within a few minutes of it existing, which the mirror's own webhook + periodic
 * reconciliation already provides, so a dedicated trigger service would be pure duplication.
 */
@Component
public class PreVisitNurtureScheduler {

    private static final Logger log = LoggerFactory.getLogger(PreVisitNurtureScheduler.class);
    private static final String AUTOMATION_KEY = "pre_visit_nurture";
    private static final String ACCEPTED_STATUS = "ACCEPTED";

    /** Welcome email fires 5-30 minutes after the booking was created — long enough to not land
     * in the same instant as Square's own confirmation SMS/email (a separate, personal touch, not
     * a duplicate of it), short enough that it still reads as "just booked," not a delayed
     * afterthought. Bounded scan, same "a booking older than this never gets welcomed" shape as
     * every other poller here. */
    private static final Duration WELCOME_MIN_AGE = Duration.ofMinutes(5);
    private static final Duration WELCOME_MAX_AGE = Duration.ofMinutes(30);

    /** Reminder window: a day out, generously wide (10h either side of the 24h mark) so a
     * 15-minute-ish poll cadence and DST/clock drift can't cause a booking to fall through the
     * gap between two ticks. A booking made less than ~14h before its own start time never enters
     * this window at all (its appointment_start_at is already in the past relative to "now +14h"
     * by the time this would first check it) — that's the intended "too soon to have a day-before"
     * exclusion, not a bug to work around. */
    private static final Duration REMINDER_MIN_LEAD = Duration.ofHours(14);
    private static final Duration REMINDER_MAX_LEAD = Duration.ofHours(34);

    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final PreVisitNurtureSendRepository sendRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final SquareClientProvider squareClientProvider;
    private final SmsAutomationService automationService;
    private final ProviderRepository providerRepository;

    public PreVisitNurtureScheduler(SquareBookingMirrorRepository bookingMirrorRepository,
                                     PreVisitNurtureSendRepository sendRepository,
                                     MailchimpConfigRepository mailchimpConfigRepository,
                                     MailchimpEmailService mailchimpEmailService,
                                     MailchimpEmailTemplateService templateService,
                                     SquareClientProvider squareClientProvider,
                                     SmsAutomationService automationService,
                                     ProviderRepository providerRepository) {
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.sendRepository = sendRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.squareClientProvider = squareClientProvider;
        this.automationService = automationService;
        this.providerRepository = providerRepository;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 30_000)
    @SchedulerLock(name = "PreVisitNurtureScheduler_sendDueWelcomeEmails", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueWelcomeEmails() {
        Instant now = Instant.now();
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            List<SquareBookingMirror> candidates = bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(
                    businessId, ACCEPTED_STATUS, now.minus(WELCOME_MAX_AGE), now.minus(WELCOME_MIN_AGE));
            for (SquareBookingMirror booking : candidates) {
                if (sendRepository.existsByBusinessIdAndSquareBookingId(businessId, booking.getSquareBookingId())) {
                    continue; // already considered this booking
                }
                try {
                    processWelcomeEmail(booking, config);
                } catch (RuntimeException e) {
                    log.warn("Pre-visit nurture welcome email failed for booking {} (skipped, not retried): {}",
                            booking.getSquareBookingId(), e.getMessage(), e);
                }
            }
        }
    }

    private void processWelcomeEmail(SquareBookingMirror booking, MailchimpConfig config) {
        Long businessId = booking.getBusinessId();
        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_DISABLED, null);
            return;
        }
        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Pre-visit nurture welcome email skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return; // no row saved — retried next tick, same as this package's other Square-failure handling
        }
        String customerId = booking.getSquareCustomerId();
        String email = customerId == null ? null : square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_NO_EMAIL, null);
            return;
        }

        String givenName = Names.capitalizeFirst(
                customerId == null ? null : square.customerGivenNames(List.of(customerId)).get(customerId));
        String technician = technicianFirstName(booking, businessId);

        Map<String, String> vars = new HashMap<>();
        vars.put("FNAME", givenName == null ? "there" : givenName);
        vars.put("TECHNICIAN_CLAUSE", technician == null ? "" : " with " + technician);

        Optional<String> html = templateService.render(businessId, AUTOMATION_KEY + "_welcome", vars);
        if (html.isEmpty()) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE, null);
            return;
        }

        String subjectLine = "You're booked, " + vars.get("FNAME") + "! A little about us";
        String previewText = "Excited to see you — here's what to expect";
        String campaignTitle = AUTOMATION_KEY + " welcome — booking " + booking.getSquareBookingId();

        String savedState;
        try {
            mailchimpEmailService.sendWinbackEmail(config, email, subjectLine, previewText, campaignTitle, html.get());
            savedState = PreVisitNurtureSend.STATE_SENT;
        } catch (Exception e) {
            log.warn("Pre-visit nurture welcome email send failed for booking {} (not retried): {}",
                    booking.getSquareBookingId(), e.getMessage());
            savedState = PreVisitNurtureSend.STATE_SEND_FAILED;
        }
        save(booking, savedState, null);
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    @SchedulerLock(name = "PreVisitNurtureScheduler_sendDueReminderEmails", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueReminderEmails() {
        Instant now = Instant.now();
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            List<PreVisitNurtureSend> candidates = sendRepository
                    .findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                            businessId, PreVisitNurtureSend.STATE_SENT,
                            now.plus(REMINDER_MIN_LEAD), now.plus(REMINDER_MAX_LEAD));
            for (PreVisitNurtureSend row : candidates) {
                try {
                    processReminderEmail(row, config);
                } catch (RuntimeException e) {
                    log.warn("Pre-visit nurture reminder email failed for booking {} (skipped, not retried): {}",
                            row.getSquareBookingId(), e.getMessage(), e);
                }
            }
        }
    }

    private void processReminderEmail(PreVisitNurtureSend row, MailchimpConfig config) {
        Long businessId = row.getBusinessId();
        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            saveReminderState(row, PreVisitNurtureSend.STATE_SKIPPED_DISABLED);
            return;
        }
        Optional<SquareBookingMirror> current = bookingMirrorRepository
                .findByBusinessIdAndSquareBookingId(businessId, row.getSquareBookingId());
        if (current.isEmpty() || !ACCEPTED_STATUS.equals(current.get().getStatus())) {
            saveReminderState(row, PreVisitNurtureSend.STATE_SKIPPED_CANCELLED);
            return;
        }
        SquareBookingMirror booking = current.get();

        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Pre-visit nurture reminder email skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return;
        }
        String customerId = row.getSquareCustomerId();
        String email = customerId == null ? null : square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            saveReminderState(row, PreVisitNurtureSend.STATE_SKIPPED_NO_EMAIL);
            return;
        }

        String givenName = Names.capitalizeFirst(
                customerId == null ? null : square.customerGivenNames(List.of(customerId)).get(customerId));
        String technician = technicianFirstName(booking, businessId);

        Map<String, String> vars = new HashMap<>();
        vars.put("FNAME", givenName == null ? "there" : givenName);
        vars.put("TECHNICIAN_CLAUSE", technician == null ? "" : " with " + technician);

        Optional<String> html = templateService.render(businessId, AUTOMATION_KEY + "_reminder", vars);
        if (html.isEmpty()) {
            saveReminderState(row, PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE);
            return;
        }

        String subjectLine = "See you tomorrow, " + vars.get("FNAME") + "!";
        String previewText = "Quick reminder + what you need to know before you come in";
        String campaignTitle = AUTOMATION_KEY + " reminder — booking " + row.getSquareBookingId();

        try {
            mailchimpEmailService.sendWinbackEmail(config, email, subjectLine, previewText, campaignTitle, html.get());
            saveReminderState(row, PreVisitNurtureSend.STATE_SENT);
        } catch (Exception e) {
            log.warn("Pre-visit nurture reminder email send failed for booking {} (not retried): {}",
                    row.getSquareBookingId(), e.getMessage());
            saveReminderState(row, PreVisitNurtureSend.STATE_SEND_FAILED);
        }
    }

    /** Best-effort — the booking's own first segment names the real Square team member id
     * performing it, matched against this business's own {@link Provider#getSquareTeamMemberIds()}.
     * {@code null} if unresolvable (no segments, or a team member id no {@link Provider} row
     * claims), same degrade-gracefully convention every other technician-naming call site here
     * follows. */
    private String technicianFirstName(SquareBookingMirror booking, Long businessId) {
        if (booking.getAppointmentSegments() == null || booking.getAppointmentSegments().isEmpty()) {
            return null;
        }
        String teamMemberId = booking.getAppointmentSegments().get(0).teamMemberId();
        if (teamMemberId == null) {
            return null;
        }
        return providerRepository.findAllByBusinessId(businessId).stream()
                .filter(p -> p.getSquareTeamMemberIds().contains(teamMemberId))
                .findFirst()
                .map(Provider::getDisplayName)
                .map(Names::firstNameOnly)
                .orElse(null);
    }

    private void save(SquareBookingMirror booking, String welcomeState, String reminderState) {
        sendRepository.save(PreVisitNurtureSend.builder()
                .businessId(booking.getBusinessId())
                .squareBookingId(booking.getSquareBookingId())
                .squareCustomerId(booking.getSquareCustomerId())
                .appointmentStartAt(booking.getStartAt())
                .welcomeState(welcomeState)
                .reminderState(reminderState)
                .build());
    }

    private void saveReminderState(PreVisitNurtureSend row, String state) {
        row.setReminderState(state);
        sendRepository.save(row);
    }
}
