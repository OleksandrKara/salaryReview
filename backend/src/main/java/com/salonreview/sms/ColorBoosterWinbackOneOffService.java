package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.ServiceLifecycleRole;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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
 * <p>Idempotency/outcome log: {@link WinbackEmailSend} (same table the SMS-fallback automations
 * use), not {@code service_lifecycle_reminder_send} — this campaign has no SMS leg at all, so
 * {@code smsMessageId} is always null here, but reusing this table (rather than a dedicated one)
 * means the owner's existing Mailchimp activity view and {@code MailchimpActivitySyncScheduler}
 * pick up real opened/clicked tracking for it for free. Found live 2026-09-05:
 * {@code service_lifecycle_reminder_send} was tried first, but it has no email/campaign-id/opened/
 * clicked columns at all, so it could never support that.
 */
@Service
public class ColorBoosterWinbackOneOffService {

    private static final Logger log = LoggerFactory.getLogger(ColorBoosterWinbackOneOffService.class);
    static final String AUTOMATION_KEY = "color_booster_winback_oneoff";
    private static final String TEMPLATE_KEY = "color_booster_winback_oneoff";
    // The "color-booster" slug itself points at the "1-2 year" Square tier, not "10 month-1 year"
    // — owner decision 2026-09-05: the shorter tier isn't sold at all anymore (see salonLandings'
    // pmu_catalog.py for the full reasoning), so the plain slug is correct here without a suffix.
    private static final String BOOKING_LINK = "https://book.pmu-annakara.com/?book=color-booster";
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");
    private static final int ELIGIBILITY_DAYS = 365;
    private static final int MAX_LOOKBACK_DAYS = 1095;

    public record CandidateResult(String squareCustomerId, String email, String state, String detail) {}

    private final ServiceLifecycleRoleRepository roleRepository;
    private final WinbackEmailSendRepository sendRepository;
    private final ProviderVisitRepository visitRepository;
    private final ProviderRepository providerRepository;
    private final SquareClientProvider squareClientProvider;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;

    public ColorBoosterWinbackOneOffService(ServiceLifecycleRoleRepository roleRepository,
                                             WinbackEmailSendRepository sendRepository,
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
            if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                    businessId, AUTOMATION_KEY, customerId, WinbackEmailSend.STATE_SENT)) {
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
        String timeSince = com.salonreview.util.TimePeriods.formatTimeSince(lastQualifyingDate, today);

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
            String campaignId = mailchimpEmailService.sendWinbackEmail(config, email, subjectLine, previewText, campaignTitle, html.get());
            save(businessId, customerId, email, WinbackEmailSend.STATE_SENT, campaignId, html.get());
            return new CandidateResult(customerId, email, "SENT", timeSince);
        } catch (Exception e) {
            save(businessId, customerId, email, WinbackEmailSend.STATE_SEND_FAILED, null, null);
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

    /** Upsert, not a blind insert — a re-run of this campaign (e.g. to pick up SEND_FAILED
     * stragglers) targets the same (business, automation, customer) a prior attempt may have
     * already logged, and there's no DB-level unique constraint covering that shape here (unlike
     * the SMS-fallback automations, which key off {@code sms_message_id}) — found live 2026-09-05
     * on the {@code service_lifecycle_reminder_send} version of this method: a naive insert-only
     * save threw on retry for customers who had actually just been sent a real email, losing the
     * SENT outcome and leaving them stuck on a stale failed row a future run would have retried
     * again — a real duplicate-send risk. */
    private void save(Long businessId, String customerId, String email, String state, String mailchimpCampaignId, String contentHtml) {
        WinbackEmailSend row = sendRepository
                .findByBusinessIdAndAutomationKeyAndSquareCustomerId(businessId, AUTOMATION_KEY, customerId)
                .orElseGet(() -> WinbackEmailSend.builder()
                        .businessId(businessId)
                        .automationKey(AUTOMATION_KEY)
                        .squareCustomerId(customerId)
                        .build());
        row.setEmailAddress(email);
        row.setState(state);
        row.setMailchimpCampaignId(mailchimpCampaignId);
        row.setContentHtml(contentHtml);
        sendRepository.save(row);
    }
}
