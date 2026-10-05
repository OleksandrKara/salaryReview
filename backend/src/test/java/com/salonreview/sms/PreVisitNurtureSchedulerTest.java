package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.PreVisitNurtureSend;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.PreVisitNurtureSendRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Both legs of the pre-visit nurture sequence — see PreVisitNurtureScheduler's own class doc for
 * why it reads SquareBookingMirror instead of a dedicated webhook trigger. */
class PreVisitNurtureSchedulerTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String BOOKING_ID = "bk-1";
    private static final String CUSTOMER_ID = "cust1";
    private static final String AUTOMATION_KEY = "pre_visit_nurture";

    private SquareBookingMirrorRepository bookingMirrorRepository;
    private PreVisitNurtureSendRepository sendRepository;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpEmailService mailchimpEmailService;
    private MailchimpEmailTemplateService templateService;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private SmsAutomationService automationService;
    private ProviderRepository providerRepository;
    private PreVisitNurtureContent content;
    private PreVisitNurtureScheduler scheduler;

    /** 11:00 Pacific on a Tuesday: inside the daytime send hours of every step after the welcome. */
    private static final Instant NOW = Instant.parse("2026-10-06T18:00:00Z");

    @BeforeEach
    void setUp() {
        bookingMirrorRepository = mock(SquareBookingMirrorRepository.class);
        sendRepository = mock(PreVisitNurtureSendRepository.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpEmailService = mock(MailchimpEmailService.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        providerRepository = mock(ProviderRepository.class);
        content = mock(PreVisitNurtureContent.class);
        scheduler = schedulerAt(NOW);

        MailchimpConfig config = MailchimpConfig.builder().businessId(BUSINESS_ID)
                .apiKey("k-us1").audienceId("a1").fromName("Lucy").fromEmail("lucy@akluxnails.com")
                .replyToEmail("lucy@akluxnails.com").build();
        when(mailchimpConfigRepository.findAll()).thenReturn(List.of(config));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(automationService.isEnabled(eq(BUSINESS_ID), anyString())).thenReturn(true);
        when(square.customerEmail(CUSTOMER_ID)).thenReturn("jane@example.com");
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "jane"));
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.of("<html></html>"));
        when(providerRepository.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of());
        when(templateService.has(eq(BUSINESS_ID), anyString())).thenReturn(true);
        when(content.studio(BUSINESS_ID)).thenReturn(Optional.of(
                new PreVisitNurtureContent.Studio("Anna Kara's PMU Studio", "1357 Seventh Ave", "(833) 912-5558")));
        when(content.artist(eq(BUSINESS_ID), any())).thenReturn(Optional.of(
                new PreVisitNurtureContent.Artist("https://x/p.jpg", "7+ years", "Bio", "Quote", "Author")));
    }

    private PreVisitNurtureScheduler schedulerAt(Instant now) {
        return new PreVisitNurtureScheduler(bookingMirrorRepository, sendRepository, mailchimpConfigRepository,
                mailchimpEmailService, templateService, squareClientProvider, automationService, providerRepository,
                content, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static SquareBookingMirror booking() {
        return SquareBookingMirror.builder().id(1L).businessId(BUSINESS_ID).squareBookingId(BOOKING_ID)
                .squareCustomerId(CUSTOMER_ID).status("ACCEPTED").startAt(NOW.plus(20, ChronoUnit.HOURS))
                .createdAt(NOW.minus(15, ChronoUnit.MINUTES)).build();
    }

    // --- Welcome email ---

    @Test
    @DisplayName("booking in the welcome window, customer has an email on file → sends, saves a SENT row")
    void welcomeSentAndSaved() throws Exception {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(sendRepository.existsByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID)).thenReturn(false);
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        PreVisitNurtureSend saved = captor.getValue();
        assertThat(saved.getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SENT);
        assertThat(saved.getReminderState()).isNull();
        assertThat(saved.getBusinessId()).isEqualTo(BUSINESS_ID);
        assertThat(saved.getSquareBookingId()).isEqualTo(BOOKING_ID);
        assertThat(saved.getSquareCustomerId()).isEqualTo(CUSTOMER_ID);
        verify(templateService).render(eq(BUSINESS_ID), eq("pre_visit_nurture_welcome"), any());
    }

    @Test
    @DisplayName("already has a row for this booking → skipped entirely, not re-considered")
    void welcomeAlreadyProcessedSkipped() {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(sendRepository.existsByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID)).thenReturn(true);

        scheduler.sendDueWelcomeEmails();

        verify(sendRepository, never()).save(any());
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("automation disabled → skipped with SKIPPED_DISABLED, no email sent")
    void welcomeDisabledSkipped() {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(automationService.isEnabled(BUSINESS_ID, AUTOMATION_KEY)).thenReturn(false);

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_DISABLED);
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("customer has no email on file → skipped with SKIPPED_NO_EMAIL")
    void welcomeNoEmailSkipped() {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(square.customerEmail(CUSTOMER_ID)).thenReturn(null);

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_NO_EMAIL);
    }

    @Test
    @DisplayName("no template registered for this business → skipped with SKIPPED_NO_TEMPLATE")
    void welcomeNoTemplateSkipped() {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(templateService.render(eq(BUSINESS_ID), eq("pre_visit_nurture_welcome"), any())).thenReturn(Optional.empty());

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE);
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("Mailchimp send throws → SEND_FAILED recorded")
    void welcomeSendFailureRecordsSendFailed() throws Exception {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Mailchimp API error"));

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SEND_FAILED);
    }

    @Test
    @DisplayName("a resolvable technician on the booking's first segment names them in TECHNICIAN_CLAUSE")
    void welcomeResolvedTechnicianNamedInClause() throws Exception {
        SquareBookingMirror withTech = SquareBookingMirror.builder().id(1L).businessId(BUSINESS_ID)
                .squareBookingId(BOOKING_ID).squareCustomerId(CUSTOMER_ID).status("ACCEPTED")
                .startAt(NOW.plus(20, ChronoUnit.HOURS)).createdAt(NOW.minus(15, ChronoUnit.MINUTES))
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("tm-9", "sv-1", 60)))
                .build();
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(withTech));
        when(providerRepository.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                Provider.builder().id(9L).displayName("Susan Alieva").squareTeamMemberIds(java.util.Set.of("tm-9")).build()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq("pre_visit_nurture_welcome"), varsCaptor.capture());
        assertThat(varsCaptor.getValue().get("TECHNICIAN_CLAUSE")).isEqualTo(" with Susan");
        assertThat(varsCaptor.getValue().get("FNAME")).isEqualTo("Jane");
    }

    // --- Reminder email ---

    private static PreVisitNurtureSend welcomedRow() {
        return PreVisitNurtureSend.builder().id(1L).businessId(BUSINESS_ID).squareBookingId(BOOKING_ID)
                .squareCustomerId(CUSTOMER_ID).appointmentStartAt(NOW.plus(20, ChronoUnit.HOURS))
                .welcomeState(PreVisitNurtureSend.STATE_SENT).build();
    }

    @Test
    @DisplayName("welcomed row, still-accepted booking, customer has an email → sends, saves SENT reminder state")
    void reminderSentAndSaved() throws Exception {
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(welcomedRow()));
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(booking()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-2");

        scheduler.sendDueReminderEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getReminderState()).isEqualTo(PreVisitNurtureSend.STATE_SENT);
        verify(templateService).render(eq(BUSINESS_ID), eq("pre_visit_nurture_reminder"), any());
    }

    @Test
    @DisplayName("booking no longer ACCEPTED (cancelled) by reminder time → SKIPPED_CANCELLED, no email sent")
    void reminderCancelledBookingSkipped() {
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(welcomedRow()));
        SquareBookingMirror cancelled = SquareBookingMirror.builder().id(1L).businessId(BUSINESS_ID)
                .squareBookingId(BOOKING_ID).squareCustomerId(CUSTOMER_ID).status("CANCELLED_BY_CUSTOMER")
                .startAt(NOW.plus(20, ChronoUnit.HOURS)).build();
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(cancelled));

        scheduler.sendDueReminderEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getReminderState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_CANCELLED);
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("booking mirror row no longer exists by reminder time → SKIPPED_CANCELLED")
    void reminderMissingBookingSkipped() {
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(welcomedRow()));
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.empty());

        scheduler.sendDueReminderEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getReminderState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_CANCELLED);
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("automation disabled by reminder time → SKIPPED_DISABLED, no email sent")
    void reminderDisabledSkipped() {
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(welcomedRow()));
        when(automationService.isEnabled(BUSINESS_ID, AUTOMATION_KEY)).thenReturn(false);

        scheduler.sendDueReminderEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getReminderState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_DISABLED);
        verifyNoInteractions(mailchimpEmailService);
        verify(bookingMirrorRepository, never()).findByBusinessIdAndSquareBookingId(any(), any());
    }

    @Test
    @DisplayName("customer has no email on file by reminder time → SKIPPED_NO_EMAIL")
    void reminderNoEmailSkipped() {
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(welcomedRow()));
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(booking()));
        when(square.customerEmail(CUSTOMER_ID)).thenReturn(null);

        scheduler.sendDueReminderEmails();

        ArgumentCaptor<PreVisitNurtureSend> captor = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getReminderState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_NO_EMAIL);
    }

    // --- PMU consultations ---

    private static SquareBookingMirror consultation(Instant createdAt, Instant startAt) {
        return SquareBookingMirror.builder().id(2L).businessId(BUSINESS_ID).squareBookingId(BOOKING_ID)
                .squareCustomerId(CUSTOMER_ID).status("ACCEPTED").startAt(startAt).createdAt(createdAt)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("tm-21", "sv-online", 30)))
                .build();
    }

    private void anastasiiaOnline() {
        when(square.catalogNames(java.util.Set.of("sv-online"))).thenReturn(Map.of("sv-online", "Online Consultation"));
        when(providerRepository.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                Provider.builder().id(21L).displayName("Anastasiia Makarenko").squareTeamMemberIds(java.util.Set.of("tm-21")).build()));
    }

    private static PreVisitNurtureSend consultationRow(Instant createdAt, Instant startAt) {
        return PreVisitNurtureSend.builder().id(2L).businessId(BUSINESS_ID).squareBookingId(BOOKING_ID)
                .squareCustomerId(CUSTOMER_ID).appointmentStartAt(startAt).createdAt(createdAt)
                .visitKind(PreVisitNurtureSend.KIND_CONSULTATION_ONLINE)
                .welcomeState(PreVisitNurtureSend.STATE_SENT).build();
    }

    @Test
    @DisplayName("online consultation → consultation template set, kind stored, phone-call wording and calendar link")
    void consultationWelcomeUsesConsultationTemplates() throws Exception {
        anastasiiaOnline();
        // 2026-10-09T21:00Z is Friday 2:00 PM Pacific.
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(consultation(NOW.minus(10, ChronoUnit.MINUTES), Instant.parse("2026-10-09T21:00:00Z"))));
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "jane <b>"));

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq("pre_visit_nurture_consultation_welcome"), vars.capture());
        assertThat(vars.getValue().get("ARTIST")).isEqualTo("Anastasiia");
        assertThat(vars.getValue().get("DAY")).isEqualTo("Friday, October 9");
        assertThat(vars.getValue().get("TIME")).isEqualTo("2:00 PM");
        assertThat(vars.getValue().get("FORMAT_DETAILS")).contains("Anastasiia will call you").contains("no need to come");
        assertThat(vars.getValue().get("FNAME")).isEqualTo("Jane &lt;b&gt;");
        assertThat(vars.getValue().get("CALENDAR_URL"))
                .startsWith("https://calendar.google.com/calendar/render?action=TEMPLATE")
                .contains("dates=20261009T210000Z/20261009T213000Z");
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(mailchimpEmailService).sendWinbackEmail(any(), eq("jane@example.com"), subject.capture(), any(), any(), any());
        assertThat(subject.getValue()).isEqualTo("Your consultation with Anastasiia: what to expect");
        ArgumentCaptor<PreVisitNurtureSend> saved = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(saved.capture());
        assertThat(saved.getValue().getVisitKind()).isEqualTo(PreVisitNurtureSend.KIND_CONSULTATION_ONLINE);
        assertThat(saved.getValue().getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SENT);
    }

    @Test
    @DisplayName("a booking with no template for its kind is skipped before any customer lookup")
    void welcomeWithoutTemplateSkipsBeforeSquareCustomerLookups() {
        when(bookingMirrorRepository.findByBusinessIdAndStatusAndCreatedAtBetween(eq(BUSINESS_ID), eq("ACCEPTED"), any(), any()))
                .thenReturn(List.of(booking()));
        when(templateService.has(BUSINESS_ID, "pre_visit_nurture_welcome")).thenReturn(false);

        scheduler.sendDueWelcomeEmails();

        ArgumentCaptor<PreVisitNurtureSend> saved = ArgumentCaptor.forClass(PreVisitNurtureSend.class);
        verify(sendRepository).save(saved.capture());
        assertThat(saved.getValue().getWelcomeState()).isEqualTo(PreVisitNurtureSend.STATE_SKIPPED_NO_TEMPLATE);
        verify(square, never()).customerEmail(any());
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("meet-your-artist goes out for a 5-day wait, never for a 3-day one")
    void meetArtistOnlyForLongWaits() throws Exception {
        anastasiiaOnline();
        PreVisitNurtureSend longWait = consultationRow(NOW.minus(2, ChronoUnit.DAYS), NOW.plus(3, ChronoUnit.DAYS));
        when(sendRepository.findByBusinessIdAndWelcomeStateAndMeetArtistStateIsNullAndCreatedAtBeforeAndAppointmentStartAtAfter(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(longWait));
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(consultation(NOW.minus(2, ChronoUnit.DAYS), NOW.plus(3, ChronoUnit.DAYS))));

        scheduler.sendDueMeetArtistEmails();

        verify(templateService).render(eq(BUSINESS_ID), eq("pre_visit_nurture_consultation_meet_artist"), any());
        assertThat(longWait.getMeetArtistState()).isEqualTo(PreVisitNurtureSend.STATE_SENT);

        PreVisitNurtureSend shortWait = consultationRow(NOW.minus(2, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS)
                .plus(20, ChronoUnit.HOURS));
        when(sendRepository.findByBusinessIdAndWelcomeStateAndMeetArtistStateIsNullAndCreatedAtBeforeAndAppointmentStartAtAfter(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(shortWait));
        scheduler.sendDueMeetArtistEmails();
        assertThat(shortWait.getMeetArtistState()).isNull();
        verify(mailchimpEmailService, org.mockito.Mockito.times(1)).sendWinbackEmail(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("getting-ready goes out only when the consultation was booked 8+ days out")
    void prepOnlyForVeryLongWaits() throws Exception {
        anastasiiaOnline();
        PreVisitNurtureSend sixDays = consultationRow(NOW.minus(3, ChronoUnit.DAYS), NOW.plus(3, ChronoUnit.DAYS));
        PreVisitNurtureSend tenDays = consultationRow(NOW.minus(7, ChronoUnit.DAYS), NOW.plus(3, ChronoUnit.DAYS));
        when(sendRepository.findByBusinessIdAndWelcomeStateAndPrepStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(sixDays, tenDays));
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(consultation(NOW.minus(7, ChronoUnit.DAYS), NOW.plus(3, ChronoUnit.DAYS))));

        scheduler.sendDuePrepEmails();

        assertThat(sixDays.getPrepState()).isNull();
        assertThat(tenDays.getPrepState()).isEqualTo(PreVisitNurtureSend.STATE_SENT);
        verify(templateService).render(eq(BUSINESS_ID), eq("pre_visit_nurture_consultation_prep"), any());
    }

    @Test
    @DisplayName("consultation reminder says Tomorrow and the time in the subject")
    void consultationReminderSubject() throws Exception {
        anastasiiaOnline();
        Instant start = Instant.parse("2026-10-07T17:30:00Z"); // Wednesday 10:30 AM Pacific
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any(), any()))
                .thenReturn(List.of(consultationRow(NOW.minus(5, ChronoUnit.DAYS), start)));
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(consultation(NOW.minus(5, ChronoUnit.DAYS), start)));

        scheduler.sendDueReminderEmails();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> preview = ArgumentCaptor.forClass(String.class);
        verify(mailchimpEmailService).sendWinbackEmail(any(), any(), subject.capture(), preview.capture(), any(), any());
        assertThat(subject.getValue()).isEqualTo("Tomorrow at 10:30 AM: your consultation with Anastasiia");
        assertThat(preview.getValue()).isEqualTo("Anastasiia will call you at the number you booked with");
    }

    @Test
    @DisplayName("later steps wait for Pacific daytime: nothing is even looked up at 3 AM")
    void laterStepsQuietAtNight() {
        PreVisitNurtureScheduler night = schedulerAt(Instant.parse("2026-10-06T10:00:00Z")); // 3:00 AM Pacific

        night.sendDueReminderEmails();
        night.sendDueMeetArtistEmails();
        night.sendDuePrepEmails();

        verifyNoInteractions(sendRepository, mailchimpEmailService);
    }

    @Test
    @DisplayName("a rescheduled visit moves the stored start time, so later steps follow the new date")
    void rescheduledVisitSynced() {
        PreVisitNurtureSend row = consultationRow(NOW.minus(1, ChronoUnit.DAYS), NOW.plus(10, ChronoUnit.DAYS));
        when(sendRepository.findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtAfter(
                eq(BUSINESS_ID), eq(PreVisitNurtureSend.STATE_SENT), any()))
                .thenReturn(List.of(row));
        Instant moved = NOW.plus(2, ChronoUnit.DAYS);
        when(bookingMirrorRepository.findByBusinessIdAndSquareBookingId(BUSINESS_ID, BOOKING_ID))
                .thenReturn(Optional.of(consultation(NOW.minus(1, ChronoUnit.DAYS), moved)));

        scheduler.sendDueReminderEmails();

        assertThat(row.getAppointmentStartAt()).isEqualTo(moved);
        verify(sendRepository).save(row);
    }
}
