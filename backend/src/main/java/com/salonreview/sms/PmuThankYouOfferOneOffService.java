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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-time Labor Day / cross-promo email for Anna Kara's Beauty PMU Studio (business 2, owner
 * request 2026-09-06/07): every business-2 Square customer with an email on file gets one shared
 * Mailchimp campaign (see {@link MailchimpBatchCampaignService} — the batch mass-send path built
 * specifically to replace the per-contact campaign-per-customer shape after the Labor Day promo's
 * 1,358-campaign send for business 1 took ~74 minutes), offering 35% off a manicure for booking a
 * new PMU appointment today. Excludes anyone with an upcoming ACCEPTED booking (same reasoning as
 * business 1's equivalent exclusion — already booked, no use for a "book today" offer) and anyone
 * Mailchimp will never actually deliver to (unsubscribed/cleaned).
 *
 * <p>Not a {@code @Scheduled} component: triggered once, manually, via {@link
 * PmuThankYouOfferOneOffController}. Refuses to run once the offer's own same-day deadline has
 * passed, since the whole point of this campaign is a same-day-only offer.
 */
@Service
public class PmuThankYouOfferOneOffService {

    static final Long BUSINESS_ID = 2L;
    static final String AUTOMATION_KEY = "pmu_thank_you_offer";
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");
    private static final LocalDate OFFER_DEADLINE = LocalDate.of(2026, 9, 8);
    private static final Duration UPCOMING_BOOKING_LOOKAHEAD = Duration.ofDays(365);
    private static final String SUBJECT = "A Labor Day thank you from Anna 💛";
    private static final String PREVIEW_TEXT = "35% off a manicure, just for booking";
    private static final String SEGMENT_NAME = "pmu_thank_you_offer_2026_09_07";

    public record PreviewResult(int totalCustomers, int withEmail, int excludedAlreadyBooked,
                                 int excludedAlreadySent, int excludedUndeliverable, int finalRecipientCount) {}

    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final SquareClientProvider squareClientProvider;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpClient mailchimpClient;
    private final WinbackEmailSendRepository sendRepository;
    private final MailchimpBatchCampaignService batchCampaignService;

    public PmuThankYouOfferOneOffService(SquareBookingMirrorRepository bookingMirrorRepository,
                                          SquareClientProvider squareClientProvider,
                                          MailchimpConfigRepository mailchimpConfigRepository,
                                          MailchimpClient mailchimpClient,
                                          WinbackEmailSendRepository sendRepository,
                                          MailchimpBatchCampaignService batchCampaignService) {
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.squareClientProvider = squareClientProvider;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpClient = mailchimpClient;
        this.sendRepository = sendRepository;
        this.batchCampaignService = batchCampaignService;
    }

    /** Read-only: resolves the exact recipient list and reports counts at every exclusion step,
     * but never touches Mailchimp beyond the two read-only member-status list scans needed for an
     * accurate count. Safe to call repeatedly while reviewing the audience before the real send. */
    public PreviewResult preview() throws Exception {
        MailchimpConfig config = requireConfig();
        List<SquareClient.Customer> allCustomers = squareClientProvider.forBusiness(BUSINESS_ID).listAllCustomers();
        Set<String> excludedCustomerIds = upcomingBookedCustomerIds();
        Set<String> undeliverable = mailchimpClient.fetchUndeliverableEmails(config);

        int withEmail = 0;
        int excludedAlreadyBooked = 0;
        int excludedAlreadySent = 0;
        int excludedUndeliverable = 0;
        int finalCount = 0;
        for (SquareClient.Customer customer : allCustomers) {
            if (customer.id() == null || customer.emailAddress() == null || customer.emailAddress().isBlank()) {
                continue;
            }
            withEmail++;
            if (excludedCustomerIds.contains(customer.id())) {
                excludedAlreadyBooked++;
                continue;
            }
            if (undeliverable.contains(customer.emailAddress().toLowerCase(java.util.Locale.ROOT))) {
                excludedUndeliverable++;
                continue;
            }
            if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                    BUSINESS_ID, AUTOMATION_KEY, customer.id(), WinbackEmailSend.STATE_SENT)) {
                excludedAlreadySent++;
                continue;
            }
            finalCount++;
        }
        return new PreviewResult(allCustomers.size(), withEmail, excludedAlreadyBooked, excludedAlreadySent, excludedUndeliverable, finalCount);
    }

    /** The real, irreversible send: builds the exact same recipient list {@link #preview} counts,
     * then hands it to {@link MailchimpBatchCampaignService} as one shared campaign. Refuses to run
     * past the offer's own deadline. */
    public MailchimpBatchCampaignService.BatchSendResult send() throws Exception {
        LocalDate today = LocalDate.now(SALON_ZONE);
        if (today.isAfter(OFFER_DEADLINE)) {
            return new MailchimpBatchCampaignService.BatchSendResult(
                    "SKIPPED_EXPIRED", "Offer deadline " + OFFER_DEADLINE + " has passed", null, null, 0);
        }

        MailchimpConfig config = requireConfig();
        Set<String> excludedCustomerIds = upcomingBookedCustomerIds();
        Set<String> undeliverable = mailchimpClient.fetchUndeliverableEmails(config);

        List<MailchimpBatchCampaignService.Recipient> recipients = new ArrayList<>();
        for (SquareClient.Customer customer : squareClientProvider.forBusiness(BUSINESS_ID).listAllCustomers()) {
            if (customer.id() == null || customer.emailAddress() == null || customer.emailAddress().isBlank()) {
                continue;
            }
            if (excludedCustomerIds.contains(customer.id())) {
                continue;
            }
            if (undeliverable.contains(customer.emailAddress().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                    BUSINESS_ID, AUTOMATION_KEY, customer.id(), WinbackEmailSend.STATE_SENT)) {
                continue; // already actually sent by this campaign before — never re-sent, even on a re-run
            }
            recipients.add(new MailchimpBatchCampaignService.Recipient(
                    customer.id(), customer.emailAddress(), Names.capitalizeFirst(customer.givenName())));
        }

        String campaignTitle = AUTOMATION_KEY + " - " + today;
        return batchCampaignService.send(BUSINESS_ID, AUTOMATION_KEY, config, SEGMENT_NAME, SUBJECT, PREVIEW_TEXT, campaignTitle, recipients);
    }

    private MailchimpConfig requireConfig() {
        return mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)
                .filter(MailchimpConfig::isConfigured)
                .orElseThrow(() -> new IllegalStateException("Mailchimp not configured for business " + BUSINESS_ID));
    }

    private Set<String> upcomingBookedCustomerIds() {
        Instant now = Instant.now();
        Set<String> ids = new HashSet<>();
        for (SquareBookingMirror booking : bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                BUSINESS_ID, now, now.plus(UPCOMING_BOOKING_LOOKAHEAD))) {
            if ("ACCEPTED".equals(booking.getStatus()) && booking.getSquareCustomerId() != null) {
                ids.add(booking.getSquareCustomerId());
            }
        }
        return ids;
    }
}
