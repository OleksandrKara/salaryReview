package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.domain.Provider;
import com.salonreview.domain.ProviderAvailabilitySnapshot;
import com.salonreview.domain.ProviderScheduleClosureAlert;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ProviderAvailabilitySnapshotRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.ProviderScheduleClosureAlertRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alerts the staff Telegram chat when a provider blocks part of their own Square calendar with
 * less than a day's notice — owner request 2026-09-02, business 1 only (see V158). Square has no
 * API or webhook for a team member's own schedule/time-off changes (confirmed against Square's own
 * docs and developer forum — {@code team_member.updated}'s payload never carries schedule data),
 * so this polls {@link SquareClient#availableSlotStarts} (deliberately uncached, unlike every
 * other Square read in this codebase — its whole purpose is detecting change between polls) and
 * diffs against the previous snapshot stored in {@code provider_availability_snapshot}. A slot
 * present last poll and missing this poll, starting less than {@link #NOTICE_THRESHOLD} from now
 * and not explained by a real customer booking (see {@link #hasRealBooking}), is treated as the
 * provider having just closed it.
 *
 * <p>Anti-spam (owner's explicit requirement — a provider very often blocks a whole remaining day
 * at once, not one slot at a time): every newly-closed slot found for the same provider in the
 * same poll is grouped into exactly one {@link ProviderScheduleClosureAlert} row / one Telegram
 * message, never one per slot. Re-alerting on an already-reported closure is avoided for free by
 * the diff-against-only-the-immediately-prior-snapshot design: a slot missing in snapshot N stays
 * missing in snapshot N+1 too, so it can never again appear as "newly missing" in a later diff —
 * no separate "already alerted" bookkeeping table is needed.
 *
 * <p>Square's availability search needs a specific {@code service_variation_id}, not just a
 * {@code team_member_id} — there is no "is this provider free at all" query. {@link
 * #representativeServiceVariationId} picks the single most-frequently-booked service variation
 * across a business's recent bookings as a pragmatic single-service-per-business proxy, reasoned
 * good enough for a nail salon where most/all providers can perform the primary service.
 */
@Component
public class ProviderScheduleClosureAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProviderScheduleClosureAlertScheduler.class);
    static final String AUTOMATION_KEY = "provider_schedule_closure_alert";

    private static final Duration LOOKAHEAD = Duration.ofHours(48);
    private static final Duration NOTICE_THRESHOLD = Duration.ofHours(24);
    private static final Duration BOOKING_MATCH_WINDOW = Duration.ofMinutes(30);
    private static final Duration REPRESENTATIVE_SERVICE_LOOKBACK = Duration.ofDays(90);

    private static final Set<String> DID_NOT_HAPPEN_STATUSES =
            Set.of("CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW");

    private final BusinessRepository businessRepository;
    private final SmsAutomationService automationService;
    private final SquareClientProvider squareClientProvider;
    private final ProviderRepository providerRepository;
    private final ProviderAvailabilitySnapshotRepository snapshotRepository;
    private final ProviderScheduleClosureAlertRepository alertRepository;
    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final TelegramNotificationService telegramService;
    private final Clock clock;

    @Autowired
    public ProviderScheduleClosureAlertScheduler(BusinessRepository businessRepository,
                                                  SmsAutomationService automationService,
                                                  SquareClientProvider squareClientProvider,
                                                  ProviderRepository providerRepository,
                                                  ProviderAvailabilitySnapshotRepository snapshotRepository,
                                                  ProviderScheduleClosureAlertRepository alertRepository,
                                                  SquareBookingMirrorRepository bookingMirrorRepository,
                                                  TelegramNotificationService telegramService) {
        this(businessRepository, automationService, squareClientProvider, providerRepository, snapshotRepository,
                alertRepository, bookingMirrorRepository, telegramService, Clock.systemUTC());
    }

    /** Test-only: a fixed {@link Clock} stands in for "now" — same pattern established for
     * {@code LaborDayPromoOneOffService}/{@code PmuThankYouOfferOneOffService}, avoiding the exact
     * class of hardcoded-real-clock test breakage already hit twice in this codebase. */
    ProviderScheduleClosureAlertScheduler(BusinessRepository businessRepository,
                                           SmsAutomationService automationService,
                                           SquareClientProvider squareClientProvider,
                                           ProviderRepository providerRepository,
                                           ProviderAvailabilitySnapshotRepository snapshotRepository,
                                           ProviderScheduleClosureAlertRepository alertRepository,
                                           SquareBookingMirrorRepository bookingMirrorRepository,
                                           TelegramNotificationService telegramService,
                                           Clock clock) {
        this.businessRepository = businessRepository;
        this.automationService = automationService;
        this.squareClientProvider = squareClientProvider;
        this.providerRepository = providerRepository;
        this.snapshotRepository = snapshotRepository;
        this.alertRepository = alertRepository;
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.telegramService = telegramService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1_200_000, initialDelay = 60_000)
    @SchedulerLock(name = "ProviderScheduleClosureAlertScheduler_poll", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void poll() {
        for (Business business : businessRepository.findAllByActiveTrue()) {
            Long businessId = business.getId();
            if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
                continue;
            }
            try {
                pollBusiness(businessId);
            } catch (RuntimeException e) {
                log.warn("Provider schedule-closure poll failed for business {} (retrying next run): {}",
                        businessId, e.getMessage());
            }
        }
    }

    private void pollBusiness(Long businessId) {
        String serviceVariationId = representativeServiceVariationId(businessId);
        if (serviceVariationId == null) {
            return; // no recent bookings to infer a representative service from yet
        }

        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Provider schedule-closure poll skipped for business {} (Square not connected): {}",
                    businessId, e.getMessage());
            return;
        }

        Instant now = Instant.now(clock);
        for (SquareClient.TeamMember teamMember : square.activeTeamMembers()) {
            try {
                pollProvider(businessId, serviceVariationId, teamMember, square, now);
            } catch (RuntimeException e) {
                log.warn("Provider schedule-closure check failed for team member {} (business {}); retrying next run",
                        teamMember.id(), businessId, e);
            }
        }
    }

    private void pollProvider(Long businessId, String serviceVariationId, SquareClient.TeamMember teamMember,
                               SquareClient square, Instant now) {
        String teamMemberId = teamMember.id();

        List<Instant> currentSlots = square.availableSlotStarts(
                teamMemberId, serviceVariationId, now, now.plus(LOOKAHEAD));
        Set<Instant> currentSet = new HashSet<>(currentSlots);

        List<ProviderAvailabilitySnapshot> previousRows =
                snapshotRepository.findByBusinessIdAndTeamMemberId(businessId, teamMemberId);
        Set<Instant> previousSet = previousRows.stream()
                .map(ProviderAvailabilitySnapshot::getSlotStartAt)
                .collect(Collectors.toSet());

        List<Instant> closures = previousSet.stream()
                .filter(slot -> !currentSet.contains(slot))
                // Only "less than a day's notice" closures are alert-worthy — a slot removed while
                // still comfortably in the future is normal advance schedule planning.
                .filter(slot -> slot.isAfter(now) && Duration.between(now, slot).compareTo(NOTICE_THRESHOLD) < 0)
                // A slot a customer just booked disappears from availability too — that's not a
                // provider-initiated closure, so it must not alert.
                .filter(slot -> !hasRealBooking(businessId, teamMemberId, slot))
                .sorted()
                .toList();

        if (!closures.isEmpty()) {
            alert(businessId, teamMember, closures);
        }

        // Replace the snapshot with current reality regardless of whether anything alerted —
        // this is what makes non-repetition across polls free (see class doc): a slot missing now
        // stays missing in the next diff too, so it never alerts twice.
        snapshotRepository.deleteByBusinessIdAndTeamMemberId(businessId, teamMemberId);
        snapshotRepository.saveAll(currentSlots.stream()
                .map(slot -> ProviderAvailabilitySnapshot.builder()
                        .businessId(businessId)
                        .teamMemberId(teamMemberId)
                        .slotStartAt(slot)
                        .build())
                .toList());
    }

    private void alert(Long businessId, SquareClient.TeamMember teamMember, List<Instant> closures) {
        String providerName = providerRepository.findBySquareTeamMemberId(teamMember.id())
                .map(Provider::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElseGet(() -> {
                    String fullName = teamMember.fullName();
                    return (fullName == null || fullName.isBlank()) ? teamMember.id() : fullName;
                });

        Instant earliest = closures.get(0);
        Instant latest = closures.get(closures.size() - 1);

        alertRepository.save(ProviderScheduleClosureAlert.builder()
                .businessId(businessId)
                .teamMemberId(teamMember.id())
                .teamMemberName(providerName)
                .slotCount(closures.size())
                .earliestSlotAt(earliest)
                .latestSlotAt(latest)
                .build());

        telegramService.sendProviderScheduleClosureAlert(businessId, providerName, closures.size(), earliest, latest);
    }

    /** {@code true} if a real, non-cancelled booking for this provider exists within {@link
     * #BOOKING_MATCH_WINDOW} of the slot — a slot a customer just booked disappears from
     * availability the same way a provider-blocked slot does, and only the latter is a closure
     * worth alerting on. */
    private boolean hasRealBooking(Long businessId, String teamMemberId, Instant slotStart) {
        List<SquareBookingMirror> nearby = bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                businessId, slotStart.minus(BOOKING_MATCH_WINDOW), slotStart.plus(BOOKING_MATCH_WINDOW));
        return nearby.stream()
                .filter(b -> !DID_NOT_HAPPEN_STATUSES.contains(b.getStatus()))
                .anyMatch(b -> b.getAppointmentSegments() != null && b.getAppointmentSegments().stream()
                        .anyMatch(seg -> teamMemberId.equals(seg.teamMemberId())));
    }

    /** The single most-frequently-booked service variation across this business's recent bookings
     * — see class doc for why this stand-in for "is this provider free" is necessary and
     * considered good enough here. {@code null} if there's no recent booking history at all yet. */
    private String representativeServiceVariationId(Long businessId) {
        Instant since = Instant.now(clock).minus(REPRESENTATIVE_SERVICE_LOOKBACK);
        List<SquareBookingMirror> recent = bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                businessId, since, Instant.now(clock));

        return recent.stream()
                .filter(b -> b.getAppointmentSegments() != null)
                .flatMap(b -> b.getAppointmentSegments().stream())
                .map(SquareBookingMirror.Segment::serviceVariationId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()))
                .entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }
}
