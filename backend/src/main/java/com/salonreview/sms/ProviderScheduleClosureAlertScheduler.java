package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.domain.Provider;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.square.SquareScheduleData;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Observes large losses of online booking opportunities. Does not infer who changed a calendar. */
@Component
public class ProviderScheduleClosureAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(ProviderScheduleClosureAlertScheduler.class);
    static final String AUTOMATION_KEY = "provider_schedule_closure_alert";
    private final BusinessRepository businesses;
    private final SmsAutomationService automations;
    private final ProviderScheduleClosureAlertConfigService config;
    private final SquareClientProvider clients;
    private final ProviderRepository providers;
    private final SquareBookingMirrorRepository bookingMirror;
    private final CurrentBusinessContext context;
    private final ProviderScheduleObservationReader reader;
    private final ProviderScheduleChangeStore store;
    private final Clock clock;

    @Autowired
    public ProviderScheduleClosureAlertScheduler(BusinessRepository businesses, SmsAutomationService automations,
            ProviderScheduleClosureAlertConfigService config, SquareClientProvider clients, ProviderRepository providers,
            SquareBookingMirrorRepository bookingMirror, CurrentBusinessContext context,
            ProviderScheduleObservationReader reader, ProviderScheduleChangeStore store) {
        this(businesses, automations, config, clients, providers, bookingMirror, context, reader, store, Clock.systemUTC());
    }

    ProviderScheduleClosureAlertScheduler(BusinessRepository businesses, SmsAutomationService automations,
            ProviderScheduleClosureAlertConfigService config, SquareClientProvider clients, ProviderRepository providers,
            SquareBookingMirrorRepository bookingMirror, CurrentBusinessContext context,
            ProviderScheduleObservationReader reader, ProviderScheduleChangeStore store, Clock clock) {
        this.businesses = businesses;
        this.automations = automations;
        this.config = config;
        this.clients = clients;
        this.providers = providers;
        this.bookingMirror = bookingMirror;
        this.context = context;
        this.reader = reader;
        this.store = store;
        this.clock = clock;
    }

    // Ten-minute sampling permits a genuine >=20-minute confirmation without depending on the
    // response time of exactly the next twenty-minute poll. State survives either replica/restarts.
    @Scheduled(cron = "0 */10 * * * *", zone = "UTC")
    @SchedulerLock(name = "ProviderScheduleClosureAlertScheduler_poll", lockAtLeastFor = "PT5M", lockAtMostFor = "PT9M")
    public void poll() {
        for (Business business : businesses.findAllByActiveTrue()) {
            if (!automations.isEnabled(business.getId(), AUTOMATION_KEY)) continue;
            context.runAs(business.getId(), () -> {
                try {
                    pollBusiness(business);
                } catch (RuntimeException exception) {
                    log.warn("Availability observation failed for business {} ({})", business.getId(), exception.getClass().getSimpleName());
                    store.failure(business.getId(), "business", Instant.now(clock), "INVALID_RESPONSE");
                } finally {
                    // Failed/unsupported reads also create diagnostic history. Apply retention
                    // even when no provider reached the successful observation path.
                    store.cleanHistory(business.getId(), Instant.now(clock));
                }
            });
        }
    }

    private record Read(String teamId, String name, ProviderAvailabilityObservation.Contract contract,
                        List<ProviderAvailabilityObservation.Slot> primary,
                        List<ProviderAvailabilityObservation.Slot> secondary) {}

    private void pollBusiness(Business business) {
        Long businessId = business.getId();
        SquareClient square = clients.forBusiness(businessId);
        if (!square.hasCompleteScheduleBookingAccess()) {
            store.failure(businessId, "business", Instant.now(clock), "INCOMPLETE_BOOKINGS");
            return;
        }
        var profile = square.scheduleBusinessProfile();
        if (!Boolean.TRUE.equals(profile.bookingEnabled())) {
            store.failure(businessId, "business", Instant.now(clock), "BOOKING_DISABLED");
            return;
        }
        var policy = config.getSettings(businessId);
        var services = square.scheduleBookableServices();
        Set<String> active = square.activeTeamMembers().stream().map(SquareClient.TeamMember::id).collect(Collectors.toSet());
        var teams = square.scheduleBookableTeamMembers();
        Instant start = Instant.now(clock);
        Instant end = start.plus(Duration.ofHours(policy.noticeThresholdHours() + 24L));
        var historical = bookingMirror.findByBusinessIdAndStartAtBetween(businessId, start.minus(Duration.ofDays(90)), start);
        Map<String, Map<String, Long>> popularity = new HashMap<>();
        for (var booking : historical) {
            if (!"ACCEPTED".equals(booking.getStatus()) || booking.getAppointmentSegments() == null) continue;
            for (var segment : booking.getAppointmentSegments()) {
                if (segment.teamMemberId() == null || segment.serviceVariationId() == null) continue;
                popularity.computeIfAbsent(segment.teamMemberId(), ignored -> new HashMap<>())
                        .merge(segment.serviceVariationId(), 1L, Long::sum);
            }
        }
        List<Read> reads = new ArrayList<>();
        for (var team : teams) {
            if (!active.contains(team.teamMemberId())) continue;
            try {
                var previous = store.latest(businessId, team.teamMemberId());
                var contract = reader.contract(square.scheduleLocationId(), team.teamMemberId(), business.getTimezone(),
                        profile.businessAppointmentSettings(), policy, services,
                        previous == null ? null : previous.contract(), popularity.getOrDefault(team.teamMemberId(), Map.of()));
                var primary = reader.slots(square.scheduleAvailability(team.teamMemberId(), contract.primary().serviceId(), start, end),
                        contract, contract.primary(), start, end);
                var secondary = reader.slots(square.scheduleAvailability(team.teamMemberId(), contract.secondary().serviceId(), start, end),
                        contract, contract.secondary(), start, end);
                String name = providers.findBySquareTeamMemberIdAndBusinessId(team.teamMemberId(), businessId)
                        .map(Provider::getDisplayName).filter(value -> value != null && !value.isBlank())
                        .orElse(team.displayName() == null || team.displayName().isBlank() ? "Provider" : team.displayName());
                reads.add(new Read(team.teamMemberId(), name, contract, primary, secondary));
            } catch (RuntimeException exception) {
                log.warn("Availability probe skipped for business {}, team {} ({})", businessId, team.teamMemberId(), exception.getClass().getSimpleName());
                store.failure(businessId, team.teamMemberId(), Instant.now(clock), "INVALID_PROBE");
            }
        }
        if (reads.isEmpty()) return;
        // Read bookings AFTER availability and once per business. No ten-minute cache or ingest.
        List<SquareScheduleData.Booking> bookings;
        try {
            bookings = square.scheduleBookings(start.minus(ProviderScheduleObservationReader.BOOKING_LOOKBACK), end);
        } catch (RuntimeException exception) {
            reads.forEach(read -> store.failure(businessId, read.teamId(), Instant.now(clock), "INCOMPLETE_BOOKINGS"));
            return;
        }
        Instant capturedAt = Instant.now(clock);
        for (Read read : reads) {
            try {
                if (Duration.between(start, capturedAt).compareTo(Duration.ofMinutes(5)) > 0)
                    throw new IllegalStateException("Schedule read exceeded freshness budget");
                var observation = reader.observation(capturedAt, start, end, read.contract(), read.primary(), read.secondary(), bookings);
                store.observe(businessId, read.name(), observation);
            } catch (RuntimeException exception) {
                log.warn("Availability evidence skipped for business {}, team {} ({})", businessId, read.teamId(), exception.getClass().getSimpleName());
                store.failure(businessId, read.teamId(), capturedAt, "INVALID_RESPONSE");
            }
        }
    }
}
