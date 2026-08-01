package com.salonreview.sms;

import com.salonreview.domain.LeadFollowUpSend;
import com.salonreview.marketing.MarketingContactsRepository;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.repo.LeadFollowUpSendRepository;
import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Lead-follow-up poller — see openspec/changes/lead-followup-and-manager-inbox design.md D1/D2.
 */
class LeadFollowUpSchedulerTest {

    private static final String PHONE = "+15551234567";

    private MarketingContactsRepository contactsRepository;
    private LeadFollowUpSendRepository sendRepository;
    private SquareClient square;
    private SmsAutomationService automationService;
    private TwilioSmsService smsService;
    private LeadFollowUpScheduler scheduler;

    @BeforeEach
    void setUp() {
        contactsRepository = mock(MarketingContactsRepository.class);
        sendRepository = mock(LeadFollowUpSendRepository.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        smsService = mock(TwilioSmsService.class);
        scheduler = new LeadFollowUpScheduler(contactsRepository, sendRepository, square, automationService, smsService);

        when(automationService.isEnabled("lead_follow_up")).thenReturn(true);
        when(smsService.sendTemplated(any(), any(), any())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));
    }

    private static RawContact contact(UUID id, String name, String squareCustomerId) {
        return contact(id, name, squareCustomerId, Instant.now());
    }

    private static RawContact contact(UUID id, String name, String squareCustomerId, Instant updatedAt) {
        // id, phoneNumber, givenName, emailAddress, originalTrafficSource, marketingTrafficSource,
        // channel, utmSource, utmMedium, utmCampaign, landingPageSlug, variantName, deviceType,
        // osName, osVersion, browserName, browserVersion, smsMarketingConsent,
        // emailMarketingConsent, squareCustomerId, squareBookingId, bookingStatus,
        // bookingStartAt, bookingServiceName, bookingPrice, bookingArtistName, createdAt, updatedAt
        return new RawContact(id, PHONE, name, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, squareCustomerId, null, null, null, null, null, null,
                updatedAt, updatedAt);
    }

    private static SquareClient.Booking booking(String status, String startAt) {
        return new SquareClient.Booking("bk1", status, startAt, null, null, null, "cust1", null, null, null);
    }

    private void givenPending(RawContact... contacts) {
        when(contactsRepository.findPendingFollowUp(any(), any())).thenReturn(List.of(contacts));
    }

    @Test
    @DisplayName("no upcoming booking, automation enabled → sends and writes SENT")
    void sendsWhenUnbookedAndEnabled() {
        UUID id = UUID.randomUUID();
        RawContact c = contact(id, "Jane", "cust1");
        givenPending(c);
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of());

        scheduler.sendDueFollowUps();

        verify(smsService).sendTemplated("lead_follow_up_nudge", PHONE, Map.of("name", "Jane"));
        ArgumentCaptor<LeadFollowUpSend> captor = ArgumentCaptor.forClass(LeadFollowUpSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LeadFollowUpSend.STATE_SENT);
        assertThat(captor.getValue().getContactId()).isEqualTo(id);
    }

    @Test
    @DisplayName("has a real upcoming appointment (via tracked squareCustomerId) → skipped, no send")
    void skipsWhenUpcomingBookingViaTrackedCustomerId() {
        RawContact c = contact(UUID.randomUUID(), "Jane", "cust1");
        givenPending(c);
        String futureIso = Instant.now().plusSeconds(3600).toString();
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(booking("ACCEPTED", futureIso)));

        scheduler.sendDueFollowUps();

        verifyNoInteractions(smsService);
        ArgumentCaptor<LeadFollowUpSend> captor = ArgumentCaptor.forClass(LeadFollowUpSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LeadFollowUpSend.STATE_SKIPPED_BOOKED);
    }

    @Test
    @DisplayName("cancelled booking only → not treated as upcoming, still sends")
    void cancelledBookingDoesNotCountAsUpcoming() {
        RawContact c = contact(UUID.randomUUID(), "Jane", "cust1");
        givenPending(c);
        String futureIso = Instant.now().plusSeconds(3600).toString();
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(booking("CANCELLED_BY_CUSTOMER", futureIso)));

        scheduler.sendDueFollowUps();

        verify(smsService).sendTemplated(eq("lead_follow_up_nudge"), eq(PHONE), any());
    }

    @Test
    @DisplayName("no tracked squareCustomerId → falls back to live phone lookup")
    void fallsBackToPhoneLookupWhenNoTrackedCustomerId() {
        RawContact c = contact(UUID.randomUUID(), "Jane", null);
        givenPending(c);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of("cust-live"));
        when(square.bookingsForCustomer(eq("cust-live"), any())).thenReturn(List.of());

        scheduler.sendDueFollowUps();

        verify(square).customerIdsForPhone(PHONE);
        verify(smsService).sendTemplated(eq("lead_follow_up_nudge"), eq(PHONE), any());
    }

    @Test
    @DisplayName("phone lookup resolves no customer → treated as unbooked, sends")
    void noCustomerResolvedTreatedAsUnbooked() {
        RawContact c = contact(UUID.randomUUID(), "Jane", null);
        givenPending(c);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of());

        scheduler.sendDueFollowUps();

        verify(smsService).sendTemplated(eq("lead_follow_up_nudge"), eq(PHONE), any());
        verify(square, never()).bookingsForCustomer(any(), any());
    }

    @Test
    @DisplayName("no name on contact → variables map is empty, not name-shaped with null")
    void noNameSendsEmptyVariables() {
        RawContact c = contact(UUID.randomUUID(), null, "cust1");
        givenPending(c);
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of());

        scheduler.sendDueFollowUps();

        verify(smsService).sendTemplated("lead_follow_up_nudge", PHONE, Map.of());
    }

    @Test
    @DisplayName("automation disabled → skipped, no send attempt, writes SKIPPED_DISABLED")
    void disabledAutomationSkips() {
        when(automationService.isEnabled("lead_follow_up")).thenReturn(false);
        RawContact c = contact(UUID.randomUUID(), "Jane", "cust1");
        givenPending(c);
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of());

        scheduler.sendDueFollowUps();

        verifyNoInteractions(smsService);
        ArgumentCaptor<LeadFollowUpSend> captor = ArgumentCaptor.forClass(LeadFollowUpSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(LeadFollowUpSend.STATE_SKIPPED_DISABLED);
    }

    @Test
    @DisplayName("already-processed contact (belt-and-suspenders check) is skipped entirely")
    void alreadyProcessedContactSkipped() {
        UUID id = UUID.randomUUID();
        Instant touchedAt = Instant.now();
        RawContact c = contact(id, "Jane", "cust1", touchedAt);
        givenPending(c);
        when(sendRepository.existsByContactIdAndContactUpdatedAtGreaterThanEqual(id, touchedAt)).thenReturn(true);

        scheduler.sendDueFollowUps();

        verifyNoInteractions(smsService, square);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("contact resubmitted since the last processed touch (new updated_at) is eligible again")
    void resubmittedContactIsEligibleAgain() {
        UUID id = UUID.randomUUID();
        Instant newTouch = Instant.now();
        RawContact c = contact(id, "Jane", "cust1", newTouch);
        givenPending(c);
        // The belt-and-suspenders check only matches touches at-or-after what's on file — a
        // resubmission (newer updated_at) was never recorded, so this returns false, same as a
        // genuinely first-time contact.
        when(sendRepository.existsByContactIdAndContactUpdatedAtGreaterThanEqual(id, newTouch)).thenReturn(false);
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of());

        scheduler.sendDueFollowUps();

        verify(smsService).sendTemplated(eq("lead_follow_up_nudge"), eq(PHONE), any());
        ArgumentCaptor<LeadFollowUpSend> captor = ArgumentCaptor.forClass(LeadFollowUpSend.class);
        verify(sendRepository).save(captor.capture());
        assertThat(captor.getValue().getContactUpdatedAt()).isEqualTo(newTouch);
    }

    @Test
    @DisplayName("Square failure while checking upcoming bookings → no row written, retried next poll")
    void squareFailureRetriesNextPoll() {
        RawContact c = contact(UUID.randomUUID(), "Jane", "cust1");
        givenPending(c);
        when(square.bookingsForCustomer(eq("cust1"), any())).thenThrow(new RuntimeException("Square down"));

        scheduler.sendDueFollowUps();

        verifyNoInteractions(smsService);
        verify(sendRepository, never()).save(any());
    }

    @Test
    @DisplayName("no pending contacts → nothing happens")
    void noPendingContactsNoOp() {
        givenPending();

        scheduler.sendDueFollowUps();

        verifyNoInteractions(smsService, square);
        verify(sendRepository, never()).save(any());
    }
}
