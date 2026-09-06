package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

/**
 * One-time Labor Day promo email (owner request 2026-09-06) to AK.LUX.NAILS' (business 1) entire
 * Square customer directory that has an email on file, excluding anyone who already has an
 * upcoming ACCEPTED booking — same exclusion window/logic as
 * {@link com.salonreview.web.UpcomingBookingEmailsController}, since a customer already booked has
 * no use for a "book by Sept 7" offer. Unlike {@link ColorBoosterWinbackOneOffService}, there is no
 * lifecycle/overdue qualification here — every customer with an email counts, by owner decision.
 *
 * <p>Not a {@code @Scheduled} component: triggered once, manually, via
 * {@link com.salonreview.web.LaborDayPromoOneOffController}. Guards against running past the
 * promo's own deadline so a stray re-run never sends out an already-dead link.
 */
@Service
public class LaborDayPromoOneOffService {

    private static final Logger log = LoggerFactory.getLogger(LaborDayPromoOneOffService.class);
    static final String AUTOMATION_KEY = "labor_day_design_promo_oneoff";
    private static final String TEMPLATE_KEY = "labor_day_design_promo";
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");
    // Matches the exp=1788850800 baked into the promo link inside the template itself — past this
    // date the link's own signature check rejects it anyway, so a re-run after it would just mail
    // out a dead offer.
    private static final LocalDate PROMO_DEADLINE = LocalDate.of(2026, 9, 7);
    private static final Duration UPCOMING_BOOKING_LOOKAHEAD = Duration.ofDays(365);
    private static final String SUBJECT = "A little Labor Day treat for you 💛";
    private static final String PREVIEW_TEXT = "Book by Sept 7 and get a free nail design";

    public record CandidateResult(String squareCustomerId, String email, String state, String detail) {}

    private final WinbackEmailSendRepository sendRepository;
    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final SquareClientProvider squareClientProvider;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;

    public LaborDayPromoOneOffService(WinbackEmailSendRepository sendRepository,
                                       SquareBookingMirrorRepository bookingMirrorRepository,
                                       SquareClientProvider squareClientProvider,
                                       MailchimpConfigRepository mailchimpConfigRepository,
                                       MailchimpEmailService mailchimpEmailService,
                                       MailchimpEmailTemplateService templateService) {
        this.sendRepository = sendRepository;
        this.bookingMirrorRepository = bookingMirrorRepository;
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
        if (today.isAfter(PROMO_DEADLINE)) {
            results.add(new CandidateResult(null, null, "SKIPPED_EXPIRED", "Promo deadline " + PROMO_DEADLINE + " has passed"));
            return results;
        }

        MailchimpConfig config = mailchimpConfigRepository.findByBusinessId(businessId).orElse(null);
        if (config == null || !config.isConfigured()) {
            results.add(new CandidateResult(null, null, "SKIPPED_NOT_CONFIGURED", "Mailchimp not configured for business " + businessId));
            return results;
        }

        Set<String> excludedCustomerIds = upcomingBookedCustomerIds(businessId);
        SquareClient square = squareClientProvider.forBusiness(businessId);

        for (SquareClient.Customer customer : square.listAllCustomers()) {
            String customerId = customer.id();
            if (customerId == null) {
                continue;
            }
            if (excludedCustomerIds.contains(customerId)) {
                results.add(new CandidateResult(customerId, null, "SKIPPED_ALREADY_BOOKED", "Already has an upcoming booking"));
                continue;
            }
            String email = customer.emailAddress();
            if (email == null || email.isBlank()) {
                results.add(new CandidateResult(customerId, null, "SKIPPED_NO_EMAIL", null));
                continue;
            }
            if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                    businessId, AUTOMATION_KEY, customerId, WinbackEmailSend.STATE_SENT)) {
                continue; // already actually sent by this campaign before — never re-sent, even on a re-run
            }
            try {
                results.add(process(businessId, customerId, email, customer.givenName(), config, dryRun));
            } catch (RuntimeException e) {
                log.warn("Labor Day promo one-off failed for customer {} (business {}): {}",
                        customerId, businessId, e.getMessage(), e);
                results.add(new CandidateResult(customerId, email, "ERROR", e.getMessage()));
            }
        }
        return results;
    }

    private Set<String> upcomingBookedCustomerIds(Long businessId) {
        Instant now = Instant.now();
        Set<String> ids = new HashSet<>();
        for (SquareBookingMirror booking : bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                businessId, now, now.plus(UPCOMING_BOOKING_LOOKAHEAD))) {
            if ("ACCEPTED".equals(booking.getStatus()) && booking.getSquareCustomerId() != null) {
                ids.add(booking.getSquareCustomerId());
            }
        }
        return ids;
    }

    private CandidateResult process(Long businessId, String customerId, String email, String rawGivenName,
                                     MailchimpConfig config, boolean dryRun) {
        String givenName = Names.capitalizeFirst(rawGivenName);

        Map<String, String> vars = new HashMap<>();
        vars.put("FNAME", givenName == null ? "there" : givenName);

        Optional<String> html = templateService.render(businessId, TEMPLATE_KEY, vars);
        if (html.isEmpty()) {
            return new CandidateResult(customerId, email, "SKIPPED_NO_TEMPLATE", null);
        }

        if (dryRun) {
            return new CandidateResult(customerId, email, "WOULD_SEND", null);
        }

        String campaignTitle = AUTOMATION_KEY + " - " + customerId;
        try {
            String campaignId = mailchimpEmailService.sendWinbackEmail(config, email, SUBJECT, PREVIEW_TEXT, campaignTitle, html.get());
            save(businessId, customerId, email, WinbackEmailSend.STATE_SENT, campaignId, html.get());
            return new CandidateResult(customerId, email, "SENT", null);
        } catch (Exception e) {
            save(businessId, customerId, email, WinbackEmailSend.STATE_SEND_FAILED, null, null);
            log.warn("Labor Day promo one-off email send failed for customer {} (business {}): {}",
                    customerId, businessId, e.getMessage());
            return new CandidateResult(customerId, email, "SEND_FAILED", e.getMessage());
        }
    }

    /** Upsert, not a blind insert — same duplicate-send risk on retry documented at
     * {@link ColorBoosterWinbackOneOffService#save}: a re-run to pick up SEND_FAILED stragglers
     * targets the same (business, automation, customer) a prior attempt may have already logged. */
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
