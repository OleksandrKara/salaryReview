package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.sms.SmsAutomationService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * First-visit intake form flow: finds new clients shortly after they book, texts them one link,
 * texts one reminder about a day before the visit if still unsigned, and mirrors the status onto
 * the Square customer note. Runs only for businesses listed in {@code intake.form.businesses} AND
 * with the first_visit_intake_form automation switched on; otherwise it does nothing at all.
 */
@Component
public class IntakeFormScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntakeFormScheduler.class);
    public static final String AUTOMATION_KEY = "first_visit_intake_form";
    private static final String ACCEPTED = "ACCEPTED";
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    /** Newly created bookings are picked up 3 minutes in (the mirror has them) and for 14 hours, so
     * a booking made at night is still texted at 8:00 the next morning. Older bookings are never
     * touched: no backfill when the switch is turned on. */
    static final Duration MIN_AGE = Duration.ofMinutes(3);
    static final Duration MAX_AGE = Duration.ofHours(14);
    static final Duration MIN_TIME_BEFORE_VISIT = Duration.ofMinutes(30);
    static final Duration REMINDER_MIN_LEAD = Duration.ofHours(14);
    static final Duration REMINDER_MAX_LEAD = Duration.ofHours(30);
    static final Duration REMINDER_MIN_SINCE_FIRST = Duration.ofHours(6);
    static final int SEND_FROM_HOUR = 8;
    static final int SEND_UNTIL_HOUR = 21;
    static final int MAX_SQUARE_SYNC_ATTEMPTS = 6;

    private final Set<Long> businesses;
    private final SquareBookingMirrorRepository bookings;
    private final IntakeFormRepository forms;
    private final FirstVisitDetector detector;
    private final IntakeFormService service;
    private final SmsAutomationService automations;
    private final SquareClientProvider squareProvider;
    private final TelegramNotificationService telegram;
    private final Clock clock;

    @Autowired
    public IntakeFormScheduler(@Value("${intake.form.businesses:}") String businesses,
                               SquareBookingMirrorRepository bookings, IntakeFormRepository forms,
                               FirstVisitDetector detector, IntakeFormService service,
                               SmsAutomationService automations, SquareClientProvider squareProvider,
                               TelegramNotificationService telegram) {
        this(parse(businesses), bookings, forms, detector, service, automations, squareProvider, telegram,
                Clock.systemUTC());
    }

    IntakeFormScheduler(Set<Long> businesses, SquareBookingMirrorRepository bookings, IntakeFormRepository forms,
                        FirstVisitDetector detector, IntakeFormService service, SmsAutomationService automations,
                        SquareClientProvider squareProvider, TelegramNotificationService telegram, Clock clock) {
        this.businesses = businesses;
        this.bookings = bookings;
        this.forms = forms;
        this.detector = detector;
        this.service = service;
        this.automations = automations;
        this.squareProvider = squareProvider;
        this.telegram = telegram;
        this.clock = clock;
    }

    static Set<Long> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(spec.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    @SchedulerLock(name = "IntakeFormScheduler_tick", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void tick() {
        for (Long businessId : businesses) {
            if (!automations.isEnabled(businessId, AUTOMATION_KEY)) {
                continue;
            }
            runStep("detect", () -> detectNewClients(businessId));
            runStep("send", () -> sendQueued(businessId));
            runStep("reminders", () -> sendReminders(businessId));
            runStep("square", this::syncSquare);
        }
    }

    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Intake form {} step failed (retried next tick): {}", name, e.getMessage(), e);
        }
    }

    void detectNewClients(Long businessId) {
        Instant now = clock.instant();
        for (SquareBookingMirror b : bookings.findByBusinessIdAndStatusAndCreatedAtBetween(
                businessId, ACCEPTED, now.minus(MAX_AGE), now.minus(MIN_AGE))) {
            try {
                if (b.getStartAt() == null || forms.findByBusinessIdAndSquareBookingIdIn(
                        businessId, List.of(b.getSquareBookingId())).size() > 0) {
                    continue;
                }
                FirstVisitDetector.Result r = detector.evaluate(b);
                if (r.isFirstVisit()) {
                    service.create(b, r);
                }
            } catch (RuntimeException e) {
                log.warn("Intake form detection failed for booking {}: {}", b.getSquareBookingId(), e.getMessage());
            }
        }
    }

    void sendQueued(Long businessId) {
        Instant now = clock.instant();
        if (!isDaytime(now)) {
            return;
        }
        for (IntakeForm f : forms.findByStatusAndInitialSmsStateIsNull(IntakeForm.PENDING)) {
            if (!businessId.equals(f.getBusinessId())) {
                continue;
            }
            try {
                String skip = skipReason(f, now, true);
                if (skip == null) {
                    service.sendText(f, false);
                } else if (!"RETRY".equals(skip)) {
                    f.setInitialSmsState(skip);
                    f.setUpdatedAt(now);
                    forms.save(f);
                }
            } catch (RuntimeException e) {
                log.warn("Intake form text failed for form {}: {}", f.getId(), e.getMessage());
            }
        }
    }

    void sendReminders(Long businessId) {
        Instant now = clock.instant();
        if (!isDaytime(now)) {
            return;
        }
        for (IntakeForm f : forms.findByBusinessIdAndStatusAndReminderSmsStateIsNullAndAppointmentStartAtBetween(
                businessId, IntakeForm.SENT, now.plus(REMINDER_MIN_LEAD), now.plus(REMINDER_MAX_LEAD))) {
            try {
                if (f.getSentAt() == null || f.getSentAt().isAfter(now.minus(REMINDER_MIN_SINCE_FIRST))) {
                    continue;
                }
                String skip = skipReason(f, now, false);
                if (skip == null) {
                    service.sendText(f, true);
                } else if (!"RETRY".equals(skip)) {
                    f.setReminderSmsState(skip);
                    f.setUpdatedAt(now);
                    forms.save(f);
                }
            } catch (RuntimeException e) {
                log.warn("Intake form reminder failed for form {}: {}", f.getId(), e.getMessage());
            }
        }
    }

    /** null = send now; "RETRY" = look again next tick; anything else is the terminal skip state. */
    private String skipReason(IntakeForm f, Instant now, boolean initial) {
        if (f.isSigned()) {
            return "SKIPPED_SIGNED";
        }
        if (!initial && f.getStatus().equals(IntakeForm.PENDING)) {
            return "SKIPPED_NOT_SENT";
        }
        Optional<SquareBookingMirror> mirror = bookings.findByBusinessIdAndSquareBookingId(
                f.getBusinessId(), f.getSquareBookingId());
        if (mirror.isEmpty() || !ACCEPTED.equals(mirror.get().getStatus())) {
            return "SKIPPED_CANCELLED";
        }
        Instant start = mirror.get().getStartAt();
        if (start != null && !start.equals(f.getAppointmentStartAt())) {
            f.setAppointmentStartAt(start);
            forms.save(f);
        }
        if (!f.getAppointmentStartAt().isAfter(now.plus(MIN_TIME_BEFORE_VISIT))) {
            return "SKIPPED_TOO_LATE";
        }
        if (!initial && (f.getAppointmentStartAt().isBefore(now.plus(REMINDER_MIN_LEAD))
                || f.getAppointmentStartAt().isAfter(now.plus(REMINDER_MAX_LEAD)))) {
            return "RETRY";
        }
        try {
            Optional<SquareClient.Booking> live = squareProvider.forBusiness(f.getBusinessId())
                    .retrieveBooking(f.getSquareBookingId());
            if (live.isEmpty() || !ACCEPTED.equals(live.get().status())) {
                return "SKIPPED_CANCELLED";
            }
        } catch (RuntimeException e) {
            log.warn("Intake form {} postponed, Square booking check failed: {}", f.getId(), e.getMessage());
            return "RETRY";
        }
        return null;
    }

    void syncSquare() {
        for (IntakeForm f : forms.findNeedingSquareSync(MAX_SQUARE_SYNC_ATTEMPTS)) {
            if (!businesses.contains(f.getBusinessId())) {
                continue;
            }
            try {
                String line = IntakeSquareNote.line(f);
                squareProvider.forBusiness(f.getBusinessId()).updateCustomerNote(
                        f.getSquareCustomerId(), note -> IntakeSquareNote.merge(note, line));
                f.setSquareSyncedStatus(f.getStatus());
                f.setSquareSyncAttempts(0);
            } catch (RuntimeException e) {
                f.setSquareSyncAttempts(f.getSquareSyncAttempts() + 1);
                log.warn("Intake form {} Square note sync failed (attempt {}): {}", f.getId(),
                        f.getSquareSyncAttempts(), e.getMessage());
                if (f.getSquareSyncAttempts() == MAX_SQUARE_SYNC_ATTEMPTS) {
                    telegram.sendOwnerAlert(f.getBusinessId(), "Intake form: could not update the Square customer note "
                            + "after " + MAX_SQUARE_SYNC_ATTEMPTS + " tries (form #" + f.getId() + "). "
                            + "The CRM still shows the status.");
                }
            }
            f.setUpdatedAt(clock.instant());
            forms.save(f);
        }
    }

    private static boolean isDaytime(Instant now) {
        int hour = now.atZone(PACIFIC).getHour();
        return hour >= SEND_FROM_HOUR && hour < SEND_UNTIL_HOUR;
    }
}
