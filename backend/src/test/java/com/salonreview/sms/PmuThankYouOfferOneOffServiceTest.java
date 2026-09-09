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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PmuThankYouOfferOneOffServiceTest {

    private static final Long BUSINESS_ID = 2L;

    private SquareBookingMirrorRepository bookingMirrorRepository;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpClient mailchimpClient;
    private WinbackEmailSendRepository sendRepository;
    private MailchimpBatchCampaignService batchCampaignService;
    private PmuThankYouOfferOneOffService service;
    private MailchimpConfig config;

    @BeforeEach
    void setUp() {
        bookingMirrorRepository = mock(SquareBookingMirrorRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpClient = mock(MailchimpClient.class);
        sendRepository = mock(WinbackEmailSendRepository.class);
        batchCampaignService = mock(MailchimpBatchCampaignService.class);
        // Fixed at a date on/before the offer's own hardcoded 2026-09-08 deadline — otherwise
        // send() reads the real system clock, which has since moved past it, and every "not
        // expired yet" test here starts returning SKIPPED_EXPIRED instead of actually running.
        Clock beforeDeadline = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneId.of("America/Los_Angeles"));
        service = new PmuThankYouOfferOneOffService(bookingMirrorRepository, squareClientProvider,
                mailchimpConfigRepository, mailchimpClient, sendRepository, batchCampaignService, beforeDeadline);

        config = MailchimpConfig.builder().businessId(BUSINESS_ID).apiKey("k-us1").audienceId("a1")
                .fromName("Anna Kara").fromEmail("anna@pmu-annakara.com").replyToEmail("anna@pmu-annakara.com").build();
        when(mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(config));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any())).thenReturn(List.of());
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                any(), any(), any(), any())).thenReturn(false);
    }

    private static SquareClient.Customer customer(String id, String email) {
        return new SquareClient.Customer(id, "Jane", "Doe", "2024-01-01T00:00:00Z", null, email, null);
    }

    private static SquareBookingMirror upcomingBooking(String customerId, String status) {
        return SquareBookingMirror.builder().businessId(BUSINESS_ID).squareBookingId("bk-" + customerId)
                .squareCustomerId(customerId).status(status).startAt(Instant.now().plus(Duration.ofDays(5))).build();
    }

    @Test
    @DisplayName("preview: counts total, with-email, excluded-already-booked, excluded-undeliverable, excluded-already-sent, final")
    void previewComputesCounts() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(
                customer("cust1", "jane@example.com"),
                customer("cust2", null),
                customer("cust3", "bob@example.com"),
                customer("cust4", "gone@example.com"),
                customer("cust5", "already@example.com")));
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any()))
                .thenReturn(List.of(upcomingBooking("cust3", "ACCEPTED")));
        when(mailchimpClient.fetchUndeliverableEmails(config)).thenReturn(Set.of("gone@example.com"));
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                eq(BUSINESS_ID), eq("pmu_thank_you_offer"), eq("cust5"), eq(WinbackEmailSend.STATE_SENT)))
                .thenReturn(true);

        PmuThankYouOfferOneOffService.PreviewResult result = service.preview();

        assertThat(result.totalCustomers()).isEqualTo(5);
        assertThat(result.withEmail()).isEqualTo(4); // cust2 has no email
        assertThat(result.excludedAlreadyBooked()).isEqualTo(1); // cust3
        assertThat(result.excludedUndeliverable()).isEqualTo(1); // cust4
        assertThat(result.excludedAlreadySent()).isEqualTo(1); // cust5
        assertThat(result.finalRecipientCount()).isEqualTo(1); // cust1 only
    }

    @Test
    @DisplayName("send: builds recipient list excluding already-booked, undeliverable and already-sent, calls the batch service once")
    void sendBuildsCorrectRecipientListAndDelegatesToBatchService() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(
                customer("cust1", "jane@example.com"),
                customer("cust2", "bob@example.com")));
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any()))
                .thenReturn(List.of(upcomingBooking("cust2", "ACCEPTED")));
        when(mailchimpClient.fetchUndeliverableEmails(config)).thenReturn(Set.of());
        when(batchCampaignService.send(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MailchimpBatchCampaignService.BatchSendResult("SENT", null, 42L, "campaign-1", 1));

        MailchimpBatchCampaignService.BatchSendResult result = service.send();

        assertThat(result.state()).isEqualTo("SENT");
        ArgumentCaptor<List<MailchimpBatchCampaignService.Recipient>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(batchCampaignService).send(eq(BUSINESS_ID), eq("pmu_thank_you_offer"), eq(config),
                any(), any(), any(), any(), recipientsCaptor.capture());
        assertThat(recipientsCaptor.getValue()).extracting(MailchimpBatchCampaignService.Recipient::squareCustomerId)
                .containsExactly("cust1");
    }

    @Test
    @DisplayName("send: not configured -> throws, no Square/batch calls")
    void sendThrowsWhenNotConfigured() {
        when(mailchimpConfigRepository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(squareClientProvider, batchCampaignService);
    }

    @Test
    @DisplayName("send: past the offer's own 2026-09-08 deadline -> SKIPPED_EXPIRED, no Square/batch calls")
    void sendSkipsPastDeadline() throws Exception {
        Clock afterDeadline = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneId.of("America/Los_Angeles"));
        PmuThankYouOfferOneOffService expiredService = new PmuThankYouOfferOneOffService(bookingMirrorRepository,
                squareClientProvider, mailchimpConfigRepository, mailchimpClient, sendRepository, batchCampaignService,
                afterDeadline);

        MailchimpBatchCampaignService.BatchSendResult result = expiredService.send();

        assertThat(result.state()).isEqualTo("SKIPPED_EXPIRED");
        verifyNoInteractions(squareClientProvider, batchCampaignService);
    }

    @Test
    @DisplayName("send: excludes a customer already sent this automation, never re-sent")
    void sendExcludesAlreadySent() throws Exception {
        when(square.listAllCustomers()).thenReturn(List.of(customer("cust1", "jane@example.com")));
        when(mailchimpClient.fetchUndeliverableEmails(config)).thenReturn(Set.of());
        when(sendRepository.existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
                eq(BUSINESS_ID), eq("pmu_thank_you_offer"), eq("cust1"), eq(WinbackEmailSend.STATE_SENT)))
                .thenReturn(true);
        when(batchCampaignService.send(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MailchimpBatchCampaignService.BatchSendResult("SKIPPED_NO_RECIPIENTS", null, null, null, 0));

        service.send();

        ArgumentCaptor<List<MailchimpBatchCampaignService.Recipient>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(batchCampaignService).send(any(), any(), any(), any(), any(), any(), any(), recipientsCaptor.capture());
        assertThat(recipientsCaptor.getValue()).isEmpty();
    }
}
