package com.salonreview.sms;

import com.salonreview.config.TouchupReminderProperties;
import com.salonreview.domain.ServiceLifecycleReminderSend;
import com.salonreview.domain.ServiceLifecycleRole;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.ServiceLifecycleReminderSendRepository;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Once-daily "it's been about 4 weeks, want your touch-up?" nudge — the first of this codebase's
 * lifecycle-reminder automations (see {@link ServiceLifecycleRole}, {@code
 * ServiceLifecycleRoleController}). Entirely data-driven and business-agnostic: which service
 * counts as the triggering "initial procedure" and which counts as a qualifying "touch-up" are
 * owner-configured per business, not hardcoded here — a business with nothing configured for
 * either role is silently skipped (see {@link #eligibleRoleIds}), so this automation is
 * automatically inert for business 1 (nails) and any business that hasn't set up its lifecycle
 * roles yet.
 *
 * <p>Unlike the click-tracked-link winback schedulers in this package, this one has no coupon and
 * no self-generated link, so it can go through the ordinary {@link TwilioSmsService#sendTemplated}
 * path rather than hand-rolling its own send — consent/blocked-number/automation-enabled/
 * configured-credentials are all already handled there. This scheduler's own job is only: find
 * candidates, exclude the ones who already have or booked the follow-up service, and record what
 * happened so the same procedure is never reconsidered.
 *
 * <p>Daily cron, not a fast poll — same reasoning as every other multi-day-window automation in
 * this package ({@link LapsedCustomerWinbackScheduler}, {@link RepeatCustomerWinbackScheduler}):
 * the eligibility window moves day-to-day, not minute-to-minute.
 *
 * <p><b>Real-visit check (added 2026-09-04):</b> a Square Booking that's merely not cancelled is
 * not proof the customer actually attended — a real incident found this the hard way for business
 * 2 (PMU), where a booking can exist purely from an online deposit invoice with no in-person
 * checkout ever following it; a live check found 55% of otherwise-"eligible" bookings had no
 * matching settled visit at all. Every candidate is now cross-checked against {@code
 * ProviderVisitRepository} (the same real-visit ledger {@link LapsedCustomerWinbackScheduler}/
 * {@link RepeatCustomerWinbackScheduler} already require) before being treated as a genuine
 * trigger — see {@link com.salonreview.repo.ProviderVisitRepository#existsByBusinessIdAndCustomerIdAndServiceDate}.
 */
@Component
public class TouchupReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TouchupReminderScheduler.class);
    static final String AUTOMATION_KEY = "touchup_reminder";
    static final String TEMPLATE_KEY = "touchup_reminder_nudge";

    /** DST-safe — see every other scheduler in this package's identical reasoning for computing
     * "today"/date windows in the salon's own timezone rather than the container's (UTC). */
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final ServiceLifecycleRoleRepository roleRepository;
    private final ServiceLifecycleReminderSendRepository sendRepository;
    private final ProviderVisitRepository visitRepository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsService smsService;
    private final TouchupReminderProperties properties;

    public TouchupReminderScheduler(ServiceLifecycleRoleRepository roleRepository,
                                     ServiceLifecycleReminderSendRepository sendRepository,
                                     ProviderVisitRepository visitRepository,
                                     SquareClientProvider squareClientProvider,
                                     TwilioSmsConfigRepository twilioConfigs,
                                     SmsAutomationService automationService,
                                     SmsMessageLogService messageLogService,
                                     TwilioSmsService smsService,
                                     TouchupReminderProperties properties) {
        this.roleRepository = roleRepository;
        this.sendRepository = sendRepository;
        this.visitRepository = visitRepository;
        this.squareClientProvider = squareClientProvider;
        this.twilioConfigs = twilioConfigs;
        this.automationService = automationService;
        this.messageLogService = messageLogService;
        this.smsService = smsService;
        this.properties = properties;
    }

    // Every @Scheduled(cron=...) in this codebase must set an explicit zone — see the 2026-08-07
    // unzoned-cron incident referenced throughout this package.
    @Scheduled(cron = "0 0 10 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "TouchupReminderScheduler_sendDueReminders", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now(SALON_ZONE);
        LocalDate windowNewEdge = today.minusDays(properties.getDelayDays());
        LocalDate windowOldEdge = windowNewEdge.minusDays(properties.getWindowDays());

        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            Set<String> initialProcedureIds = eligibleRoleIds(businessId, "INITIAL_PROCEDURE");
            Set<String> touchUpIds = eligibleRoleIds(businessId, "TOUCH_UP");
            if (initialProcedureIds.isEmpty() || touchUpIds.isEmpty()) {
                continue; // nothing configured for this business yet — see class doc
            }
            // Checked here, before any Square calls or idempotency rows get written — not lazily
            // inside TwilioSmsService.sendTemplated the way a normal send would be. Found live
            // 2026-08-25: sendTemplated's own disabled check happens only after this scheduler had
            // already written a permanent NOT_SENT row for that customer/procedure-date, which
            // (correctly, by design, everywhere else in this codebase) blocks ever reconsidering
            // them again — meaning every real customer whose ~4-week window passed while this
            // automation was still being configured would be silently skipped forever, even after
            // the owner turned it on. Skipping the whole business here instead means nothing is
            // recorded while off, so every candidate is simply re-evaluated fresh on the next run
            // once enabled — see design decision, 2026-08-25.
            if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
                continue;
            }

            SquareClient square;
            try {
                square = squareClientProvider.forBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Touch-up reminder run skipped for business {} (will be retried at next scheduled run): {}",
                        businessId, e.getMessage());
                continue;
            }

            List<SquareClient.Booking> candidates;
            try {
                Instant fetchStart = windowOldEdge.atStartOfDay(SALON_ZONE).toInstant();
                Instant fetchEnd = windowNewEdge.plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
                candidates = square.bookings(fetchStart, fetchEnd);
            } catch (RuntimeException e) {
                log.warn("Touch-up reminder candidate fetch failed for business {} (retrying next run): {}",
                        businessId, e.getMessage());
                continue;
            }

            for (SquareClient.Booking booking : candidates) {
                if (!SquareBookingFilters.didHappen(booking) || booking.customerId() == null) continue;
                LocalDate triggerDate = triggerDate(booking);
                if (triggerDate == null) continue;
                boolean matchesInitialProcedure = booking.appointmentSegments() != null
                        && booking.appointmentSegments().stream()
                                .anyMatch(s -> initialProcedureIds.contains(s.serviceVariationId()));
                if (!matchesInitialProcedure) continue;

                // A Square Booking merely "not cancelled" is not proof a real visit happened — see
                // ProviderVisitRepository#existsByBusinessIdAndCustomerIdAndServiceDate's own doc
                // for the real incident this guards against (business 2's online-deposit bookings
                // that were never actually attended in person).
                if (!visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(businessId, booking.customerId(), triggerDate)) {
                    continue;
                }

                if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndTriggerServiceDate(
                        businessId, AUTOMATION_KEY, booking.customerId(), triggerDate)) {
                    continue; // already considered for this exact procedure
                }
                try {
                    process(businessId, booking.customerId(), triggerDate, touchUpIds, square);
                } catch (RuntimeException e) {
                    // Fails closed: no row written for this candidate, so a transient Square
                    // failure (e.g. checking for an existing touch-up booking) just means this
                    // customer is retried on the next scheduled run rather than wrongly texted or
                    // permanently skipped — same convention as every other scheduler in this
                    // package.
                    log.warn("Touch-up reminder processing failed for customer {} (business {}); retrying next run",
                            booking.customerId(), businessId, e);
                }
            }
        }
    }

    private Set<String> eligibleRoleIds(Long businessId, String role) {
        return roleRepository.findAllByBusinessIdAndRole(businessId, role).stream()
                .map(ServiceLifecycleRole::getSquareVariationId)
                .collect(Collectors.toSet());
    }

    private static LocalDate triggerDate(SquareClient.Booking booking) {
        if (booking.startAt() == null) return null;
        try {
            return Instant.parse(booking.startAt()).atZone(SALON_ZONE).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void process(Long businessId, String customerId, LocalDate triggerDate, Set<String> touchUpIds, SquareClient square) {
        if (alreadyHasTouchUp(customerId, triggerDate, touchUpIds, square)) {
            save(businessId, customerId, triggerDate, null, null, ServiceLifecycleReminderSend.STATE_SKIPPED_ALREADY_DONE);
            return;
        }

        String phoneNumber = square.customerPhone(customerId);
        if (phoneNumber == null || phoneNumber.isBlank()) {
            save(businessId, customerId, triggerDate, null, null, ServiceLifecycleReminderSend.STATE_SKIPPED_UNRESOLVED);
            return;
        }

        if (messageLogService.hasNegativeFeedback(businessId, phoneNumber)) {
            save(businessId, customerId, triggerDate, phoneNumber, null, ServiceLifecycleReminderSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
            return;
        }

        String rawGivenName = square.customerGivenNames(List.of(customerId)).get(customerId);
        String name = com.salonreview.util.Names.capitalizeFirst(rawGivenName);
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        var result = smsService.sendTemplated(businessId, TEMPLATE_KEY, phoneNumber, Map.of("greeting", greeting));
        String state = result.sent() ? ServiceLifecycleReminderSend.STATE_SENT : ServiceLifecycleReminderSend.STATE_NOT_SENT;
        if (!result.sent()) {
            log.info("{} not sent for customer {} ({}): {}", TEMPLATE_KEY, customerId, phoneNumber, result.reason());
        }
        save(businessId, customerId, triggerDate, phoneNumber, rawGivenName, state);
    }

    /** Live check across past and upcoming bookings from the triggering procedure onward — a
     * qualifying touch-up that's already happened OR already scheduled both count, per spec. */
    private boolean alreadyHasTouchUp(String customerId, LocalDate triggerDate, Set<String> touchUpIds, SquareClient square) {
        Instant since = triggerDate.atStartOfDay(SALON_ZONE).toInstant();
        // A Square failure here propagates to the caller's catch (see sendDueReminders) rather
        // than being swallowed into "no touch-up found" — a transient error must never look like
        // a real negative answer.
        return square.bookingsForCustomer(customerId, since).stream()
                .filter(SquareBookingFilters::didHappen)
                .anyMatch(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                        .anyMatch(s -> touchUpIds.contains(s.serviceVariationId())));
    }

    private void save(Long businessId, String customerId, LocalDate triggerDate, String phoneNumber, String customerName, String state) {
        sendRepository.save(ServiceLifecycleReminderSend.builder()
                .businessId(businessId)
                .automationKey(AUTOMATION_KEY)
                .squareCustomerId(customerId)
                .triggerServiceDate(triggerDate)
                .phoneNumber(phoneNumber)
                .customerName(customerName)
                .state(state)
                .build());
    }
}
