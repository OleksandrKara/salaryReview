package com.salonreview.sms;

import com.salonreview.config.ColorBoosterReminderProperties;
import com.salonreview.domain.ServiceLifecycleReminderSend;
import com.salonreview.domain.ServiceLifecycleRole;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.ServiceLifecycleReminderSendRepository;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Annual color-booster reminder scheduler — data-driven off {@link ServiceLifecycleRole}, a
 * recurring (cooldown-based, not one-shot) reminder unlike {@link TouchupReminderScheduler}. */
class ColorBoosterReminderSchedulerTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String PHONE = "+15551234567";
    private static final String INITIAL_ID = "VAR-INITIAL";
    private static final String BOOSTER_ID = "VAR-BOOSTER";
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private ServiceLifecycleRoleRepository roleRepository;
    private ServiceLifecycleReminderSendRepository sendRepository;
    private ProviderVisitRepository visitRepository;
    private SquareClient square;
    private SmsAutomationService automationService;
    private SmsMessageLogService messageLogService;
    private TwilioSmsService smsService;
    private ColorBoosterReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        roleRepository = mock(ServiceLifecycleRoleRepository.class);
        sendRepository = mock(ServiceLifecycleReminderSendRepository.class);
        visitRepository = mock(ProviderVisitRepository.class);
        square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        TwilioSmsConfigRepository twilioConfigs = mock(TwilioSmsConfigRepository.class);
        when(twilioConfigs.findAll()).thenReturn(List.of(TwilioSmsConfig.builder().id(1L).businessId(BUSINESS_ID)
                .accountSid("AC1").apiKey("k").apiSecret("s").fromPhoneNumber("+15550001111").build()));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        automationService = mock(SmsAutomationService.class);
        when(automationService.isEnabled(BUSINESS_ID, "color_booster_reminder")).thenReturn(true);
        messageLogService = mock(SmsMessageLogService.class);
        smsService = mock(TwilioSmsService.class);
        ColorBoosterReminderProperties properties = new ColorBoosterReminderProperties();

        scheduler = new ColorBoosterReminderScheduler(roleRepository, sendRepository, visitRepository, squareClientProvider,
                twilioConfigs, automationService, messageLogService, smsService, properties);

        givenRoles(List.of(role("INITIAL_PROCEDURE", INITIAL_ID)), List.of(role("COLOR_BOOSTER", BOOSTER_ID)));
        when(smsService.sendTemplated(any(), any(), any(), any())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        when(square.customerPhone("cust1")).thenReturn(PHONE);
        when(square.customerGivenNames(List.of("cust1"))).thenReturn(Map.of("cust1", "Jane"));
        when(messageLogService.hasNegativeFeedback(eq(BUSINESS_ID), any())).thenReturn(false);
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("color_booster_reminder"), any(), any(), any())).thenReturn(false);
        // Default: a real settled visit exists for whatever date a test's booking claims — see
        // the dedicated "no real visit" test below. 2026-09-04: added after a real incident
        // (business 2's online-deposit bookings with no actual in-person checkout).
        when(visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(eq(BUSINESS_ID), any(), any())).thenReturn(true);
    }

    private void givenRoles(List<ServiceLifecycleRole> initial, List<ServiceLifecycleRole> booster) {
        when(roleRepository.findAllByBusinessIdAndRole(BUSINESS_ID, "INITIAL_PROCEDURE")).thenReturn(initial);
        when(roleRepository.findAllByBusinessIdAndRole(BUSINESS_ID, "COLOR_BOOSTER")).thenReturn(booster);
    }

    private static ServiceLifecycleRole role(String role, String variationId) {
        return ServiceLifecycleRole.builder().businessId(BUSINESS_ID).role(role).squareVariationId(variationId).build();
    }

    private static SquareClient.Booking booking(String id, int daysOffset, String variationId, String status) {
        String startAt = LocalDate.now(SALON_ZONE).plusDays(daysOffset).atStartOfDay(SALON_ZONE).toInstant().toString();
        return new SquareClient.Booking(id, status, startAt, null, null, null, "cust1", null, null,
                List.of(new SquareClient.AppointmentSegment("team1", variationId, 90)));
    }

    private static SquareClient.Booking qualifying(int daysAgo) {
        return booking("bk-" + daysAgo, -daysAgo, INITIAL_ID, "ACCEPTED");
    }

    @Test
    @DisplayName("no lifecycle roles configured for either side → no Square calls at all, inert")
    void noRolesConfiguredIsInert() {
        givenRoles(List.of(), List.of());

        scheduler.sendDueReminders();

        verifyNoInteractions(square, smsService, sendRepository);
    }

    @Test
    @DisplayName("only INITIAL_PROCEDURE configured, no COLOR_BOOSTER → still inert")
    void onlyOneRoleConfiguredIsInert() {
        givenRoles(List.of(role("INITIAL_PROCEDURE", INITIAL_ID)), List.of());

        scheduler.sendDueReminders();

        verifyNoInteractions(square, smsService, sendRepository);
    }

    @Test
    @DisplayName("automation disabled → business skipped entirely, no row, no Square calls")
    void automationDisabledSkipsWithoutRecording() {
        when(automationService.isEnabled(BUSINESS_ID, "color_booster_reminder")).thenReturn(false);
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));

        scheduler.sendDueReminders();

        verifyNoInteractions(square, smsService, sendRepository);
    }

    @Test
    @DisplayName("qualifying procedure ~400 days ago, nothing since → sends and writes SENT")
    void sendsWhenOverdue() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(qualifying(400)));

        scheduler.sendDueReminders();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(smsService).sendTemplated(eq(BUSINESS_ID), eq("color_booster_reminder_nudge"), eq(PHONE), varsCaptor.capture());
        assertThat(varsCaptor.getValue().get("greeting")).isEqualTo("Hi Jane!");
        assertThat(varsCaptor.getValue().get("timeSince")).contains("year"); // ~400 days ago is always over a year
        ArgumentCaptor<ServiceLifecycleReminderSend> captor = ArgumentCaptor.forClass(ServiceLifecycleReminderSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(ServiceLifecycleReminderSend.STATE_SENT);
        assertThat(captor.getValue().getAutomationKey()).isEqualTo("color_booster_reminder");
        assertThat(captor.getValue().getTriggerServiceDate()).isEqualTo(LocalDate.now(SALON_ZONE));
    }

    @Test
    @DisplayName("candidate scan finds an old qualifying event, but the customer's TRUE most recent one is recent → not actually due, no row")
    void notDueWhenTrueMostRecentEventIsRecent() {
        // The wide scan only looks back to (today - maxLookbackDays .. today - eligibilityDays), so
        // it can surface a customer via an old booking even though they have a newer one outside
        // that window (a color booster 100 days ago) — the per-candidate live check must catch this.
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(
                qualifying(400), booking("bk-recent", -100, BOOSTER_ID, "ACCEPTED")));

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("customer already has an upcoming color booster booked → skipped, writes SKIPPED_ALREADY_DONE")
    void skipsWhenBoosterAlreadyScheduled() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(
                qualifying(400), booking("bk-upcoming", 5, BOOSTER_ID, "ACCEPTED")));

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService);
        ArgumentCaptor<ServiceLifecycleReminderSend> captor = ArgumentCaptor.forClass(ServiceLifecycleReminderSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(ServiceLifecycleReminderSend.STATE_SKIPPED_ALREADY_DONE);
    }

    @Test
    @DisplayName("cancelled upcoming booster booking doesn't count → still sends")
    void cancelledUpcomingBoosterDoesNotCount() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(
                qualifying(400), booking("bk-cancelled", 5, BOOSTER_ID, "CANCELLED_BY_CUSTOMER")));

        scheduler.sendDueReminders();

        verify(smsService).sendTemplated(eq(BUSINESS_ID), eq("color_booster_reminder_nudge"), eq(PHONE), any());
    }

    @Test
    @DisplayName("no phone on file → skipped, writes SKIPPED_UNRESOLVED")
    void skipsWhenUnresolvedPhone() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(qualifying(400)));
        when(square.customerPhone("cust1")).thenReturn(null);

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService);
        ArgumentCaptor<ServiceLifecycleReminderSend> captor = ArgumentCaptor.forClass(ServiceLifecycleReminderSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(ServiceLifecycleReminderSend.STATE_SKIPPED_UNRESOLVED);
    }

    @Test
    @DisplayName("customer has left negative feedback before → skipped, no send")
    void skipsWhenNegativeFeedback() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(qualifying(400)));
        when(messageLogService.hasNegativeFeedback(BUSINESS_ID, PHONE)).thenReturn(true);

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService);
        ArgumentCaptor<ServiceLifecycleReminderSend> captor = ArgumentCaptor.forClass(ServiceLifecycleReminderSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(ServiceLifecycleReminderSend.STATE_SKIPPED_NEGATIVE_FEEDBACK);
    }

    @Test
    @DisplayName("already sent within the cooldown window → quiet skip, no Square lookup for this customer, no new row")
    void withinCooldownSkipsQuietly() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("color_booster_reminder"), eq("cust1"), eq(ServiceLifecycleReminderSend.STATE_SENT), any()))
                .thenReturn(true);

        scheduler.sendDueReminders();

        verify(square, never()).bookingsForCustomer(any(), any());
        verifyNoInteractions(smsService);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("cooldown lapsed (no recent SENT row) → re-sent, recurring behavior")
    void cooldownLapsedSendsAgain() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(qualifying(400)));
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
                eq(BUSINESS_ID), eq("color_booster_reminder"), eq("cust1"), eq(ServiceLifecycleReminderSend.STATE_SENT), any()))
                .thenReturn(false);

        scheduler.sendDueReminders();

        verify(smsService).sendTemplated(eq(BUSINESS_ID), eq("color_booster_reminder_nudge"), eq(PHONE), any());
    }

    @Test
    @DisplayName("Square failure fetching customer history → no row written, retried next run")
    void squareFailureRetriesNextRun() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenThrow(new RuntimeException("Square down"));

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("2026-09-04 regression: qualifying booking exists but no real settled visit on "
            + "file (e.g. an online-deposit booking never actually attended) → not even a candidate")
    void bookingWithoutARealVisitIsNotACandidate() {
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(eq(BUSINESS_ID), eq("cust1"), any()))
                .thenReturn(false);

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService, sendRepository);
        verify(square, never()).bookingsForCustomer(any(), any());
    }

    @Test
    @DisplayName("2026-09-04 regression: candidate scan passes (real visit exists for the old "
            + "qualifying booking), but process()'s own true-most-recent-event re-check must also "
            + "reject a qualifying booking with no real visit, not just accept the first one found")
    void trueRecentEventWithoutARealVisitIsIgnored() {
        // cust1 has two qualifying bookings: one 400 days ago (real visit on file) and one 100
        // days ago (no real visit — e.g. a deposit-only booking) that would otherwise look like a
        // more recent qualifying event and push the customer's "true" trigger date forward,
        // wrongly making them look not-yet-due.
        SquareClient.Booking recentNoVisit = booking("bk-recent-noviz", -100, INITIAL_ID, "ACCEPTED");
        when(square.bookings(any(), any())).thenReturn(List.of(qualifying(400)));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(qualifying(400), recentNoVisit));
        LocalDate oldDate = LocalDate.now(SALON_ZONE).minusDays(400);
        LocalDate recentDate = LocalDate.now(SALON_ZONE).minusDays(100);
        when(visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(BUSINESS_ID, "cust1", oldDate)).thenReturn(true);
        when(visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(BUSINESS_ID, "cust1", recentDate)).thenReturn(false);

        scheduler.sendDueReminders();

        // The true most recent *real* qualifying event is still 400 days ago (the 100-day-old one
        // doesn't count), so the customer is correctly due — proves the fake-recent booking didn't
        // silently suppress a real reminder.
        verify(smsService).sendTemplated(eq(BUSINESS_ID), eq("color_booster_reminder_nudge"), eq(PHONE), any());
    }

    @Test
    @DisplayName("booking with no matching service variation → ignored, not even a candidate")
    void nonMatchingServiceIgnored() {
        SquareClient.Booking unrelated = booking("bk-unrelated", -400, "SOME-OTHER-SERVICE", "ACCEPTED");
        when(square.bookings(any(), any())).thenReturn(List.of(unrelated));

        scheduler.sendDueReminders();

        verifyNoInteractions(smsService, sendRepository);
        verify(square, never()).bookingsForCustomer(any(), any());
    }
}
