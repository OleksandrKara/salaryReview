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

import java.time.Instant;
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
    private PreVisitNurtureScheduler scheduler;

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
        scheduler = new PreVisitNurtureScheduler(bookingMirrorRepository, sendRepository, mailchimpConfigRepository,
                mailchimpEmailService, templateService, squareClientProvider, automationService, providerRepository);

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
    }

    private static SquareBookingMirror booking() {
        return SquareBookingMirror.builder().id(1L).businessId(BUSINESS_ID).squareBookingId(BOOKING_ID)
                .squareCustomerId(CUSTOMER_ID).status("ACCEPTED").startAt(Instant.now().plus(20, ChronoUnit.HOURS))
                .createdAt(Instant.now().minus(15, ChronoUnit.MINUTES)).build();
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
                .startAt(Instant.now().plus(20, ChronoUnit.HOURS)).createdAt(Instant.now().minus(15, ChronoUnit.MINUTES))
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
                .squareCustomerId(CUSTOMER_ID).appointmentStartAt(Instant.now().plus(20, ChronoUnit.HOURS))
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
                .startAt(Instant.now().plus(20, ChronoUnit.HOURS)).build();
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
}
