package com.salonreview.sms;

import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Consultation follow-up (owner request 2026-10-06, business 2): a client who had a PMU
 * consultation and left "to think" without booking a procedure hears from the artist they spoke
 * with, at most four times over ~45 days:
 * <ul>
 *   <li>the day after the consultation: a staff Telegram alert with a "Don't message this client"
 *       button (not a candidate, consultation didn't happen, coming back on their own);</li>
 *   <li>day 2: thank-you SMS, "reply here with any questions", booking link;</li>
 *   <li>day 5: email with what helps people decide (the "does it hurt" videos, results, reviews)
 *       and payment plans (Afterpay, Cherry);</li>
 *   <li>day 21: check-in SMS with the artist's real openings from Square;</li>
 *   <li>day 45: last chance $75 OFF any procedure from $500, booked within 7 days, applied by
 *       Square itself at checkout (customer-group pricing rule, promo READY75). SMS only with
 *       marketing consent; email to everyone with an address; a no-discount last check-in SMS for
 *       a client who can get neither.</li>
 * </ul>
 * The sequence stops for good when the client books anything, replies to a text, opts out, or
 * staff tap the button. Before every message Square itself is asked about bookings (the mirror
 * only hears about future bookings through webhooks), messages only go out 09:00-20:00 Pacific,
 * and a step waits if another automation texted the client in the last 7 days.
 *
 * <p>Only consultations from the last few days are ever enrolled: clients from before this
 * shipped are a separate, owner-approved backfill, never picked up here.
 */
@Component
public class ConsultationFollowUpScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConsultationFollowUpScheduler.class);
    static final String AUTOMATION_KEY = "consultation_follow_up";
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final String BOOK_LINK = "https://pmu-annakara.com/?book=procedure";
    private static final String ACCEPTED = "ACCEPTED";

    /** Enrolled from 20h after the consultation's start (it's over, and the alert can still land
     * "the day after") up to 4 days after (a short outage never makes us miss one; anything older
     * is never picked up). */
    private static final Duration ENROLL_MIN_AGE = Duration.ofHours(20);
    private static final Duration ENROLL_MAX_AGE = Duration.ofDays(4);
    /** The step-1 text never goes out sooner than this after the staff alert. */
    private static final Duration ALERT_HEAD_START = Duration.ofHours(6);
    private static final Duration RECENT_OTHER_SMS = Duration.ofDays(7);
    private static final int OFFER_DAYS = 7;
    private static final int SEND_FROM_HOUR = 9;
    private static final int SEND_UNTIL_HOUR = 20;
    private static final DateTimeFormatter OFFER_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);
    private static final DateTimeFormatter OPENING_DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private enum Step {
        THANKS(Duration.ofDays(2), Duration.ofDays(3), ConsultationFollowUp::getThanksSmsState, ConsultationFollowUp::setThanksSmsState),
        INFO(Duration.ofDays(5), Duration.ofDays(5), ConsultationFollowUp::getInfoEmailState, ConsultationFollowUp::setInfoEmailState),
        CHECKIN(Duration.ofDays(21), Duration.ofDays(7), ConsultationFollowUp::getCheckinSmsState, ConsultationFollowUp::setCheckinSmsState),
        OFFER(Duration.ofDays(45), Duration.ofDays(10), ConsultationFollowUp::getOfferState, ConsultationFollowUp::setOfferState);

        final Duration due;
        final Duration maxLate;
        final Function<ConsultationFollowUp, String> state;
        final BiConsumer<ConsultationFollowUp, String> setState;

        Step(Duration due, Duration maxLate, Function<ConsultationFollowUp, String> state,
             BiConsumer<ConsultationFollowUp, String> setState) {
            this.due = due;
            this.maxLate = maxLate;
            this.state = state;
            this.setState = setState;
        }
    }

    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final ConsultationFollowUpRepository repository;
    private final SquareClientProvider squareClientProvider;
    private final SmsAutomationService automationService;
    private final ProviderRepository providerRepository;
    private final TwilioSmsService smsService;
    private final SmsMessageRepository smsMessageRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final PreVisitNurtureContent content;
    private final TelegramNotificationService telegram;
    private final ConsultationFollowUpLinks links;
    private final PromoConfigService promoConfigService;
    private final SameDayRebookingGroupMembershipRepository membershipRepository;
    private final Clock clock;

    @Autowired
    public ConsultationFollowUpScheduler(SquareBookingMirrorRepository bookingMirrorRepository,
                                         ConsultationFollowUpRepository repository,
                                         SquareClientProvider squareClientProvider,
                                         SmsAutomationService automationService,
                                         ProviderRepository providerRepository,
                                         TwilioSmsService smsService,
                                         SmsMessageRepository smsMessageRepository,
                                         MailchimpConfigRepository mailchimpConfigRepository,
                                         MailchimpEmailService mailchimpEmailService,
                                         MailchimpEmailTemplateService templateService,
                                         PreVisitNurtureContent content,
                                         TelegramNotificationService telegram,
                                         ConsultationFollowUpLinks links,
                                         PromoConfigService promoConfigService,
                                         SameDayRebookingGroupMembershipRepository membershipRepository) {
        this(bookingMirrorRepository, repository, squareClientProvider, automationService, providerRepository,
                smsService, smsMessageRepository, mailchimpConfigRepository, mailchimpEmailService, templateService,
                content, telegram, links, promoConfigService, membershipRepository, Clock.systemUTC());
    }

    /** Tests pin the clock (Pacific daytime gate, step due dates). */
    ConsultationFollowUpScheduler(SquareBookingMirrorRepository bookingMirrorRepository,
                                  ConsultationFollowUpRepository repository,
                                  SquareClientProvider squareClientProvider,
                                  SmsAutomationService automationService,
                                  ProviderRepository providerRepository,
                                  TwilioSmsService smsService,
                                  SmsMessageRepository smsMessageRepository,
                                  MailchimpConfigRepository mailchimpConfigRepository,
                                  MailchimpEmailService mailchimpEmailService,
                                  MailchimpEmailTemplateService templateService,
                                  PreVisitNurtureContent content,
                                  TelegramNotificationService telegram,
                                  ConsultationFollowUpLinks links,
                                  PromoConfigService promoConfigService,
                                  SameDayRebookingGroupMembershipRepository membershipRepository,
                                  Clock clock) {
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.repository = repository;
        this.squareClientProvider = squareClientProvider;
        this.automationService = automationService;
        this.providerRepository = providerRepository;
        this.smsService = smsService;
        this.smsMessageRepository = smsMessageRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.content = content;
        this.telegram = telegram;
        this.links = links;
        this.promoConfigService = promoConfigService;
        this.membershipRepository = membershipRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 150_000)
    @SchedulerLock(name = "ConsultationFollowUpScheduler_run", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void run() {
        Instant now = clock.instant();
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            Long businessId = config.getBusinessId();
            if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
                continue;
            }
            try {
                runForBusiness(businessId, config, now);
            } catch (RuntimeException e) {
                log.warn("Consultation follow-up run failed for business {} (retried next tick): {}", businessId, e.getMessage(), e);
            }
        }
    }

    void runForBusiness(Long businessId, MailchimpConfig mailchimp, Instant now) {
        SquareClient square = squareClientProvider.forBusiness(businessId);
        enroll(businessId, square, now);
        closeExpiredOffers(businessId, square, now);
        if (!isDaytime(now)) {
            return;
        }
        for (ConsultationFollowUp row : repository.findByBusinessIdAndStopReasonIsNullAndConsultationStartAtAfter(
                businessId, now.minus(Duration.ofDays(60)))) {
            try {
                advance(row, square, mailchimp, now);
            } catch (RuntimeException e) {
                log.warn("Consultation follow-up for booking {} failed this tick: {}", row.getSquareBookingId(), e.getMessage(), e);
            }
        }
    }

    // --- Enrollment --------------------------------------------------------------------------

    private void enroll(Long businessId, SquareClient square, Instant now) {
        List<SquareBookingMirror> recent = bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                businessId, now.minus(ENROLL_MAX_AGE), now.minus(ENROLL_MIN_AGE));
        for (SquareBookingMirror booking : recent) {
            if (!ACCEPTED.equals(booking.getStatus()) || booking.getSquareCustomerId() == null
                    || repository.existsByBusinessIdAndSquareBookingId(businessId, booking.getSquareBookingId())) {
                continue;
            }
            String kind = PreVisitNurtureScheduler.visitKind(booking, square);
            if (kind == null) {
                continue; // not a consultation
            }
            enrollOne(businessId, booking, kind, square);
        }
    }

    private void enrollOne(Long businessId, SquareBookingMirror booking, String kind, SquareClient square) {
        String customerId = booking.getSquareCustomerId();
        String teamMemberId = firstTeamMember(booking.getAppointmentSegments());
        String givenName = Names.capitalizeFirst(square.customerGivenNames(List.of(customerId)).get(customerId));
        ConsultationFollowUp row = ConsultationFollowUp.builder()
                .businessId(businessId)
                .squareBookingId(booking.getSquareBookingId())
                .squareCustomerId(customerId)
                .teamMemberId(teamMemberId)
                .artistName(artistFirstName(businessId, teamMemberId))
                .visitKind(kind)
                .consultationStartAt(booking.getStartAt())
                .customerName(givenName)
                .phoneNumber(square.customerPhone(customerId))
                .build();

        // A returning client (had a procedure before this consultation) or one who already booked
        // after it is not someone "thinking it over": recorded, never messaged.
        Set<String> consultationIds = consultationVariationIds(businessId, square,
                bookingMirrorRepository.findByBusinessIdAndSquareCustomerId(businessId, customerId));
        boolean otherProcedure = bookingMirrorRepository.findByBusinessIdAndSquareCustomerId(businessId, customerId).stream()
                .filter(b -> !b.getSquareBookingId().equals(booking.getSquareBookingId()))
                .filter(b -> ACCEPTED.equals(b.getStatus()) || "PENDING".equals(b.getStatus()))
                .anyMatch(b -> !isConsultation(b.getAppointmentSegments(), consultationIds));
        if (otherProcedure) {
            row.setStopReason(ConsultationFollowUp.STOP_NOT_ELIGIBLE);
            row.setStoppedAt(clock.instant());
        }
        repository.save(row);
    }

    // --- Steps -------------------------------------------------------------------------------

    void advance(ConsultationFollowUp row, SquareClient square, MailchimpConfig mailchimp, Instant now) {
        Long businessId = row.getBusinessId();
        if (row.getStaffAlertSentAt() == null) {
            String stopUrl = links.stopUrl(row.getId());
            String who = row.getCustomerName() == null ? "Client" : row.getCustomerName();
            if (row.getPhoneNumber() != null) who += " (" + row.getPhoneNumber() + ")";
            telegram.sendConsultationFollowUpAlert(businessId, who, row.getArtistName(), row.getConsultationStartAt(), stopUrl);
            row.setStaffAlertSentAt(now);
            repository.save(row);
            return; // the first text waits at least ALERT_HEAD_START after this
        }
        Step step = nextDueStep(row, now);
        if (step == null) {
            return;
        }
        if (now.isAfter(row.getConsultationStartAt().plus(step.due).plus(step.maxLate))) {
            step.setState.accept(row, ConsultationFollowUp.STATE_SKIPPED_LATE);
            repository.save(row);
            return;
        }
        if (stopIfDone(row, square, now)) {
            return;
        }
        if (row.getPhoneNumber() != null && smsMessageRepository
                .existsByBusinessIdAndPhoneNumberAndDirectionAndStatusAndAutomationKeyNotAndCreatedAtAfter(
                        businessId, row.getPhoneNumber(), "OUTBOUND", "SENT", AUTOMATION_KEY, now.minus(RECENT_OTHER_SMS))) {
            return; // another automation texted them this week: wait (bounded by maxLate)
        }
        String state = switch (step) {
            case THANKS -> sendThanks(row);
            case INFO -> sendInfoEmail(row, square, mailchimp);
            case CHECKIN -> sendCheckin(row, square, now);
            case OFFER -> sendOffer(row, square, mailchimp, now);
        };
        step.setState.accept(row, state);
        repository.save(row);
    }

    private Step nextDueStep(ConsultationFollowUp row, Instant now) {
        for (Step step : Step.values()) {
            if (step.state.apply(row) != null) {
                continue;
            }
            Instant due = row.getConsultationStartAt().plus(step.due);
            if (step == Step.THANKS && row.getStaffAlertSentAt().plus(ALERT_HEAD_START).isAfter(now)) {
                return null;
            }
            return now.isBefore(due) ? null : step;
        }
        return null;
    }

    /** Booked anything (asked of Square itself) or replied by text: the sequence ends for good. */
    private boolean stopIfDone(ConsultationFollowUp row, SquareClient square, Instant now) {
        if (row.getPhoneNumber() != null && smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                row.getBusinessId(), row.getPhoneNumber(), "INBOUND", row.getCreatedAt())) {
            stop(row, ConsultationFollowUp.STOP_REPLIED, now);
            return true;
        }
        List<SquareClient.Booking> bookings = square.bookingsForCustomer(row.getSquareCustomerId(),
                row.getConsultationStartAt().minus(Duration.ofDays(1)));
        Set<String> consultationIds = consultationVariationIdsLive(square, bookings);
        boolean booked = bookings.stream()
                .filter(b -> !b.id().equals(row.getSquareBookingId()))
                .filter(b -> ACCEPTED.equals(b.status()) || "PENDING".equals(b.status()))
                .anyMatch(b -> !isConsultationLive(b.appointmentSegments(), consultationIds));
        if (booked) {
            stop(row, ConsultationFollowUp.STOP_BOOKED, now);
            return true;
        }
        return false;
    }

    private void stop(ConsultationFollowUp row, String reason, Instant now) {
        row.setStopReason(reason);
        row.setStoppedAt(now);
        repository.save(row);
    }

    private String sendThanks(ConsultationFollowUp row) {
        if (row.getPhoneNumber() == null) return ConsultationFollowUp.STATE_SKIPPED;
        return smsState(smsService.sendTemplated(row.getBusinessId(), "consultation_follow_up_thanks", row.getPhoneNumber(),
                Map.of("name", firstName(row), "artist", artist(row), "link", BOOK_LINK)));
    }

    private String sendInfoEmail(ConsultationFollowUp row, SquareClient square, MailchimpConfig mailchimp) {
        String email = square.customerEmail(row.getSquareCustomerId());
        if (email == null || email.isBlank()) return ConsultationFollowUp.STATE_SKIPPED_NO_EMAIL;
        if (!mailchimp.isConfigured()) return ConsultationFollowUp.STATE_SKIPPED;
        Optional<String> html = templateService.render(row.getBusinessId(), "consultation_follow_up_info", emailVars(row, null));
        if (html.isEmpty()) return ConsultationFollowUp.STATE_SKIPPED_NO_TEMPLATE;
        return emailState(mailchimp, email, "A few things that help you decide, " + firstName(row),
                "Does it hurt, real results, and payment plans", "info", row, html.get());
    }

    private String sendCheckin(ConsultationFollowUp row, SquareClient square, Instant now) {
        if (row.getPhoneNumber() == null) return ConsultationFollowUp.STATE_SKIPPED;
        return smsState(smsService.sendTemplated(row.getBusinessId(), "consultation_follow_up_checkin", row.getPhoneNumber(),
                Map.of("name", firstName(row), "artist", artist(row), "openingsClause", openingsClause(row, square, now))));
    }

    /** Day 45. The $75 comes from Square itself: the client joins the READY75 customer group,
     * whose pricing rule takes $75 off a checkout of $500 or more. Membership is kept well past the
     * 7-day window here; {@link #closeExpiredOffers} ends it at the deadline unless they booked. */
    private String sendOffer(ConsultationFollowUp row, SquareClient square, MailchimpConfig mailchimp, Instant now) {
        Long businessId = row.getBusinessId();
        Optional<PromoConfigService.PromoTerms> terms = promoConfigService.get(businessId, PromoConfigService.CONSULTATION_OFFER_PROMO_CODE)
                .filter(PromoConfigService.PromoTerms::configured);
        String email = square.customerEmail(row.getSquareCustomerId());
        boolean hasEmail = email != null && !email.isBlank() && mailchimp.isConfigured();

        if (terms.isEmpty()) {
            // Offer not set up: the same last touch, without a discount.
            return lastCheckin(row);
        }
        Instant expires = LocalDate.now(clock.withZone(PACIFIC)).plusDays(OFFER_DAYS).plusDays(1).atStartOfDay(PACIFIC).toInstant().minusSeconds(1);
        String expiresText = expires.atZone(PACIFIC).format(OFFER_DATE);
        try {
            square.addCustomerToGroup(row.getSquareCustomerId(), terms.get().squareCustomerGroupId());
            membershipRepository.save(SameDayRebookingGroupMembership.builder()
                    .businessId(businessId)
                    .squareCustomerId(row.getSquareCustomerId())
                    .groupId(terms.get().squareCustomerGroupId())
                    .expiresAt(expires.plus(Duration.ofDays(120)))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Consultation follow-up offer: adding {} to the {} group failed, sending the no-discount check-in instead: {}",
                    row.getSquareCustomerId(), PromoConfigService.CONSULTATION_OFFER_PROMO_CODE, e.getMessage());
            return lastCheckin(row);
        }
        row.setOfferExpiresAt(expires);

        boolean delivered = false;
        if (hasEmail) {
            Optional<String> html = templateService.render(businessId, "consultation_follow_up_offer", emailVars(row, expiresText));
            if (html.isPresent()) {
                delivered = ConsultationFollowUp.STATE_SENT.equals(emailState(mailchimp, email,
                        "Last chance: $75 OFF your procedure, " + firstName(row),
                        "Book by " + expiresText + ", applied automatically at checkout", "offer", row, html.get()));
            }
        }
        if (row.getPhoneNumber() != null) {
            TwilioSmsService.SmsSendResult sms = smsService.sendTemplated(businessId, "consultation_follow_up_offer", row.getPhoneNumber(),
                    Map.of("name", firstName(row), "artist", artist(row), "expires", expiresText, "link", BOOK_LINK));
            delivered |= sms.sent();
            if (!sms.sent() && !delivered) {
                // No marketing consent and no email: the plain last check-in instead (no discount in it).
                lastCheckin(row);
            }
        }
        return delivered ? ConsultationFollowUp.STATE_SENT : ConsultationFollowUp.STATE_SKIPPED;
    }

    private String lastCheckin(ConsultationFollowUp row) {
        if (row.getPhoneNumber() == null) return ConsultationFollowUp.STATE_SKIPPED;
        smsService.sendTemplated(row.getBusinessId(), "consultation_follow_up_last_checkin", row.getPhoneNumber(),
                Map.of("name", firstName(row), "artist", artist(row), "link", BOOK_LINK));
        return ConsultationFollowUp.STATE_SKIPPED;
    }

    /** When an offer's 7 days are over: a client who booked a procedure inside the window keeps the
     * discount until two days after that visit; everyone else leaves the group now (the shared
     * SameDayRebookingGroupExpiryScheduler does the actual Square removal). */
    void closeExpiredOffers(Long businessId, SquareClient square, Instant now) {
        for (ConsultationFollowUp row : repository.findByBusinessIdAndOfferStateAndOfferExtendedAtIsNullAndOfferExpiresAtBefore(
                businessId, ConsultationFollowUp.STATE_SENT, now)) {
            Optional<PromoConfigService.PromoTerms> terms = promoConfigService.get(businessId, PromoConfigService.CONSULTATION_OFFER_PROMO_CODE);
            Optional<SameDayRebookingGroupMembership> membership = terms.flatMap(t -> membershipRepository
                    .findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(
                            businessId, row.getSquareCustomerId(), t.squareCustomerGroupId()));
            Instant windowStart = row.getOfferExpiresAt().minus(Duration.ofDays(OFFER_DAYS + 1));
            List<SquareClient.Booking> bookings = square.bookingsForCustomer(row.getSquareCustomerId(), windowStart);
            Set<String> consultationIds = consultationVariationIdsLive(square, bookings);
            Optional<Instant> bookedVisit = bookings.stream()
                    .filter(b -> ACCEPTED.equals(b.status()) || "PENDING".equals(b.status()))
                    .filter(b -> !isConsultationLive(b.appointmentSegments(), consultationIds))
                    .filter(b -> b.createdAt() != null && !Instant.parse(b.createdAt()).isAfter(row.getOfferExpiresAt()))
                    .map(b -> Instant.parse(b.startAt()))
                    .max(Instant::compareTo);
            membership.ifPresent(m -> {
                m.setExpiresAt(bookedVisit.map(v -> v.plus(Duration.ofDays(2))).orElse(now));
                membershipRepository.save(m);
            });
            row.setOfferExtendedAt(now);
            repository.save(row);
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private String emailState(MailchimpConfig mailchimp, String email, String subject, String preview, String step,
                              ConsultationFollowUp row, String html) {
        try {
            mailchimpEmailService.sendWinbackEmail(mailchimp, email, subject, preview,
                    AUTOMATION_KEY + " " + step + ": booking " + row.getSquareBookingId(), html);
            return ConsultationFollowUp.STATE_SENT;
        } catch (Exception e) {
            log.warn("Consultation follow-up {} email failed for booking {}: {}", step, row.getSquareBookingId(), e.getMessage());
            return ConsultationFollowUp.STATE_SEND_FAILED;
        }
    }

    private static String smsState(TwilioSmsService.SmsSendResult result) {
        if (result.sent()) return ConsultationFollowUp.STATE_SENT;
        return "send_failed".equals(result.reason()) ? ConsultationFollowUp.STATE_SEND_FAILED : ConsultationFollowUp.STATE_SKIPPED;
    }

    private Map<String, String> emailVars(ConsultationFollowUp row, String expiresText) {
        Map<String, String> v = new HashMap<>();
        v.put("FNAME", HtmlUtils.htmlEscape(firstName(row)));
        v.put("ARTIST", HtmlUtils.htmlEscape(artist(row)));
        v.put("ARTIST_PHOTO_URL", content.artist(row.getBusinessId(), row.getArtistName())
                .map(PreVisitNurtureContent.Artist::photoUrl).orElse(""));
        if (expiresText != null) v.put("EXPIRES", HtmlUtils.htmlEscape(expiresText));
        return v;
    }

    /** " I have openings on Tue, Oct 28 and Thu, Oct 30." from the artist's real Square
     * availability over the next two weeks, or "" (never a made-up opening). */
    String openingsClause(ConsultationFollowUp row, SquareClient square, Instant now) {
        Optional<String> variation = content.availabilityVariationId(row.getBusinessId());
        if (row.getTeamMemberId() == null || variation.isEmpty()) return "";
        try {
            List<Instant> starts = square.availableSlotStarts(row.getTeamMemberId(), variation.get(),
                    now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(15)));
            List<String> days = new ArrayList<>(new LinkedHashSet<>(starts.stream().sorted()
                    .map(s -> s.atZone(PACIFIC).format(OPENING_DATE)).toList()));
            if (days.isEmpty()) return "";
            return days.size() == 1 ? " I have an opening on " + days.get(0) + "."
                    : " I have openings on " + days.get(0) + " and " + days.get(1) + ".";
        } catch (RuntimeException e) {
            log.info("Consultation follow-up: availability lookup failed, check-in goes out without openings: {}", e.getMessage());
            return "";
        }
    }

    private static String firstName(ConsultationFollowUp row) {
        return row.getCustomerName() == null || row.getCustomerName().isBlank() ? "there" : row.getCustomerName();
    }

    private static String artist(ConsultationFollowUp row) {
        return row.getArtistName() == null || row.getArtistName().isBlank() ? "your artist" : row.getArtistName();
    }

    private boolean isDaytime(Instant now) {
        int hour = now.atZone(PACIFIC).getHour();
        return hour >= SEND_FROM_HOUR && hour < SEND_UNTIL_HOUR;
    }

    private static String firstTeamMember(List<SquareBookingMirror.Segment> segments) {
        return segments == null || segments.isEmpty() ? null : segments.get(0).teamMemberId();
    }

    private String artistFirstName(Long businessId, String teamMemberId) {
        if (teamMemberId == null) return null;
        return providerRepository.findAllByBusinessId(businessId).stream()
                .filter(p -> p.getSquareTeamMemberIds().contains(teamMemberId))
                .findFirst()
                .map(Provider::getDisplayName)
                .map(Names::firstNameOnly)
                .orElse(null);
    }

    private static Set<String> consultationVariationIds(Long businessId, SquareClient square, List<SquareBookingMirror> bookings) {
        Set<String> ids = new HashSet<>();
        for (SquareBookingMirror b : bookings) {
            if (b.getAppointmentSegments() == null) continue;
            b.getAppointmentSegments().forEach(s -> { if (s.serviceVariationId() != null) ids.add(s.serviceVariationId()); });
        }
        return consultationsAmong(square, ids);
    }

    private static Set<String> consultationVariationIdsLive(SquareClient square, List<SquareClient.Booking> bookings) {
        Set<String> ids = new HashSet<>();
        for (SquareClient.Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            b.appointmentSegments().forEach(s -> { if (s.serviceVariationId() != null) ids.add(s.serviceVariationId()); });
        }
        return consultationsAmong(square, ids);
    }

    private static Set<String> consultationsAmong(SquareClient square, Set<String> variationIds) {
        if (variationIds.isEmpty()) return Set.of();
        Map<String, String> names = square.catalogNames(variationIds);
        Set<String> out = new HashSet<>();
        names.forEach((id, name) -> { if (name != null && name.toLowerCase(Locale.US).contains("consultation")) out.add(id); });
        return out;
    }

    private static boolean isConsultation(List<SquareBookingMirror.Segment> segments, Set<String> consultationIds) {
        return segments != null && !segments.isEmpty() && segments.stream().allMatch(s -> consultationIds.contains(s.serviceVariationId()));
    }

    private static boolean isConsultationLive(List<SquareClient.AppointmentSegment> segments, Set<String> consultationIds) {
        return segments != null && !segments.isEmpty() && segments.stream().allMatch(s -> consultationIds.contains(s.serviceVariationId()));
    }
}
