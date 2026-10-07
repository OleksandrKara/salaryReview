package com.salonreview.sms;

import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.domain.SquarePaymentMirror;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * One-off email to PMU clients who had a consultation long ago and never came back for a procedure
 * (owner approved 2026-10-07): one personal email from the artist they spoke with, $75 OFF any
 * procedure from $500 for 7 days, sent in waves by how long ago the consultation was (wave 1 up to
 * a year, wave 2 one to two years, wave 3 older). Email only, no SMS.
 *
 * <p>Never eligible: anyone with any other booking (procedure) at all, before or after, anyone who
 * paid the studio $100 or more without a booking, anyone with a consultation coming up, anyone the
 * regular consultation_follow_up sequence already has, anyone Mailchimp won't deliver to, and
 * anyone this campaign already reached. Recomputed on every call, so a client who books between
 * waves simply drops out.
 *
 * <p>The discount itself works exactly like the day-45 offer: the client joins the READY75 Square
 * customer group, and a consultation_follow_up row (stop_reason REENGAGE, offer_state SENT) lets
 * ConsultationFollowUpScheduler#closeExpiredOffers end the membership after 7 days, or keep it
 * until two days after the visit for a client who booked in time.
 *
 * <p>The artist who did the consultation writes; when they're no longer at the studio or unknown,
 * Anna writes instead, introducing herself as the owner (owner decision 2026-10-07).
 */
@Service
public class ConsultationReengageOneOffService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationReengageOneOffService.class);

    static final Long BUSINESS_ID = 2L;
    static final String TEMPLATE_KEY = "consultation_reengage_offer";
    static final String VISIT_KIND = "reengage";
    static final String TEST_BOOKING_ID = "TEST-PROOF";
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final int OFFER_DAYS = 7;
    private static final DateTimeFormatter OFFER_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);
    /** Past the regular sequence's 4-day enrollment window, so the two never race for a client. */
    private static final Duration MIN_AGE = Duration.ofDays(5);
    private static final BigDecimal PAID_WITHOUT_BOOKING = new BigDecimal("100");
    private static final String ACCEPTED = "ACCEPTED";

    public record Candidate(String squareCustomerId, String email, String firstName, String artist,
                            boolean ownerWrites, String consultationBookingId, String teamMemberId,
                            Instant consultationStartAt, int wave) {}

    public record WavePreview(int wave, int recipients, Map<String, Integer> byArtist) {}

    public record PreviewResult(int consultationClients, int excludedHadProcedure, int excludedPaid,
                                int excludedUpcomingConsultation, int excludedRecentOrInSequence,
                                int excludedNoEmail, int excludedUndeliverable, int excludedAlreadySent,
                                List<WavePreview> waves) {}

    public record SendResult(int wave, int sent, int failed, String expires) {}

    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final SquarePaymentMirrorRepository paymentMirrorRepository;
    private final SquareCustomerMirrorRepository customerMirrorRepository;
    private final ConsultationFollowUpRepository followUpRepository;
    private final ProviderRepository providerRepository;
    private final SquareClientProvider squareClientProvider;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpClient mailchimpClient;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final PreVisitNurtureContent content;
    private final PromoConfigService promoConfigService;
    private final SameDayRebookingGroupMembershipRepository membershipRepository;
    private final ConsultationFollowUpLinks links;
    private final Clock clock;

    @Autowired
    public ConsultationReengageOneOffService(SquareBookingMirrorRepository bookingMirrorRepository,
                                             SquarePaymentMirrorRepository paymentMirrorRepository,
                                             SquareCustomerMirrorRepository customerMirrorRepository,
                                             ConsultationFollowUpRepository followUpRepository,
                                             ProviderRepository providerRepository,
                                             SquareClientProvider squareClientProvider,
                                             MailchimpConfigRepository mailchimpConfigRepository,
                                             MailchimpClient mailchimpClient,
                                             MailchimpEmailService mailchimpEmailService,
                                             MailchimpEmailTemplateService templateService,
                                             PreVisitNurtureContent content,
                                             PromoConfigService promoConfigService,
                                             SameDayRebookingGroupMembershipRepository membershipRepository,
                                             ConsultationFollowUpLinks links) {
        this(bookingMirrorRepository, paymentMirrorRepository, customerMirrorRepository, followUpRepository,
                providerRepository, squareClientProvider, mailchimpConfigRepository, mailchimpClient,
                mailchimpEmailService, templateService, content, promoConfigService, membershipRepository, links,
                Clock.system(PACIFIC));
    }

    ConsultationReengageOneOffService(SquareBookingMirrorRepository bookingMirrorRepository,
                                      SquarePaymentMirrorRepository paymentMirrorRepository,
                                      SquareCustomerMirrorRepository customerMirrorRepository,
                                      ConsultationFollowUpRepository followUpRepository,
                                      ProviderRepository providerRepository,
                                      SquareClientProvider squareClientProvider,
                                      MailchimpConfigRepository mailchimpConfigRepository,
                                      MailchimpClient mailchimpClient,
                                      MailchimpEmailService mailchimpEmailService,
                                      MailchimpEmailTemplateService templateService,
                                      PreVisitNurtureContent content,
                                      PromoConfigService promoConfigService,
                                      SameDayRebookingGroupMembershipRepository membershipRepository,
                                      ConsultationFollowUpLinks links,
                                      Clock clock) {
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.paymentMirrorRepository = paymentMirrorRepository;
        this.customerMirrorRepository = customerMirrorRepository;
        this.followUpRepository = followUpRepository;
        this.providerRepository = providerRepository;
        this.squareClientProvider = squareClientProvider;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpClient = mailchimpClient;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.content = content;
        this.promoConfigService = promoConfigService;
        this.membershipRepository = membershipRepository;
        this.links = links;
        this.clock = clock;
    }

    /** Read-only: who each wave would reach right now. Never writes, never sends. */
    public PreviewResult preview() throws Exception {
        Audience audience = audience();
        List<WavePreview> waves = new ArrayList<>();
        for (int wave = 1; wave <= 3; wave++) {
            int w = wave;
            List<Candidate> list = audience.candidates.stream().filter(c -> c.wave() == w).toList();
            Map<String, Integer> byArtist = new TreeMap<>();
            list.forEach(c -> byArtist.merge(c.ownerWrites() ? "Anna (artist no longer here or unknown)" : c.artist(), 1, Integer::sum));
            waves.add(new WavePreview(wave, list.size(), byArtist));
        }
        return new PreviewResult(audience.consultationClients, audience.hadProcedure, audience.paid,
                audience.upcomingConsultation, audience.recentOrInSequence, audience.noEmail,
                audience.undeliverable, audience.alreadySent, waves);
    }

    /** Sends the email for one made-up client to {@code toEmail} (an owner proof): real template,
     * real artist photo, nobody joins the discount group and nothing is recorded. */
    public void sendTest(String toEmail, String artist, boolean ownerWrites) throws Exception {
        MailchimpConfig config = requireConfig();
        String resolved = ownerWrites ? content.artistName(BUSINESS_ID, null) : artist;
        String expires = expiresText(offerExpires());
        // The proof's button carries a live personal link, so the owner can see the discount in
        // the booking popup: a stand-in offer row for a made-up customer, already "closed"
        // (offer_extended_at set) so closeExpiredOffers never looks it up in Square. Bookings
        // with a test phone number never claim an offer.
        ConsultationFollowUp proof = followUpRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, TEST_BOOKING_ID)
                .orElseGet(() -> ConsultationFollowUp.builder().businessId(BUSINESS_ID).squareBookingId(TEST_BOOKING_ID)
                        .squareCustomerId(TEST_BOOKING_ID).visitKind(VISIT_KIND + "_test").consultationStartAt(clock.instant())
                        .stopReason(ConsultationFollowUp.STOP_REENGAGE).stoppedAt(clock.instant()).build());
        proof.setCustomerName("Alex");
        proof.setOfferState(ConsultationFollowUp.STATE_SENT);
        proof.setOfferExpiresAt(offerExpires());
        proof.setOfferExtendedAt(clock.instant());
        proof = followUpRepository.save(proof);
        String html = render("Alex", resolved, ownerWrites, expires, links.offerBookUrl(proof.getId()));
        mailchimpEmailService.sendWinbackEmail(config, toEmail, "[TEST] " + subject("Alex"), preview(resolved, expires),
                TEMPLATE_KEY + " TEST", html);
    }

    /** The real, irreversible send of one wave: every recipient joins the READY75 group for 7
     * days and gets their email. A client already reached is never sent twice, even on a re-run. */
    public SendResult send(int wave) throws Exception {
        if (wave < 1 || wave > 3) throw new IllegalArgumentException("wave must be 1, 2 or 3");
        MailchimpConfig config = requireConfig();
        PromoConfigService.PromoTerms terms = promoConfigService.get(BUSINESS_ID, PromoConfigService.CONSULTATION_OFFER_PROMO_CODE)
                .filter(PromoConfigService.PromoTerms::configured)
                .orElseThrow(() -> new IllegalStateException("READY75 isn't set up for business " + BUSINESS_ID));
        if (templateService.render(BUSINESS_ID, TEMPLATE_KEY, Map.of()).isEmpty()) {
            throw new IllegalStateException("No " + TEMPLATE_KEY + " template for business " + BUSINESS_ID);
        }
        SquareClient square = squareClientProvider.forBusiness(BUSINESS_ID);
        Instant expires = offerExpires();
        String expiresText = expiresText(expires);

        int sent = 0;
        int failed = 0;
        for (Candidate c : audience().candidates) {
            if (c.wave() != wave) continue;
            try {
                square.addCustomerToGroup(c.squareCustomerId(), terms.squareCustomerGroupId());
                membershipRepository.save(SameDayRebookingGroupMembership.builder()
                        .businessId(BUSINESS_ID)
                        .squareCustomerId(c.squareCustomerId())
                        .groupId(terms.squareCustomerGroupId())
                        .expiresAt(expires.plus(Duration.ofDays(120)))
                        .build());
                ConsultationFollowUp row = followUpRepository.save(ConsultationFollowUp.builder()
                        .businessId(BUSINESS_ID)
                        .squareBookingId(c.consultationBookingId())
                        .squareCustomerId(c.squareCustomerId())
                        .teamMemberId(c.teamMemberId())
                        .artistName(c.artist())
                        .visitKind(VISIT_KIND)
                        .consultationStartAt(c.consultationStartAt())
                        .customerName(c.firstName())
                        .offerExpiresAt(expires)
                        .stopReason(ConsultationFollowUp.STOP_REENGAGE)
                        .stoppedAt(clock.instant())
                        .build());
                String html = render(c.firstName(), c.artist(), c.ownerWrites(), expiresText, links.offerBookUrl(row.getId()));
                try {
                    mailchimpEmailService.sendWinbackEmail(config, c.email(), subject(c.firstName()), preview(c.artist(), expiresText),
                            TEMPLATE_KEY + " wave " + wave + ": booking " + c.consultationBookingId(), html);
                    row.setOfferState(ConsultationFollowUp.STATE_SENT);
                    sent++;
                } catch (Exception e) {
                    log.warn("Consultation re-engage email failed for booking {}: {}", c.consultationBookingId(), e.getMessage());
                    // Recorded, so a re-run doesn't retry blindly; closeExpiredOffers only looks at
                    // SENT rows, so this membership simply runs out with the expiry scheduler.
                    row.setOfferState(ConsultationFollowUp.STATE_SEND_FAILED);
                    failed++;
                }
                followUpRepository.save(row);
            } catch (RuntimeException e) {
                log.warn("Consultation re-engage for booking {} failed before sending: {}", c.consultationBookingId(), e.getMessage(), e);
                failed++;
            }
        }
        return new SendResult(wave, sent, failed, expiresText);
    }

    // --- Audience ----------------------------------------------------------------------------

    private static final class Audience {
        final List<Candidate> candidates = new ArrayList<>();
        int consultationClients, hadProcedure, paid, upcomingConsultation, recentOrInSequence, noEmail, undeliverable, alreadySent;
    }

    private Audience audience() throws Exception {
        Instant now = clock.instant();
        SquareClient square = squareClientProvider.forBusiness(BUSINESS_ID);
        List<SquareBookingMirror> all = bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                BUSINESS_ID, Instant.EPOCH, now.plus(Duration.ofDays(3650)));

        Set<String> variationIds = new HashSet<>();
        for (SquareBookingMirror b : all) {
            if (b.getAppointmentSegments() != null) {
                b.getAppointmentSegments().forEach(s -> { if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId()); });
            }
        }
        Map<String, String> names = square.catalogNames(variationIds);
        Set<String> consultationIds = new HashSet<>();
        names.forEach((id, name) -> { if (name != null && name.toLowerCase(Locale.US).contains("consultation")) consultationIds.add(id); });

        Map<String, List<SquareBookingMirror>> byCustomer = new HashMap<>();
        for (SquareBookingMirror b : all) {
            if (b.getSquareCustomerId() != null) byCustomer.computeIfAbsent(b.getSquareCustomerId(), k -> new ArrayList<>()).add(b);
        }
        Set<String> paidCustomers = new HashSet<>();
        for (SquarePaymentMirror p : paymentMirrorRepository.findByBusinessIdAndCreatedAtBetween(BUSINESS_ID, Instant.EPOCH, now)) {
            if (p.getSquareCustomerId() != null && "COMPLETED".equals(p.getStatus())
                    && p.getTotalMoney() != null && p.getTotalMoney().compareTo(PAID_WITHOUT_BOOKING) >= 0) {
                paidCustomers.add(p.getSquareCustomerId());
            }
        }
        Set<String> sequenceCustomers = new HashSet<>();
        Set<String> alreadySentCustomers = new HashSet<>();
        for (ConsultationFollowUp row : followUpRepository.findAll()) {
            if (!BUSINESS_ID.equals(row.getBusinessId())) continue;
            if (TEST_BOOKING_ID.equals(row.getSquareBookingId())) continue;
            if (VISIT_KIND.equals(row.getVisitKind())) alreadySentCustomers.add(row.getSquareCustomerId());
            else sequenceCustomers.add(row.getSquareCustomerId());
        }
        MailchimpConfig config = requireConfig();
        Set<String> undeliverable = mailchimpClient.fetchUndeliverableEmails(config);

        Audience a = new Audience();
        Map<String, String> artistByTeamMember = artistNames();
        for (Map.Entry<String, List<SquareBookingMirror>> e : byCustomer.entrySet()) {
            String customerId = e.getKey();
            List<SquareBookingMirror> bookings = e.getValue();
            Optional<SquareBookingMirror> last = bookings.stream()
                    .filter(b -> ACCEPTED.equals(b.getStatus()) && b.getStartAt() != null && b.getStartAt().isBefore(now))
                    .filter(b -> isConsultation(b, consultationIds))
                    .max(Comparator.comparing(SquareBookingMirror::getStartAt));
            if (last.isEmpty()) continue;
            a.consultationClients++;

            boolean procedure = bookings.stream()
                    .filter(b -> ACCEPTED.equals(b.getStatus()) || "PENDING".equals(b.getStatus()))
                    .anyMatch(b -> !isConsultation(b, consultationIds));
            if (procedure) { a.hadProcedure++; continue; }
            if (paidCustomers.contains(customerId)) { a.paid++; continue; }
            boolean upcoming = bookings.stream().anyMatch(b -> b.getStartAt() != null && b.getStartAt().isAfter(now)
                    && (ACCEPTED.equals(b.getStatus()) || "PENDING".equals(b.getStatus())));
            if (upcoming) { a.upcomingConsultation++; continue; }
            if (last.get().getStartAt().isAfter(now.minus(MIN_AGE)) || sequenceCustomers.contains(customerId)) {
                a.recentOrInSequence++; continue;
            }
            if (alreadySentCustomers.contains(customerId)) { a.alreadySent++; continue; }
            Optional<SquareCustomerMirror> customer = customerMirrorRepository.findByBusinessIdAndSquareCustomerId(BUSINESS_ID, customerId);
            String email = customer.map(SquareCustomerMirror::getEmailAddress).filter(s -> s != null && !s.isBlank()).orElse(null);
            if (email == null) { a.noEmail++; continue; }
            if (undeliverable.contains(email.toLowerCase(Locale.ROOT))) { a.undeliverable++; continue; }

            SquareBookingMirror consultation = last.get();
            String teamMemberId = consultation.getAppointmentSegments() == null || consultation.getAppointmentSegments().isEmpty()
                    ? null : consultation.getAppointmentSegments().get(0).teamMemberId();
            String original = teamMemberId == null ? null : artistByTeamMember.get(teamMemberId);
            String artist = content.artistName(BUSINESS_ID, original);
            boolean ownerWrites = !Objects.equals(original, artist);
            String firstName = customer.map(SquareCustomerMirror::getGivenName).map(Names::capitalizeFirst)
                    .filter(s -> s != null && !s.isBlank()).orElse(null);
            a.candidates.add(new Candidate(customerId, email, firstName, artist, ownerWrites,
                    consultation.getSquareBookingId(), teamMemberId, consultation.getStartAt(),
                    wave(consultation.getStartAt(), now)));
        }
        a.candidates.sort(Comparator.comparing(Candidate::consultationStartAt).reversed());
        return a;
    }

    static int wave(Instant consultation, Instant now) {
        long days = Duration.between(consultation, now).toDays();
        return days <= 365 ? 1 : days <= 730 ? 2 : 3;
    }

    private static boolean isConsultation(SquareBookingMirror b, Set<String> consultationIds) {
        return b.getAppointmentSegments() != null && !b.getAppointmentSegments().isEmpty()
                && b.getAppointmentSegments().stream().allMatch(s -> consultationIds.contains(s.serviceVariationId()));
    }

    /** Team member id -> first name, for every provider the studio has on file. */
    private Map<String, String> artistNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Provider p : providerRepository.findAllByBusinessId(BUSINESS_ID)) {
            String name = p.getDisplayName() == null ? null : Names.firstNameOnly(p.getDisplayName());
            if (name == null) continue;
            p.getSquareTeamMemberIds().forEach(tm -> names.putIfAbsent(tm, name));
        }
        return names;
    }

    // --- Email -------------------------------------------------------------------------------

    String render(String firstName, String artist, boolean ownerWrites, String expiresText, String bookUrl) {
        String intro = ownerWrites ? "It's " + artist + ", owner of Anna Kara's PMU Studio."
                : "It's " + artist + " from Anna Kara's PMU Studio.";
        String opener = ownerWrites ? "You had a consultation with us a while ago, and I wanted to check in personally."
                : "We met at your consultation a while ago, and I wanted to check in.";
        Map<String, String> v = new HashMap<>();
        v.put("FNAME", HtmlUtils.htmlEscape(firstName == null ? "there" : firstName));
        v.put("ARTIST", HtmlUtils.htmlEscape(artist));
        v.put("ARTIST_PHOTO_URL", content.artist(BUSINESS_ID, artist).map(PreVisitNurtureContent.Artist::photoUrl).orElse(""));
        v.put("EXPIRES", HtmlUtils.htmlEscape(expiresText));
        v.put("INTRO", HtmlUtils.htmlEscape(intro));
        v.put("OPENER", HtmlUtils.htmlEscape(opener));
        v.put("BOOK_URL", HtmlUtils.htmlEscape(bookUrl));
        return templateService.render(BUSINESS_ID, TEMPLATE_KEY, v)
                .orElseThrow(() -> new IllegalStateException("No " + TEMPLATE_KEY + " template"));
    }

    static String subject(String firstName) {
        return firstName == null ? "Still thinking about it?" : firstName + ", still thinking about it?";
    }

    static String preview(String artist, String expiresText) {
        return "$75 off your procedure this week, from " + artist;
    }

    /** End of the 7th day from today, Pacific time. */
    private Instant offerExpires() {
        return LocalDate.now(clock.withZone(PACIFIC)).plusDays(OFFER_DAYS).plusDays(1).atStartOfDay(PACIFIC).toInstant().minusSeconds(1);
    }

    private static String expiresText(Instant expires) {
        return expires.atZone(PACIFIC).format(OFFER_DATE);
    }

    private MailchimpConfig requireConfig() {
        return mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)
                .filter(MailchimpConfig::isConfigured)
                .orElseThrow(() -> new IllegalStateException("Mailchimp not configured for business " + BUSINESS_ID));
    }
}
