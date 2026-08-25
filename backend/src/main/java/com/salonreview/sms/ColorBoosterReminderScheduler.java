package com.salonreview.sms;

import com.salonreview.config.ColorBoosterReminderProperties;
import com.salonreview.domain.ServiceLifecycleReminderSend;
import com.salonreview.domain.ServiceLifecycleRole;
import com.salonreview.domain.TwilioSmsConfig;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Once-daily "it's been about a year, want your color booster?" nudge — the second lifecycle-
 * reminder automation in this package (see {@link ServiceLifecycleRole},
 * {@code ServiceLifecycleRoleController}), sharing the {@code INITIAL_PROCEDURE} role with
 * {@link TouchupReminderScheduler} (the same qualifying procedure anchors both reminders) but
 * introducing its own {@code COLOR_BOOSTER} role. Entirely data-driven and business-agnostic —
 * see that class's own doc for why a business with nothing configured for either role is
 * automatically inert.
 *
 * <p>Structurally different from {@link TouchupReminderScheduler} in one real way: touch-up fires
 * once per specific procedure (a narrow day-window catch, one row ever per triggering booking).
 * This one is a genuinely <b>recurring</b> reminder — "due" is "at least N days since the most
 * recent qualifying event," an open-ended condition that stays true indefinitely until the
 * customer actually books a color booster, so a customer who never books would otherwise get
 * reminded on every single daily run forever. {@link ColorBoosterReminderProperties#getCooldownDays}
 * is what turns that into an actual "roughly annual" cadence instead — same real behavior
 * {@link RepeatCustomerWinbackScheduler}'s own cooldown already has (it also keeps re-nagging a
 * customer who never returns, just gated to once per cooldown window rather than every day).
 *
 * <p>Candidate discovery has to hit Square directly, same reason as touch-up: {@code
 * provider_visit} carries no service identity, so there is no cheaper SQL-only path (contrast
 * {@code RepeatCustomerWinbackEligibilityRepository}, which can query {@code provider_visit}
 * directly because it only cares about visit dates, not which specific service was performed).
 */
@Component
public class ColorBoosterReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ColorBoosterReminderScheduler.class);
    static final String AUTOMATION_KEY = "color_booster_reminder";
    static final String TEMPLATE_KEY = "color_booster_reminder_nudge";

    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final ServiceLifecycleRoleRepository roleRepository;
    private final ServiceLifecycleReminderSendRepository sendRepository;
    private final SquareClientProvider squareClientProvider;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsAutomationService automationService;
    private final SmsMessageLogService messageLogService;
    private final TwilioSmsService smsService;
    private final ColorBoosterReminderProperties properties;

    public ColorBoosterReminderScheduler(ServiceLifecycleRoleRepository roleRepository,
                                          ServiceLifecycleReminderSendRepository sendRepository,
                                          SquareClientProvider squareClientProvider,
                                          TwilioSmsConfigRepository twilioConfigs,
                                          SmsAutomationService automationService,
                                          SmsMessageLogService messageLogService,
                                          TwilioSmsService smsService,
                                          ColorBoosterReminderProperties properties) {
        this.roleRepository = roleRepository;
        this.sendRepository = sendRepository;
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
    @SchedulerLock(name = "ColorBoosterReminderScheduler_sendDueReminders", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now(SALON_ZONE);
        LocalDate eligibleBeforeDate = today.minusDays(properties.getEligibilityDays());
        LocalDate lookbackFloor = today.minusDays(properties.getMaxLookbackDays());
        Instant cooldownCutoff = Instant.now().minus(properties.getCooldownDays(), java.time.temporal.ChronoUnit.DAYS);

        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            Set<String> initialProcedureIds = eligibleRoleIds(businessId, "INITIAL_PROCEDURE");
            Set<String> colorBoosterIds = eligibleRoleIds(businessId, "COLOR_BOOSTER");
            if (initialProcedureIds.isEmpty() || colorBoosterIds.isEmpty()) {
                continue; // nothing configured for this business yet — see class doc
            }
            // Checked here, before any Square calls, same reasoning as TouchupReminderScheduler's
            // own identical guard — a disabled automation must never leave any trace that could
            // suppress a real reminder once the owner turns it on.
            if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
                continue;
            }

            Set<String> qualifyingIds = new HashSet<>(initialProcedureIds);
            qualifyingIds.addAll(colorBoosterIds);

            SquareClient square;
            try {
                square = squareClientProvider.forBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Color-booster reminder run skipped for business {} (will be retried at next scheduled run): {}",
                        businessId, e.getMessage());
                continue;
            }

            Set<String> candidateCustomerIds;
            try {
                Instant fetchStart = lookbackFloor.atStartOfDay(SALON_ZONE).toInstant();
                Instant fetchEnd = eligibleBeforeDate.plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
                candidateCustomerIds = square.bookings(fetchStart, fetchEnd).stream()
                        .filter(SquareBookingFilters::didHappen)
                        .filter(b -> b.customerId() != null)
                        .filter(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                                .anyMatch(s -> qualifyingIds.contains(s.serviceVariationId())))
                        .map(SquareClient.Booking::customerId)
                        .collect(Collectors.toSet());
            } catch (RuntimeException e) {
                log.warn("Color-booster reminder candidate fetch failed for business {} (retrying next run): {}",
                        businessId, e.getMessage());
                continue;
            }

            for (String customerId : candidateCustomerIds) {
                if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
                        businessId, AUTOMATION_KEY, customerId, ServiceLifecycleReminderSend.STATE_SENT, cooldownCutoff)) {
                    continue; // already reminded within the cooldown window — quiet skip, no row
                }
                try {
                    process(businessId, customerId, today, eligibleBeforeDate, lookbackFloor, qualifyingIds, colorBoosterIds, square);
                } catch (RuntimeException e) {
                    // Fails closed, same convention as TouchupReminderScheduler: no row written,
                    // this customer is simply retried on the next scheduled run.
                    log.warn("Color-booster reminder processing failed for customer {} (business {}); retrying next run",
                            customerId, businessId, e);
                }
            }
        }
    }

    private Set<String> eligibleRoleIds(Long businessId, String role) {
        return roleRepository.findAllByBusinessIdAndRole(businessId, role).stream()
                .map(ServiceLifecycleRole::getSquareVariationId)
                .collect(Collectors.toSet());
    }

    private void process(Long businessId, String customerId, LocalDate today, LocalDate eligibleBeforeDate,
                          LocalDate lookbackFloor, Set<String> qualifyingIds, Set<String> colorBoosterIds, SquareClient square) {
        Instant since = lookbackFloor.atStartOfDay(SALON_ZONE).toInstant();
        // One Square call answers both questions this needs: the customer's true most recent
        // qualifying event (not just "some qualifying event was seen in the wide candidate scan" —
        // see class doc on why that scan alone can't tell a genuinely-due customer apart from one
        // who already got a recent booster that simply falls outside the scan's own upper bound)
        // and whether a color booster is already scheduled.
        List<SquareClient.Booking> history = square.bookingsForCustomer(customerId, since);

        LocalDate lastQualifyingDate = history.stream()
                .filter(SquareBookingFilters::didHappen)
                .filter(b -> !SquareBookingFilters.isTodayOrLater(b.startAt(), today))
                .filter(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                        .anyMatch(s -> qualifyingIds.contains(s.serviceVariationId())))
                .map(ColorBoosterReminderScheduler::bookingDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (lastQualifyingDate == null) {
            return; // shouldn't happen (they were found by the wide scan) — defensive, no row
        }
        if (lastQualifyingDate.isAfter(eligibleBeforeDate)) {
            return; // has a more recent qualifying event than the candidate scan's window implied — not actually due yet
        }

        boolean hasUpcomingColorBooster = history.stream()
                .filter(SquareBookingFilters::didHappen)
                .filter(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today))
                .anyMatch(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                        .anyMatch(s -> colorBoosterIds.contains(s.serviceVariationId())));
        if (hasUpcomingColorBooster) {
            save(businessId, customerId, today, null, null, ServiceLifecycleReminderSend.STATE_SKIPPED_ALREADY_DONE);
            return;
        }

        String phoneNumber = square.customerPhone(customerId);
        if (phoneNumber == null || phoneNumber.isBlank()) {
            save(businessId, customerId, today, null, null, ServiceLifecycleReminderSend.STATE_SKIPPED_UNRESOLVED);
            return;
        }

        if (messageLogService.hasNegativeFeedback(businessId, phoneNumber)) {
            save(businessId, customerId, today, phoneNumber, null, ServiceLifecycleReminderSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
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
        save(businessId, customerId, today, phoneNumber, rawGivenName, state);
    }

    private static LocalDate bookingDate(SquareClient.Booking booking) {
        if (booking.startAt() == null) return null;
        try {
            return Instant.parse(booking.startAt()).atZone(SALON_ZONE).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code triggerServiceDate} is today's evaluation date, not the qualifying event's own date
     * — this is a recurring reminder (see class doc), so a fresh, always-unique-per-day value is
     * what lets the same customer be re-recorded on a later run once the cooldown lapses, without
     * colliding with the table's own (business, automation, customer, trigger_date) uniqueness. */
    private void save(Long businessId, String customerId, LocalDate today, String phoneNumber, String customerName, String state) {
        sendRepository.save(ServiceLifecycleReminderSend.builder()
                .businessId(businessId)
                .automationKey(AUTOMATION_KEY)
                .squareCustomerId(customerId)
                .triggerServiceDate(today)
                .phoneNumber(phoneNumber)
                .customerName(customerName)
                .state(state)
                .build());
    }
}
