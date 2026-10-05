package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.PreVisitNurtureSend;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.PreVisitNurtureSendRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.Names;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Pre-visit nurture email sequence (owner request 2026-09-05): a customer who just booked gets a
 * warm welcome email shortly after (step 1, {@link #sendDueWelcomeEmails}), and — only if their
 * appointment is far enough out to have a real "day before" — a reminder email the day before
 * (step 2, {@link #sendDueReminderEmails}). Goal is fewer cancellations/no-shows through
 * familiarity with the studio before the visit, not a booking-conversion ask (the customer has
 * already booked); framed that way in both templates.
 *
 * <p>PMU consultations (owner request 2026-10-05) get their own template set, picked once at
 * welcome time from the booked Square service and stored as {@link PreVisitNurtureSend#getVisitKind()}:
 * clients often wait days or weeks for a consultation, so on top of the welcome and the
 * day-before reminder, a long wait adds "meet your artist" (~2 days after booking, visit 4+ days
 * out; {@link #sendDueMeetArtistEmails}) and "getting ready" (~3 days before, booked 8+ days out;
 * {@link #sendDuePrepEmails}). A step whose template a business doesn't have is skipped, so
 * business 1's generic welcome/reminder pair is unchanged.
 *
 * <p>Reads {@link SquareBookingMirror} (the already-synced local copy of Square's own bookings —
 * see that class's own doc) rather than hooking a new webhook path: this automation only needs to
 * notice a booking within a few minutes of it existing, which the mirror's own webhook + periodic
 * reconciliation already provides, so a dedicated trigger service would be pure duplication.
 */
@Component
public class PreVisitNurtureScheduler {

    private static final Logger log = LoggerFactory.getLogger(PreVisitNurtureScheduler.class);
    private static final String AUTOMATION_KEY = "pre_visit_nurture";
    private static final String ACCEPTED_STATUS = "ACCEPTED";
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    /** Welcome email fires 5-30 minutes after the booking was created — long enough to not land
     * in the same instant as Square's own confirmation SMS/email (a separate, personal touch, not
     * a duplicate of it), short enough that it still reads as "just booked," not a delayed
     * afterthought. Bounded scan, same "a booking older than this never gets welcomed" shape as
     * every other poller here. */
    private static final Duration WELCOME_MIN_AGE = Duration.ofMinutes(5);
    private static final Duration WELCOME_MAX_AGE = Duration.ofMinutes(30);

    /** Reminder window: a day out, generously wide (10h either side of the 24h mark) so a
     * 15-minute-ish poll cadence and DST/clock drift can't cause a booking to fall through the
     * gap between two ticks. A booking made less than ~14h before its own start time never enters
     * this window at all (its appointment_start_at is already in the past relative to "now +14h"
     * by the time this would first check it) — that's the intended "too soon to have a day-before"
     * exclusion, not a bug to work around. */
    private static final Duration REMINDER_MIN_LEAD = Duration.ofHours(14);
    private static final Duration REMINDER_MAX_LEAD = Duration.ofHours(34);

    /** "Meet your artist": about two days after booking, only when the whole wait is 4+ days, and
     * never closer than 40h to the visit so it can't land on the same day as the reminder. */
    private static final Duration MEET_ARTIST_AFTER_BOOKING = Duration.ofHours(44);
    private static final Duration MEET_ARTIST_MIN_REMAINING = Duration.ofHours(40);
    private static final Duration MEET_ARTIST_MIN_WAIT = Duration.ofDays(4);

    /** "Getting ready": about three days before the visit, only when it was booked 8+ days out
     * (shorter waits already get welcome, meet-your-artist and the reminder, which is enough). */
    private static final Duration PREP_MIN_LEAD = Duration.ofHours(60);
    private static final Duration PREP_MAX_LEAD = Duration.ofHours(84);
    private static final Duration PREP_MIN_WAIT = Duration.ofDays(8);

    /** Every step after the welcome is a scheduled nudge, not a reply to something the client just
     * did, so it waits for daytime in Pacific time (08:00-21:00). Each step's window is wider than
     * the 11 quiet hours, so nothing falls through. The welcome itself goes out right away: the
     * client just booked, at whatever hour that was. */
    private static final int SEND_FROM_HOUR = 8;
    private static final int SEND_UNTIL_HOUR = 21;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CALENDAR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int DEFAULT_CONSULTATION_MINUTES = 30;

    private enum Step {
        MEET_ARTIST("meet_artist", PreVisitNurtureSend::getMeetArtistState, PreVisitNurtureSend::setMeetArtistState),
        PREP("prep", PreVisitNurtureSend::getPrepState, PreVisitNurtureSend::setPrepState),
        REMINDER("reminder", PreVisitNurtureSend::getReminderState, PreVisitNurtureSend::setReminderState);

        final String templateSuffix;
        final Function<PreVisitNurtureSend, String> state;
        final BiConsumer<PreVisitNurtureSend, String> setState;

        Step(String templateSuffix, Function<PreVisitNurtureSend, String> state,
             BiConsumer<PreVisitNurtureSend, String> setState) {
            this.templateSuffix = templateSuffix;
            this.state = state;
            this.setState = setState;
        }
    }

    private final SquareBookingMirrorRepository bookingMirrorRepository;
    private final PreVisitNurtureSendRepository sendRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpEmailService mailchimpEmailService;
    private final MailchimpEmailTemplateService templateService;
    private final SquareClientProvider squareClientProvider;
    private final SmsAutomationService automationService;
    private final ProviderRepository providerRepository;
    private final PreVisitNurtureContent content;
    private final Clock clock;

    @Autowired
    public PreVisitNurtureScheduler(SquareBookingMirrorRepository bookingMirrorRepository,
                                     PreVisitNurtureSendRepository sendRepository,
                                     MailchimpConfigRepository mailchimpConfigRepository,
                                     MailchimpEmailService mailchimpEmailService,
                                     MailchimpEmailTemplateService templateService,
                                     SquareClientProvider squareClientProvider,
                                     SmsAutomationService automationService,
                                     ProviderRepository providerRepository,
                                     PreVisitNurtureContent content) {
        this(bookingMirrorRepository, sendRepository, mailchimpConfigRepository, mailchimpEmailService,
                templateService, squareClientProvider, automationService, providerRepository, content,
                Clock.systemUTC());
    }

    /** Tests pin the clock: every step after the welcome only sends in Pacific daytime. */
    PreVisitNurtureScheduler(SquareBookingMirrorRepository bookingMirrorRepository,
                                     PreVisitNurtureSendRepository sendRepository,
                                     MailchimpConfigRepository mailchimpConfigRepository,
                                     MailchimpEmailService mailchimpEmailService,
                                     MailchimpEmailTemplateService templateService,
                                     SquareClientProvider squareClientProvider,
                                     SmsAutomationService automationService,
                                     ProviderRepository providerRepository,
                                     PreVisitNurtureContent content,
                                     Clock clock) {
        this.bookingMirrorRepository = bookingMirrorRepository;
        this.sendRepository = sendRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.mailchimpEmailService = mailchimpEmailService;
        this.templateService = templateService;
        this.squareClientProvider = squareClientProvider;
        this.automationService = automationService;
        this.providerRepository = providerRepository;
        this.content = content;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 30_000)
    @SchedulerLock(name = "PreVisitNurtureScheduler_sendDueWelcomeEmails", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueWelcomeEmails() {
        Instant now = clock.instant();
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            List<SquareBookingMirror> candidates = bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(
                    businessId, ACCEPTED_STATUS, now.minus(WELCOME_MAX_AGE), now.minus(WELCOME_MIN_AGE));
            for (SquareBookingMirror booking : candidates) {
                if (sendRepository.existsByBusinessIdAndSquareBookingId(businessId, booking.getSquareBookingId())) {
                    continue; // already considered this booking
                }
                try {
                    processWelcomeEmail(booking, config);
                } catch (RuntimeException e) {
                    log.warn("Pre-visit nurture welcome email failed for booking {} (skipped, not retried): {}",
                            booking.getSquareBookingId(), e.getMessage(), e);
                }
            }
        }
    }

    private void processWelcomeEmail(SquareBookingMirror booking, MailchimpConfig config) {
        Long businessId = booking.getBusinessId();
        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_DISABLED, null);
            return;
        }
        SquareClient square;
        String kind;
        try {
            square = squareClientProvider.forBusiness(businessId);
            kind = visitKind(booking, square);
        } catch (RuntimeException e) {
            log.warn("Pre-visit nurture welcome email skipped for business {} (Square unavailable this run): {}",
                    businessId, e.getMessage());
            return; // no row saved — retried next tick, same as this package's other Square-failure handling
        }
        String templateKey = templateKey(kind, "welcome");
        if (!templateService.has(businessId, templateKey)) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE, kind);
            return;
        }
        String customerId = booking.getSquareCustomerId();
        String email = customerId == null ? null : square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_NO_EMAIL, kind);
            return;
        }

        Map<String, String> vars = vars(booking, kind, square);
        Optional<String> html = templateService.render(businessId, templateKey, vars);
        if (html.isEmpty()) {
            save(booking, PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE, kind);
            return;
        }

        String[] subject = kind == null
                ? new String[] {"You're booked, " + vars.get("FNAME_TEXT") + "! A little about us",
                        "Excited to see you, here's what to expect"}
                : new String[] {"Your consultation with " + vars.get("ARTIST_TEXT") + ": what to expect",
                        vars.get("DAY_TEXT") + " at " + vars.get("TIME_TEXT") + ", plus how to help "
                                + vars.get("ARTIST_TEXT") + " prepare"};
        String campaignTitle = AUTOMATION_KEY + " welcome — booking " + booking.getSquareBookingId();

        String savedState;
        try {
            mailchimpEmailService.sendWinbackEmail(config, email, subject[0], subject[1], campaignTitle, html.get());
            savedState = PreVisitNurtureSend.STATE_SENT;
        } catch (Exception e) {
            log.warn("Pre-visit nurture welcome email send failed for booking {} (not retried): {}",
                    booking.getSquareBookingId(), e.getMessage());
            savedState = PreVisitNurtureSend.STATE_SEND_FAILED;
        }
        save(booking, savedState, kind);
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    @SchedulerLock(name = "PreVisitNurtureScheduler_sendDueReminderEmails", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueReminderEmails() {
        Instant now = clock.instant();
        if (!isDaytime(now)) {
            return;
        }
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            syncRescheduledVisits(businessId, now);
            List<PreVisitNurtureSend> candidates = sendRepository
                    .findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                            businessId, PreVisitNurtureSend.STATE_SENT,
                            now.plus(REMINDER_MIN_LEAD), now.plus(REMINDER_MAX_LEAD));
            processLaterStep(candidates, config, Step.REMINDER);
        }
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 90_000)
    @SchedulerLock(name = "PreVisitNurtureScheduler_sendDueMeetArtistEmails", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDueMeetArtistEmails() {
        Instant now = clock.instant();
        if (!isDaytime(now)) {
            return;
        }
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            syncRescheduledVisits(businessId, now);
            List<PreVisitNurtureSend> candidates = sendRepository
                    .findByBusinessIdAndWelcomeStateAndMeetArtistStateIsNullAndCreatedAtBeforeAndAppointmentStartAtAfter(
                            businessId, PreVisitNurtureSend.STATE_SENT,
                            now.minus(MEET_ARTIST_AFTER_BOOKING), now.plus(MEET_ARTIST_MIN_REMAINING))
                    .stream()
                    .filter(row -> waited(row, MEET_ARTIST_MIN_WAIT))
                    .toList();
            processLaterStep(candidates, config, Step.MEET_ARTIST);
        }
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    @SchedulerLock(name = "PreVisitNurtureScheduler_sendDuePrepEmails", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void sendDuePrepEmails() {
        Instant now = clock.instant();
        if (!isDaytime(now)) {
            return;
        }
        for (MailchimpConfig config : mailchimpConfigRepository.findAll()) {
            if (!config.isConfigured()) {
                continue;
            }
            Long businessId = config.getBusinessId();
            syncRescheduledVisits(businessId, now);
            List<PreVisitNurtureSend> candidates = sendRepository
                    .findByBusinessIdAndWelcomeStateAndPrepStateIsNullAndAppointmentStartAtBetween(
                            businessId, PreVisitNurtureSend.STATE_SENT,
                            now.plus(PREP_MIN_LEAD), now.plus(PREP_MAX_LEAD))
                    .stream()
                    .filter(row -> waited(row, PREP_MIN_WAIT))
                    .toList();
            processLaterStep(candidates, config, Step.PREP);
        }
    }

    private void processLaterStep(List<PreVisitNurtureSend> candidates, MailchimpConfig config, Step step) {
        for (PreVisitNurtureSend row : candidates) {
            try {
                processLaterStep(row, config, step);
            } catch (RuntimeException e) {
                log.warn("Pre-visit nurture {} email failed for booking {} (skipped, not retried): {}",
                        step.templateSuffix, row.getSquareBookingId(), e.getMessage(), e);
            }
        }
    }

    private void processLaterStep(PreVisitNurtureSend row, MailchimpConfig config, Step step) {
        Long businessId = row.getBusinessId();
        if (!automationService.isEnabled(businessId, AUTOMATION_KEY)) {
            saveStepState(row, step, PreVisitNurtureSend.STATE_SKIPPED_DISABLED);
            return;
        }
        String templateKey = templateKey(row.getVisitKind(), step.templateSuffix);
        if (!templateService.has(businessId, templateKey)) {
            saveStepState(row, step, PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE);
            return;
        }
        Optional<SquareBookingMirror> current = bookingMirrorRepository
                .findByBusinessIdAndSquareBookingId(businessId, row.getSquareBookingId());
        if (current.isEmpty() || !ACCEPTED_STATUS.equals(current.get().getStatus())) {
            saveStepState(row, step, PreVisitNurtureSend.STATE_SKIPPED_CANCELLED);
            return;
        }
        SquareBookingMirror booking = current.get();

        SquareClient square;
        try {
            square = squareClientProvider.forBusiness(businessId);
        } catch (RuntimeException e) {
            log.warn("Pre-visit nurture {} email skipped for business {} (Square unavailable this run): {}",
                    step.templateSuffix, businessId, e.getMessage());
            return;
        }
        String customerId = row.getSquareCustomerId();
        String email = customerId == null ? null : square.customerEmail(customerId);
        if (email == null || email.isBlank()) {
            saveStepState(row, step, PreVisitNurtureSend.STATE_SKIPPED_NO_EMAIL);
            return;
        }

        Map<String, String> vars = vars(booking, row.getVisitKind(), square);
        Optional<String> html = templateService.render(businessId, templateKey, vars);
        if (html.isEmpty()) {
            saveStepState(row, step, PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE);
            return;
        }

        String[] subject = subject(step, row.getVisitKind(), vars);
        String campaignTitle = AUTOMATION_KEY + " " + step.templateSuffix + ": booking " + row.getSquareBookingId();
        try {
            mailchimpEmailService.sendWinbackEmail(config, email, subject[0], subject[1], campaignTitle, html.get());
            saveStepState(row, step, PreVisitNurtureSend.STATE_SENT);
        } catch (Exception e) {
            log.warn("Pre-visit nurture {} email send failed for booking {} (not retried): {}",
                    step.templateSuffix, row.getSquareBookingId(), e.getMessage());
            saveStepState(row, step, PreVisitNurtureSend.STATE_SEND_FAILED);
        }
    }

    /** Subject line and preview text per step. */
    private static String[] subject(Step step, String kind, Map<String, String> vars) {
        String artist = vars.get("ARTIST_TEXT");
        if (kind == null) {
            // Business 1's generic pair has only a reminder after the welcome.
            return new String[] {"See you tomorrow, " + vars.get("FNAME_TEXT") + "!",
                    "Quick reminder + what you need to know before you come in"};
        }
        return switch (step) {
            case MEET_ARTIST -> new String[] {"Meet " + artist + ", your PMU artist",
                    "A little about who you'll be talking to on " + vars.get("DAY_TEXT")};
            case PREP -> new String[] {"Getting ready for your consultation with " + artist,
                    "A few things worth thinking about before " + vars.get("DAY_TEXT")};
            case REMINDER -> new String[] {vars.get("RELATIVE_DAY_TEXT") + " at " + vars.get("TIME_TEXT")
                    + ": your consultation with " + artist,
                    PreVisitNurtureSend.KIND_CONSULTATION_ONLINE.equals(kind)
                            ? artist + " will call you at the number you booked with"
                            : "See you at the studio, please arrive 5 minutes early"};
        };
    }

    /** {@code null} for the generic template pair; a consultation kind when the booking's first
     * service is a consultation ("Online Consultation" vs. the in-studio one), read from the
     * Square catalog name so a renamed or new consultation service keeps working. */
    static String visitKind(SquareBookingMirror booking, SquareClient square) {
        List<SquareBookingMirror.Segment> segments = booking.getAppointmentSegments();
        String variationId = segments == null || segments.isEmpty() ? null : segments.get(0).serviceVariationId();
        if (variationId == null) {
            return null;
        }
        String name = square.catalogNames(Set.of(variationId)).get(variationId);
        if (name == null || !name.toLowerCase(Locale.US).contains("consultation")) {
            return null;
        }
        return name.toLowerCase(Locale.US).contains("online")
                ? PreVisitNurtureSend.KIND_CONSULTATION_ONLINE
                : PreVisitNurtureSend.KIND_CONSULTATION_IN_PERSON;
    }

    private static String templateKey(String kind, String step) {
        return kind == null ? AUTOMATION_KEY + "_" + step : AUTOMATION_KEY + "_consultation_" + step;
    }

    /** Template variables. Every {@code *_TEXT} entry is the plain-text value for subject lines;
     * the template tokens themselves are HTML-escaped. */
    private Map<String, String> vars(SquareBookingMirror booking, String kind, SquareClient square) {
        Long businessId = booking.getBusinessId();
        String customerId = booking.getSquareCustomerId();
        String givenName = Names.capitalizeFirst(
                customerId == null ? null : square.customerGivenNames(List.of(customerId)).get(customerId));
        String technician = technicianFirstName(booking, businessId);

        Map<String, String> text = new HashMap<>();
        text.put("FNAME", givenName == null ? "there" : givenName);
        text.put("TECHNICIAN_CLAUSE", technician == null ? "" : " with " + technician);

        if (kind != null && booking.getStartAt() != null) {
            boolean online = PreVisitNurtureSend.KIND_CONSULTATION_ONLINE.equals(kind);
            String artist = technician == null ? "your artist" : technician;
            ZonedDateTime start = booking.getStartAt().atZone(PACIFIC);
            PreVisitNurtureContent.Studio studio = content.studio(businessId)
                    .orElse(new PreVisitNurtureContent.Studio("", "", ""));
            PreVisitNurtureContent.Artist profile = content.artist(businessId, technician)
                    .orElse(new PreVisitNurtureContent.Artist("", "", "", "", ""));

            text.put("ARTIST", artist);
            text.put("ARTIST_CAP", Character.toUpperCase(artist.charAt(0)) + artist.substring(1));
            text.put("DAY", start.format(DAY_FORMAT));
            text.put("TIME", start.format(TIME_FORMAT));
            text.put("RELATIVE_DAY", relativeDay(start.toLocalDate(), LocalDate.now(clock.withZone(PACIFIC)), start));
            text.put("FORMAT_TITLE", online ? "Online consultation, by phone" : "In-studio consultation");
            text.put("FORMAT_DETAILS", online
                    ? text.get("ARTIST_CAP") + " will call you at the phone number you booked with. There's no need to come to the studio."
                    : "At our studio, " + studio.address() + ". Please arrive 5 minutes early.");
            text.put("STUDIO_NAME", studio.name());
            text.put("STUDIO_ADDRESS", studio.address());
            text.put("TEXT_NUMBER", studio.textNumber());
            text.put("ARTIST_PHOTO_URL", profile.photoUrl());
            text.put("ARTIST_HEADLINE", profile.headline());
            text.put("ARTIST_BIO", profile.bio());
            text.put("ARTIST_QUOTE", profile.quote());
            text.put("ARTIST_QUOTE_AUTHOR", profile.quoteAuthor());
            text.put("CALENDAR_URL", calendarUrl(booking, online, artist, studio));
        }

        Map<String, String> vars = new HashMap<>();
        text.forEach((k, v) -> {
            vars.put(k, HtmlUtils.htmlEscape(v));
            vars.put(k + "_TEXT", v);
        });
        return vars;
    }

    private static String relativeDay(LocalDate day, LocalDate today, ZonedDateTime start) {
        if (day.equals(today)) return "Today";
        if (day.equals(today.plusDays(1))) return "Tomorrow";
        return start.format(DateTimeFormatter.ofPattern("EEEE", Locale.US));
    }

    /** Google Calendar "add event" link. Square's own confirmation email carries an .ics file for
     * other calendars; this is the one-tap version for the many clients on Gmail. */
    private static String calendarUrl(SquareBookingMirror booking, boolean online, String artist,
                                      PreVisitNurtureContent.Studio studio) {
        List<SquareBookingMirror.Segment> segments = booking.getAppointmentSegments();
        Integer minutes = segments == null || segments.isEmpty() ? null : segments.get(0).durationMinutes();
        Instant start = booking.getStartAt();
        Instant end = start.plus(Duration.ofMinutes(minutes == null || minutes <= 0 ? DEFAULT_CONSULTATION_MINUTES : minutes));
        String title = (online ? "Online PMU consultation (phone call) with " : "PMU consultation with ") + artist;
        String details = online
                ? artist + " from " + studio.name() + " will call you at the phone number you booked with."
                : "Please arrive 5 minutes early. " + studio.name() + ".";
        String location = online ? "Phone call" : studio.address();
        return "https://calendar.google.com/calendar/render?action=TEMPLATE"
                + "&text=" + encode(title)
                + "&dates=" + CALENDAR_FORMAT.format(start.atOffset(ZoneOffset.UTC))
                + "/" + CALENDAR_FORMAT.format(end.atOffset(ZoneOffset.UTC))
                + "&details=" + encode(details)
                + "&location=" + encode(location);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isDaytime(Instant now) {
        int hour = now.atZone(PACIFIC).getHour();
        return hour >= SEND_FROM_HOUR && hour < SEND_UNTIL_HOUR;
    }

    private static boolean waited(PreVisitNurtureSend row, Duration minWait) {
        return row.getCreatedAt() != null
                && Duration.between(row.getCreatedAt(), row.getAppointmentStartAt()).compareTo(minWait) >= 0;
    }

    /** A client who moves their visit keeps the same Square booking id with a new start time;
     * the stored start time drives every later step's window, so it follows the mirror. */
    private void syncRescheduledVisits(Long businessId, Instant now) {
        for (PreVisitNurtureSend row : sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtAfter(
                businessId, PreVisitNurtureSend.STATE_SENT, now)) {
            bookingMirrorRepository.findByBusinessIdAndSquareBookingId(businessId, row.getSquareBookingId())
                    .map(SquareBookingMirror::getStartAt)
                    .filter(start -> !start.equals(row.getAppointmentStartAt()))
                    .ifPresent(start -> {
                        row.setAppointmentStartAt(start);
                        sendRepository.save(row);
                    });
        }
    }

    /** Best-effort — the booking's own first segment names the real Square team member id
     * performing it, matched against this business's own {@link Provider#getSquareTeamMemberIds()}.
     * {@code null} if unresolvable (no segments, or a team member id no {@link Provider} row
     * claims), same degrade-gracefully convention every other technician-naming call site here
     * follows. */
    private String technicianFirstName(SquareBookingMirror booking, Long businessId) {
        if (booking.getAppointmentSegments() == null || booking.getAppointmentSegments().isEmpty()) {
            return null;
        }
        String teamMemberId = booking.getAppointmentSegments().get(0).teamMemberId();
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

    private void save(SquareBookingMirror booking, String welcomeState, String visitKind) {
        sendRepository.save(PreVisitNurtureSend.builder()
                .businessId(booking.getBusinessId())
                .squareBookingId(booking.getSquareBookingId())
                .squareCustomerId(booking.getSquareCustomerId())
                .appointmentStartAt(booking.getStartAt())
                .visitKind(visitKind)
                .welcomeState(welcomeState)
                .build());
    }

    private void saveStepState(PreVisitNurtureSend row, Step step, String state) {
        step.setState.accept(row, state);
        sendRepository.save(row);
    }
}
