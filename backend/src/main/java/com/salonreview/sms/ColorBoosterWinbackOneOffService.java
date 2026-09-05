package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.ServiceLifecycleReminderSend;
import com.salonreview.domain.ServiceLifecycleRole;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.ServiceLifecycleReminderSendRepository;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-time win-back email for business 2 customers who are 1-3 years overdue for a color booster
 * and were never reached by {@link ColorBoosterReminderScheduler}'s own recurring SMS nudge (owner
 * request 2026-09-05, after a live count found ~116 such customers, only ~2 of whom that
 * automation could actually verify via {@code provider_visit} before its own backfill window was
 * widened — see {@code ProviderVisitStartup}'s 2026-09-05 doc for that fix). Not a
 * {@code @Scheduled} component: triggered once, manually, via {@link ColorBoosterWinbackOneOffController}.
 *
 * <p>Shares {@code ColorBoosterReminderScheduler}'s exact candidate-discovery and real-visit
 * cross-check logic (same 365-1095 day window, same {@code provider_visit} proof requirement) —
 * this is a different channel (email, not SMS) and a different template for the same underlying
 * "you're overdue" fact, not a way to bypass that automation's own safety bar.
 *
 * <p>Idempotency: logged into the same {@code service_lifecycle_reminder_send} table the recurring
 * automations use, under the {@code color_booster_winback_oneoff} key, so re-running this (e.g. to
 * pick up stragglers who lacked an email address the first time and later added one) never
 * double-emails a customer who was already actually sent one.
 */
@Service
public class ColorBoosterWinbackOneOffService {

    private static final Logger log = LoggerFactory.getLogger(ColorBoosterWinbackOneOffService.class);
    static final String AUTOMATION_KEY = "color_booster_winback_oneoff";
    private static final String TEMPLATE_KEY = "color_booster_winback_oneoff";
    private static final String BOOKING_LINK = "https://book.pmu-annakara.com/?book=color-booster";
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");
    private static final int ELIGIBILITY_DAYS = 365;
    private static final int MAX_LOOKBACK_DAYS = 1095;

    public record CandidateResult(String squareCustomerId, String email, String state, String detail) {}

    private final ServiceLifecycleRoleRepository roleRepository;
    private final ServiceLifecycleReminderSendRepository sendRepository;
    private final ProviderVisitRepository visitRepository;
    private final ProviderRepository providerRepository;
    private final SquareClientProvider squareClientProvider;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;

    public ColorBoosterWinbackOneOffService(ServiceLifecycleRoleRepository roleRepository,
                                             ServiceLifecycleReminderSendRepository sendRepository,
                                             ProviderVisitRepository visitRepository,
                                             ProviderRepository providerRepository,
                                             SquareClientProvider squareClientProvider,
                                             MailchimpConfigRepository mailchimpConfigRepository,
                                             MailchimpEmailService mailchimpEmailService,
                                             MailchimpEmailTemplateService templateService) {
        this.roleRepository = roleRepository;
        this.sendRepository = sendRepository;
        this.visitRepository = visitRepository;
        this.providerRepository = providerRepository;
        this.squareClientProvider = squareClientProvider;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
    }

    /** {@code dryRun}: when true, resolves and renders everything but never calls Mailchimp and
     * never writes a row — the state reported is {@code WOULD_SEND} instead of {@code SENT}, safe
     * to run against production as many times as needed while reviewing the list. */
    public List<CandidateResult> run(Long businessId, boolean dryRun) {
        List<CandidateResult> results = new ArrayList<>();
        LocalDate today = LocalDate.now(SALON_ZONE);
        LocalDate eligibleBeforeDate = today.minusDays(ELIGIBILITY_DAYS);
        LocalDate lookbackFloor = today.minusDays(MAX_LOOKBACK_DAYS);

        Set<String> initialProcedureIds = eligibleRoleIds(businessId, "INITIAL_PROCEDURE");
        Set<String> colorBoosterIds = eligibleRoleIds(businessId, "COLOR_BOOSTER");
        if (initialProcedureIds.isEmpty() || colorBoosterIds.isEmpty()) {
            return results; // nothing configured for this business
        }

        MailchimpConfig config = mailchimpConfigRepository.findByBusinessId(businessId).orElse(null);
        if (config == null || !config.isConfigured()) {
            results.add(new CandidateResult(null, null, "SKIPPED_NOT_CONFIGURED", "Mailchimp not configured for business " + businessId));
            return results;
        }

        Set<String> qualifyingIds = new HashSet<>(initialProcedureIds);
        qualifyingIds.addAll(colorBoosterIds);

        SquareClient square = squareClientProvider.forBusiness(businessId);

        Instant fetchStart = lookbackFloor.atStartOfDay(SALON_ZONE).toInstant();
        Instant fetchEnd = eligibleBeforeDate.plusDays(1).atStartOfDay(SALON_ZONE).toInstant();
        Set<String> candidateCustomerIds = square.bookings(fetchStart, fetchEnd).stream()
                .filter(SquareBookingFilters::didHappen)
                .filter(b -> b.customerId() != null)
                .filter(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                        .anyMatch(s -> qualifyingIds.contains(s.serviceVariationId())))
                .filter(b -> {
                    LocalDate d = bookingDate(b);
                    return d != null && visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(businessId, b.customerId(), d);
                })
                .map(SquareClient.Booking::customerId)
                .collect(Collectors.toSet());

        for (String customerId : candidateCustomerIds) {
            if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
                    businessId, AUTOMATION_KEY, customerId, ServiceLifecycleReminderSend.STATE_SENT, Instant.EPOCH)) {
                continue; // already actually sent by this campaign before — never re-sent, even on a re-run
            }
            try {
                results.add(process(businessId, customerId, today, eligibleBeforeDate, lookbackFloor,
                        qualifyingIds, colorBoosterIds, square, config, dryRun));
            } catch (RuntimeException e) {
                log.warn("Color-booster winback one-off failed for customer {} (business {}): {}",
                        customerId, businessId, e.getMessage(), e);
                results.add(new CandidateResult(customerId, null, "ERROR", e.getMessage()));
            }
        }
        return results;
    }

    private CandidateResult process(Long businessId, String customerId, LocalDate today, LocalDate eligibleBeforeDate,
                                     LocalDate lookbackFloor, Set<String> qualifyingIds, Set<String> colorBoosterIds,
                                     SquareClient square, MailchimpConfig config, boolean dryRun) {
        Instant since = lookbackFloor.atStartOfDay(SALON_ZONE).toInstant();
        List<SquareClient.Booking> history = square.bookingsForCustomer(customerId, since);

        SquareClient.Booking lastQualifying = history.stream()
                .filter(SquareBookingFilters::didHappen)
                .filter(b -> !SquareBookingFilters.isTodayOrLater(b.startAt(), today))
                .filter(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                        .anyMatch(s -> qualifyingIds.contains(s.serviceVariationId())))
                .filter(b -> {
                    LocalDate d = bookingDate(b);
                    return d != null && visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(businessId, customerId, d);
                })
                .max((a, b) -> bookingDate(a).compareTo(bookingDate(b)))
                .orElse(null);
        if (lastQualifying == null) {
            return new CandidateResult(customerId, null, "SKIPPED_UNVERIFIED", "No provider_visit-verified qualifying visit found");
        }
        LocalDate lastQualifyingDate = bookingDate(lastQualifying);
        if (lastQualifyingDate.isAfter(eligibleBeforeDate)) {
            return new CandidateResult(customerId, null, "SKIPPED_NOT_DUE", "Last qualifying visit " + lastQualifyingDate + " is not yet overdue");
        }

        boolean hasUpcomingColorBooster = history.stream()
                .filter(SquareBookingFilters::didHappen)
                .filter(b -> SquareBookingFilters.isTodayOrLater(b.startAt(), today))
                .anyMatch(b -> b.appointmentSegments() != null && b.appointmentSegments().stream()
                        .anyMatch(s -> colorBoosterIds.contains(s.serviceVariationId())));
        if (hasUpcomingColorBooster) {
            return new CandidateResult(customerId, null, "SKIPPED_ALREADY_BOOKED", "Already has an upcoming color booster");
        }

        String email = square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            return new CandidateResult(customerId, null, "SKIPPED_NO_EMAIL", null);
        }

        String rawGivenName = square.customerGivenNames(List.of(customerId)).get(customerId);
        String givenName = Names.capitalizeFirst(rawGivenName);
        String providerName = technicianFirstName(lastQualifying, businessId);
        String timeSince = formatTimeSince(lastQualifyingDate, today);

        Map<String, String> vars = new HashMap<>();
        vars.put("FNAME", givenName == null ? "there" : givenName);
        vars.put("TIME_SINCE", timeSince);
        vars.put("PROVIDER_CLAUSE", providerName == null ? "" : " with " + providerName);
        vars.put("LINK", BOOKING_LINK);

        Optional<String> html = templateService.render(businessId, TEMPLATE_KEY, vars);
        if (html.isEmpty()) {
            return new CandidateResult(customerId, email, "SKIPPED_NO_TEMPLATE", null);
        }

        if (dryRun) {
            return new CandidateResult(customerId, email, "WOULD_SEND", timeSince + (providerName == null ? "" : " with " + providerName));
        }

        String subjectLine = "It's been " + timeSince + " since your last procedure";
        String previewText = "Time for your color booster";
        String campaignTitle = AUTOMATION_KEY + " - " + customerId;
        try {
            mailchimpEmailService.sendWinbackEmail(config, email, subjectLine, previewText, campaignTitle, html.get());
            save(businessId, customerId, today, email, rawGivenName, ServiceLifecycleReminderSend.STATE_SENT);
            return new CandidateResult(customerId, email, "SENT", timeSince);
        } catch (Exception e) {
            save(businessId, customerId, today, email, rawGivenName, "SEND_FAILED");
            log.warn("Color-booster winback one-off email send failed for customer {} (business {}): {}",
                    customerId, businessId, e.getMessage());
            return new CandidateResult(customerId, email, "SEND_FAILED", e.getMessage());
        }
    }

    /** Best-effort — the qualifying booking's own first segment names the real Square team member
     * id who performed it, matched against this business's own {@link Provider#getSquareTeamMemberIds()}.
     * {@code null} if unresolvable, same degrade-gracefully convention as
     * {@code PreVisitNurtureScheduler#technicianFirstName}. Deliberately does NOT default to
     * "Anna" when unresolved — see owner correction 2026-09-05: most of this cohort's procedures
     * were performed by other technicians, not Anna herself, so a wrong name would be worse than no
     * name at all. */
    private String technicianFirstName(SquareClient.Booking booking, Long businessId) {
        if (booking.appointmentSegments() == null || booking.appointmentSegments().isEmpty()) {
            return null;
        }
        String teamMemberId = booking.appointmentSegments().get(0).teamMemberId();
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

    /** E.g. "1 year and 7 months", "7 months", "2 years" — no "and 0 months" tail. */
    static String formatTimeSince(LocalDate from, LocalDate to) {
        Period period = Period.between(from, to);
        int years = period.getYears();
        int months = period.getMonths();
        if (years <= 0) {
            return months + (months == 1 ? " month" : " months");
        }
        String yearsPart = years + (years == 1 ? " year" : " years");
        if (months <= 0) {
            return yearsPart;
        }
        return yearsPart + " and " + months + (months == 1 ? " month" : " months");
    }

    private Set<String> eligibleRoleIds(Long businessId, String role) {
        return roleRepository.findAllByBusinessIdAndRole(businessId, role).stream()
                .map(ServiceLifecycleRole::getSquareVariationId)
                .collect(Collectors.toSet());
    }

    private static LocalDate bookingDate(SquareClient.Booking booking) {
        if (booking.startAt() == null) return null;
        try {
            return Instant.parse(booking.startAt()).atZone(SALON_ZONE).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code service_lifecycle_reminder_send} was built for SMS automations and has no email
     * column — the {@code phoneNumber} column holds the email address here instead, purely for a
     * human reviewing the row later; nothing reads it back programmatically (idempotency only
     * needs {@code squareCustomerId}/{@code state}/{@code createdAt}). */
    /** Upsert, not a blind insert — a re-run of this campaign (e.g. to pick up SEND_FAILED
     * stragglers) targets the same (business, automation, customer, date) tuple a prior attempt
     * already wrote, which the table's unique constraint rejects as a second insert. Found live
     * 2026-09-05: an insert-only version of this method threw on retry for customers who had
     * actually just been sent a real email, losing the SENT outcome and leaving them stuck on a
     * stale SEND_FAILED row that a future run would have retried again — a real duplicate-send risk. */
    private void save(Long businessId, String customerId, LocalDate today, String email, String customerName, String state) {
        ServiceLifecycleReminderSend row = sendRepository
                .findByBusinessIdAndAutomationKeyAndSquareCustomerIdAndTriggerServiceDate(businessId, AUTOMATION_KEY, customerId, today)
                .orElseGet(() -> ServiceLifecycleReminderSend.builder()
                        .businessId(businessId)
                        .automationKey(AUTOMATION_KEY)
                        .squareCustomerId(customerId)
                        .triggerServiceDate(today)
                        .build());
        row.setPhoneNumber(email);
        row.setCustomerName(customerName);
        row.setState(state);
        sendRepository.save(row);
    }
}
