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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ColorBoosterWinbackOneOffServiceTest {

    private static final Long BUSINESS_ID = 2L;
    private static final String CUSTOMER_ID = "cust1";
    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final String INITIAL_VARIATION = "INITIAL1";
    private static final String BOOSTER_VARIATION = "BOOSTER1";

    private ServiceLifecycleRoleRepository roleRepository;
    private WinbackEmailSendRepository sendRepository;
    private ProviderVisitRepository visitRepository;
    private ProviderRepository providerRepository;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpEmailService mailchimpEmailService;
    private MailchimpEmailTemplateService templateService;
    private ColorBoosterWinbackOneOffService service;

    @BeforeEach
    void setUp() {
        roleRepository = mock(ServiceLifecycleRoleRepository.class);
        sendRepository = mock(WinbackEmailSendRepository.class);
        visitRepository = mock(ProviderVisitRepository.class);
        providerRepository = mock(ProviderRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpEmailService = mock(MailchimpEmailService.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        service = new ColorBoosterWinbackOneOffService(roleRepository, sendRepository, visitRepository,
                providerRepository, squareClientProvider, mailchimpConfigRepository, mailchimpEmailService, templateService);

        when(roleRepository.findAllByBusinessIdAndRole(BUSINESS_ID, "INITIAL_PROCEDURE"))
                .thenReturn(List.of(role("INITIAL_PROCEDURE", INITIAL_VARIATION)));
        when(roleRepository.findAllByBusinessIdAndRole(BUSINESS_ID, "COLOR_BOOSTER"))
                .thenReturn(List.of(role("COLOR_BOOSTER", BOOSTER_VARIATION)));
        when(mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(configuredMailchimp()));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(providerRepository.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of());
        when(square.customerEmail(CUSTOMER_ID)).thenReturn("jane@example.com");
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "jane"));
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.of("<html></html>"));
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                any(), anyString(), anyString(), anyString())).thenReturn(false);
    }

    private static ServiceLifecycleRole role(String role, String variationId) {
        return ServiceLifecycleRole.builder().businessId(BUSINESS_ID).role(role).squareVariationId(variationId).build();
    }

    private static MailchimpConfig configuredMailchimp() {
        return MailchimpConfig.builder().businessId(BUSINESS_ID).apiKey("k-us1").audienceId("a1")
                .fromName("Anna").fromEmail("anna@pmu-annakara.com").replyToEmail("anna@pmu-annakara.com").build();
    }

    private SquareClient.Booking qualifyingBooking(LocalDate visitDate, String teamMemberId) {
        String startAt = visitDate.atStartOfDay(ZONE).toInstant().toString();
        return new SquareClient.Booking("bk1", "ACCEPTED", startAt, startAt, startAt, "loc1", CUSTOMER_ID, null, null,
                List.of(new SquareClient.AppointmentSegment(teamMemberId, INITIAL_VARIATION, 60)));
    }

    private void stubCandidateScanAndHistory(LocalDate visitDate, String teamMemberId) {
        SquareClient.Booking booking = qualifyingBooking(visitDate, teamMemberId);
        when(square.bookings(any(), any())).thenReturn(List.of(booking));
        when(visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(BUSINESS_ID, CUSTOMER_ID, visitDate)).thenReturn(true);
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenReturn(List.of(booking));
    }

    @Test
    @DisplayName("real dry run: overdue, verified visit, email on file -> WOULD_SEND, nothing actually sent")
    void dryRunDoesNotSend() {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, true);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("WOULD_SEND");
        verifyNoInteractions(mailchimpEmailService);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("real run: sends and saves a SENT WinbackEmailSend row with campaign id + content, no sms_message_id")
    void realRunSendsAndSaves() throws Exception {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SENT");
        assertThat(results.get(0).email()).isEqualTo("jane@example.com");

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository).save(captor.capture());
        WinbackEmailSend saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
        assertThat(saved.getEmailAddress()).isEqualTo("jane@example.com");
        assertThat(saved.getAutomationKey()).isEqualTo("color_booster_winback_oneoff");
        assertThat(saved.getMailchimpCampaignId()).isEqualTo("campaign-1");
        assertThat(saved.getContentHtml()).isEqualTo("<html></html>");
        assertThat(saved.getSmsMessageId()).isNull();
    }

    @Test
    @DisplayName("retry over an existing SEND_FAILED row updates it in place (upsert), never a second insert")
    void retryUpdatesExistingRowInPlace() throws Exception {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);
        WinbackEmailSend existing = WinbackEmailSend.builder()
                .id(42L).businessId(BUSINESS_ID).automationKey("color_booster_winback_oneoff")
                .squareCustomerId(CUSTOMER_ID).emailAddress("jane@example.com").state(WinbackEmailSend.STATE_SEND_FAILED).build();
        when(sendRepository.findByBusinessIdAndAutomationKeyAndSquareCustomerId(
                eq(BUSINESS_ID), eq("color_booster_winback_oneoff"), eq(CUSTOMER_ID)))
                .thenReturn(Optional.of(existing));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SENT");

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
    }

    @Test
    @DisplayName("already sent by this campaign before -> skipped entirely, never re-sent")
    void alreadySentSkipped() {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                eq(BUSINESS_ID), eq("color_booster_winback_oneoff"), eq(CUSTOMER_ID), eq(WinbackEmailSend.STATE_SENT)))
                .thenReturn(true);

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).isEmpty();
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("customer already has an upcoming color booster booked -> SKIPPED_ALREADY_BOOKED")
    void alreadyBookedSkipped() {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        SquareClient.Booking pastBooking = qualifyingBooking(visitDate, null);
        LocalDate future = LocalDate.now(ZONE).plusDays(10);
        SquareClient.Booking upcomingBooster = new SquareClient.Booking("bk2", "ACCEPTED",
                future.atStartOfDay(ZONE).toInstant().toString(), null, null, "loc1", CUSTOMER_ID, null, null,
                List.of(new SquareClient.AppointmentSegment(null, BOOSTER_VARIATION, 60)));

        when(square.bookings(any(), any())).thenReturn(List.of(pastBooking));
        when(visitRepository.existsByBusinessIdAndCustomerIdAndServiceDate(BUSINESS_ID, CUSTOMER_ID, visitDate)).thenReturn(true);
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenReturn(List.of(pastBooking, upcomingBooster));

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_ALREADY_BOOKED");
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("no email on file -> SKIPPED_NO_EMAIL")
    void noEmailSkipped() {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);
        when(square.customerEmail(CUSTOMER_ID)).thenReturn(null);

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_NO_EMAIL");
    }

    @Test
    @DisplayName("no template registered for this business -> SKIPPED_NO_TEMPLATE")
    void noTemplateSkipped() {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.empty());

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_NO_TEMPLATE");
    }

    @Test
    @DisplayName("a resolvable technician on the qualifying booking's first segment names them in PROVIDER_CLAUSE")
    void resolvedTechnicianNamedInClause() throws Exception {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, "tm-9");
        when(providerRepository.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                Provider.builder().id(9L).displayName("Anastasiia Makarenko").squareTeamMemberIds(Set.of("tm-9")).build()));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        service.run(BUSINESS_ID, false);

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq("color_booster_winback_oneoff"), varsCaptor.capture());
        assertThat(varsCaptor.getValue().get("PROVIDER_CLAUSE")).isEqualTo(" with Anastasiia");
        assertThat(varsCaptor.getValue().get("LINK")).isEqualTo("https://book.pmu-annakara.com/?book=color-booster");
    }

    @Test
    @DisplayName("no resolvable technician -> blank PROVIDER_CLAUSE, no fabricated name")
    void unresolvedTechnicianBlankClause() throws Exception {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, "tm-unknown");
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        service.run(BUSINESS_ID, false);

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq("color_booster_winback_oneoff"), varsCaptor.capture());
        assertThat(varsCaptor.getValue().get("PROVIDER_CLAUSE")).isEmpty();
    }

    @Test
    @DisplayName("formatTimeSince: whole months only, whole years only, and years+months mix")
    void formatTimeSinceVariants() {
        LocalDate to = LocalDate.of(2026, 9, 5);
        assertThat(ColorBoosterWinbackOneOffService.formatTimeSince(LocalDate.of(2026, 2, 5), to)).isEqualTo("7 months");
        assertThat(ColorBoosterWinbackOneOffService.formatTimeSince(LocalDate.of(2025, 9, 5), to)).isEqualTo("1 year");
        assertThat(ColorBoosterWinbackOneOffService.formatTimeSince(LocalDate.of(2025, 2, 5), to)).isEqualTo("1 year and 7 months");
        assertThat(ColorBoosterWinbackOneOffService.formatTimeSince(LocalDate.of(2024, 9, 5), to)).isEqualTo("2 years");
        assertThat(ColorBoosterWinbackOneOffService.formatTimeSince(LocalDate.of(2026, 8, 5), to)).isEqualTo("1 month");
    }

    @Test
    @DisplayName("Mailchimp send throws -> SEND_FAILED recorded, no campaign id/content stored")
    void sendFailureRecordsSendFailed() throws Exception {
        LocalDate visitDate = LocalDate.now(ZONE).minusDays(600);
        stubCandidateScanAndHistory(visitDate, null);
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Mailchimp API error"));

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SEND_FAILED");

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SEND_FAILED);
        assertThat(captor.getValue().getMailchimpCampaignId()).isNull();
        assertThat(captor.getValue().getContentHtml()).isNull();
    }

    @Test
    @DisplayName("Mailchimp not configured for this business -> single SKIPPED_NOT_CONFIGURED result, no Square calls")
    void notConfiguredSkipped() {
        when(mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());

        List<ColorBoosterWinbackOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_NOT_CONFIGURED");
        verifyNoInteractions(square);
    }
}
