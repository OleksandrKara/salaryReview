package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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

class LaborDayPromoOneOffServiceTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String AUTOMATION_KEY = "labor_day_design_promo_oneoff";

    private WinbackEmailSendRepository sendRepository;
    private SquareBookingMirrorRepository bookingMirrorRepository;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpEmailService mailchimpEmailService;
    private MailchimpEmailTemplateService templateService;
    private LaborDayPromoOneOffService service;

    @BeforeEach
    void setUp() {
        sendRepository = mock(WinbackEmailSendRepository.class);
        bookingMirrorRepository = mock(SquareBookingMirrorRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpEmailService = mock(MailchimpEmailService.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        service = new LaborDayPromoOneOffService(sendRepository, bookingMirrorRepository,
                squareClientProvider, mailchimpConfigRepository, mailchimpEmailService, templateService);

        when(mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(configuredMailchimp()));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any())).thenReturn(List.of());
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.of("<html></html>"));
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                any(), anyString(), anyString(), anyString())).thenReturn(false);
    }

    private static MailchimpConfig configuredMailchimp() {
        return MailchimpConfig.builder().businessId(BUSINESS_ID).apiKey("k-us1").audienceId("a1")
                .fromName("Lucy").fromEmail("lucy@akluxnails.com").replyToEmail("lucy@akluxnails.com").build();
    }

    private static SquareClient.Customer customer(String id, String givenName, String email) {
        return new SquareClient.Customer(id, givenName, "Doe", "2024-01-01T00:00:00Z", null, email, null);
    }

    private static SquareBookingMirror upcomingBooking(String customerId, String status) {
        return SquareBookingMirror.builder().businessId(BUSINESS_ID).squareBookingId("bk-" + customerId)
                .squareCustomerId(customerId).status(status).startAt(Instant.now().plus(java.time.Duration.ofDays(5))).build();
    }

    @Test
    @DisplayName("real dry run: customer with email, not booked -> WOULD_SEND, nothing actually sent")
    void dryRunDoesNotSend() {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, true);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("WOULD_SEND");
        verifyNoInteractions(mailchimpEmailService);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("real run: sends and saves a SENT WinbackEmailSend row with campaign id + content")
    void realRunSendsAndSaves() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SENT");
        assertThat(results.get(0).email()).isEqualTo("jane@example.com");

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository).save(captor.capture());
        WinbackEmailSend saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
        assertThat(saved.getAutomationKey()).isEqualTo(AUTOMATION_KEY);
        assertThat(saved.getMailchimpCampaignId()).isEqualTo("campaign-1");
        assertThat(saved.getSmsMessageId()).isNull();

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq("labor_day_design_promo"), varsCaptor.capture());
        assertThat(varsCaptor.getValue().get("FNAME")).isEqualTo("Jane");
    }

    @Test
    @DisplayName("customer already has an upcoming ACCEPTED booking -> SKIPPED_ALREADY_BOOKED, never sent")
    void alreadyBookedSkipped() {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any()))
                .thenReturn(List.of(upcomingBooking("cust1", "ACCEPTED")));

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_ALREADY_BOOKED");
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("a non-ACCEPTED upcoming booking does not exclude the customer")
    void nonAcceptedUpcomingBookingDoesNotExclude() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any()))
                .thenReturn(List.of(upcomingBooking("cust1", "CANCELLED_BY_CUSTOMER")));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("no email on file -> SKIPPED_NO_EMAIL")
    void noEmailSkipped() {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", null)));

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_NO_EMAIL");
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("already sent by this campaign before -> skipped entirely, never re-sent")
    void alreadySentSkipped() {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                eq(BUSINESS_ID), eq(AUTOMATION_KEY), eq("cust1"), eq(WinbackEmailSend.STATE_SENT)))
                .thenReturn(true);

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).isEmpty();
        verifyNoInteractions(mailchimpEmailService);
    }

    @Test
    @DisplayName("retry over an existing SEND_FAILED row updates it in place (upsert), never a second insert")
    void retryUpdatesExistingRowInPlace() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        WinbackEmailSend existing = WinbackEmailSend.builder()
                .id(42L).businessId(BUSINESS_ID).automationKey(AUTOMATION_KEY)
                .squareCustomerId("cust1").emailAddress("jane@example.com").state(WinbackEmailSend.STATE_SEND_FAILED).build();
        when(sendRepository.findByBusinessIdAndAutomationKeyAndSquareCustomerId(eq(BUSINESS_ID), eq(AUTOMATION_KEY), eq("cust1")))
                .thenReturn(Optional.of(existing));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any())).thenReturn("campaign-1");

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SENT");

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L);
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
    }

    @Test
    @DisplayName("Mailchimp send throws -> SEND_FAILED recorded, no campaign id/content stored")
    void sendFailureRecordsSendFailed() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        when(mailchimpEmailService.sendWinbackEmail(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Mailchimp API error"));

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SEND_FAILED");

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SEND_FAILED);
        assertThat(captor.getValue().getMailchimpCampaignId()).isNull();
    }

    @Test
    @DisplayName("no template registered for this business -> SKIPPED_NO_TEMPLATE")
    void noTemplateSkipped() {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane", "jane@example.com")));
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.empty());

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_NO_TEMPLATE");
    }

    @Test
    @DisplayName("Mailchimp not configured for this business -> single SKIPPED_NOT_CONFIGURED result, no Square calls")
    void notConfiguredSkipped() {
        when(mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());

        List<LaborDayPromoOneOffService.CandidateResult> results = service.run(BUSINESS_ID, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).state()).isEqualTo("SKIPPED_NOT_CONFIGURED");
        verifyNoInteractions(square);
    }
}
