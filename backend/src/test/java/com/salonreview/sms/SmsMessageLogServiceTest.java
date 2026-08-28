package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Read/unread state for inbound messages — see openspec/changes/sms-automations-hub design.md D9.
 */
class SmsMessageLogServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private SmsMessageRepository repository;
    private SmsEventBroadcaster events;
    private SmsMessageLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageRepository.class);
        events = mock(SmsEventBroadcaster.class);
        service = new SmsMessageLogService(repository, events);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a newly-logged inbound message has read_at = null")
    void newInboundMessageIsUnread() {
        SmsMessage logged = service.logInbound(BUSINESS_ID, "+15551234567", "hello", null);

        assertThat(logged.getReadAt()).isNull();
        assertThat(logged.getDirection()).isEqualTo("INBOUND");
    }

    @Test
    @DisplayName("logInbound broadcasts the normalized phone number so the manager view can live-update")
    void logInboundBroadcastsChange() {
        service.logInbound(BUSINESS_ID, "(555) 123-4567", "hello", null);

        verify(events).broadcast("+15551234567");
    }

    @Test
    @DisplayName("unreadCount delegates to the repository's unread-inbound count")
    void unreadCountDelegates() {
        when(repository.countByBusinessIdAndDirectionAndReadAtIsNull(BUSINESS_ID, "INBOUND")).thenReturn(3L);

        assertThat(service.unreadCount(BUSINESS_ID)).isEqualTo(3L);
    }

    @Test
    @DisplayName("marking an unread message read sets read_at")
    void markReadSetsTimestamp() {
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("INBOUND").phoneNumber("+15551234567")
                .body("hi").status("RECEIVED").build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        service.markRead(BUSINESS_ID, 1L);

        assertThat(message.getReadAt()).isNotNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("marking an already-read message read again is a no-op — doesn't change the original timestamp, doesn't error")
    void markReadOnAlreadyReadIsNoOp() {
        Instant original = Instant.now().minusSeconds(60);
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("INBOUND").phoneNumber("+15551234567")
                .body("hi").status("RECEIVED").readAt(original).build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        service.markRead(BUSINESS_ID, 1L);

        assertThat(message.getReadAt()).isEqualTo(original);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("marking an unknown id read is a no-op, doesn't error")
    void markReadOnUnknownIdIsNoOp() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        service.markRead(BUSINESS_ID, 999L);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("marking a message id belonging to another business is a no-op, doesn't error")
    void markReadOnAnotherBusinessesMessageIsNoOp() {
        SmsMessage message = SmsMessage.builder().id(1L).businessId(2L).direction("INBOUND").phoneNumber("+15551234567")
                .body("hi").status("RECEIVED").build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        service.markRead(BUSINESS_ID, 1L);

        assertThat(message.getReadAt()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("markThreadRead normalizes the phone number, delegates to the bulk repository update, and broadcasts when it actually marked something")
    void markThreadReadNormalizesAndDelegates() {
        when(repository.markThreadRead(eq(BUSINESS_ID), eq("+15551234567"), any(Instant.class))).thenReturn(2);

        service.markThreadRead(BUSINESS_ID, "(555) 123-4567");

        verify(repository).markThreadRead(eq(BUSINESS_ID), eq("+15551234567"), any(Instant.class));
        verify(events).broadcast("+15551234567");
    }

    @Test
    @DisplayName("marking an already-fully-read thread read again doesn't broadcast — breaks the self-triggering SSE loop that used to jitter the open thread's scroll position")
    void markThreadReadOnAlreadyReadThreadDoesNotBroadcast() {
        when(repository.markThreadRead(eq(BUSINESS_ID), eq("+15551234567"), any(Instant.class))).thenReturn(0);

        service.markThreadRead(BUSINESS_ID, "(555) 123-4567");

        verify(events, never()).broadcast(any());
    }

    @Test
    @DisplayName("markThreadUnread normalizes the phone number and delegates to the repository")
    void markThreadUnreadNormalizesAndDelegates() {
        service.markThreadUnread(BUSINESS_ID, "(555) 123-4567");

        verify(repository).markLastInboundUnread(eq(BUSINESS_ID), eq("+15551234567"));
    }

    @Test
    @DisplayName("generateUniqueClickToken re-rolls on a collision and returns the first free candidate")
    void generateUniqueClickTokenRerollsOnCollision() {
        when(repository.existsByClickToken(any()))
                .thenReturn(true, true, false); // first two candidates taken, third is free

        String token = service.generateUniqueClickToken();

        assertThat(token).isNotNull().hasSize(5);
        verify(repository, times(3)).existsByClickToken(any());
    }

    @Test
    @DisplayName("generateUniqueClickToken gives up after repeated collisions rather than looping forever")
    void generateUniqueClickTokenGivesUpEventually() {
        when(repository.existsByClickToken(any())).thenReturn(true);

        assertThatThrownBy(() -> service.generateUniqueClickToken())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("searchConversations returns one hit per phone number, keeping only the most recent match")
    void searchConversationsDedupesByPhoneKeepingMostRecent() {
        SmsMessage newer = SmsMessage.builder().businessId(BUSINESS_ID).phoneNumber("+15551234567").direction("INBOUND")
                .body("running late for my appointment").createdAt(Instant.now()).build();
        SmsMessage older = SmsMessage.builder().businessId(BUSINESS_ID).phoneNumber("+15551234567").direction("OUTBOUND")
                .body("see you at your appointment tomorrow").createdAt(Instant.now().minusSeconds(3600)).build();
        SmsMessage otherPhone = SmsMessage.builder().businessId(BUSINESS_ID).phoneNumber("+15559876543").direction("INBOUND")
                .body("can I reschedule my appointment").createdAt(Instant.now().minusSeconds(60)).build();
        when(repository.searchByBodyContaining(eq(BUSINESS_ID), eq("appointment"), any(Pageable.class)))
                .thenReturn(List.of(newer, otherPhone, older)); // already ordered newest-first

        List<SmsMessageLogService.ConversationSearchHit> hits = service.searchConversations(BUSINESS_ID, "appointment");

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).phoneNumber()).isEqualTo("+15551234567");
        assertThat(hits.get(0).snippet()).isEqualTo("running late for my appointment");
        assertThat(hits.get(1).phoneNumber()).isEqualTo("+15559876543");
    }

    @Test
    @DisplayName("searchConversations returns an empty list for a blank query without hitting the repository")
    void searchConversationsWithBlankQueryReturnsEmpty() {
        assertThat(service.searchConversations(BUSINESS_ID, "   ")).isEmpty();
        verify(repository, never()).searchByBodyContaining(any(), any(), any());
        verify(repository, never()).findPhoneNumbersMatchingNameOrDigits(any(), any(), any());
    }

    @Test
    @DisplayName("searchConversations includes a name/phone match with no body-content hit — see design.md D8/D9 "
            + "and the freeze this replaced (found live 2026-08-27/28)")
    void searchConversationsIncludesNameOrPhoneOnlyMatch() {
        when(repository.searchByBodyContaining(eq(BUSINESS_ID), eq("maly"), any(Pageable.class))).thenReturn(List.of());
        when(repository.findPhoneNumbersMatchingNameOrDigits(BUSINESS_ID, "maly", "")).thenReturn(List.of("+17149843774"));

        List<SmsMessageLogService.ConversationSearchHit> hits = service.searchConversations(BUSINESS_ID, "maly");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).phoneNumber()).isEqualTo("+17149843774");
    }

    @Test
    @DisplayName("searchConversations doesn't duplicate a phone number that matches both by body content and by name/phone")
    void searchConversationsDedupesAcrossBodyAndNameMatches() {
        SmsMessage bodyMatch = SmsMessage.builder().businessId(BUSINESS_ID).phoneNumber("+17149843774").direction("INBOUND")
                .body("thanks Maly, see you soon").createdAt(Instant.now()).build();
        when(repository.searchByBodyContaining(eq(BUSINESS_ID), eq("maly"), any(Pageable.class))).thenReturn(List.of(bodyMatch));
        when(repository.findPhoneNumbersMatchingNameOrDigits(BUSINESS_ID, "maly", "")).thenReturn(List.of("+17149843774"));

        List<SmsMessageLogService.ConversationSearchHit> hits = service.searchConversations(BUSINESS_ID, "maly");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).isEqualTo("thanks Maly, see you soon"); // body match wins, keeps its real snippet
    }

    @Test
    @DisplayName("digits-only search passes the extracted digits through for the phone-number branch")
    void searchConversationsExtractsDigitsForPhoneMatch() {
        when(repository.searchByBodyContaining(eq(BUSINESS_ID), eq("(714) 984-3774"), any(Pageable.class))).thenReturn(List.of());
        when(repository.findPhoneNumbersMatchingNameOrDigits(BUSINESS_ID, "(714) 984-3774", "7149843774"))
                .thenReturn(List.of("+17149843774"));

        List<SmsMessageLogService.ConversationSearchHit> hits = service.searchConversations(BUSINESS_ID, "(714) 984-3774");

        assertThat(hits).extracting(SmsMessageLogService.ConversationSearchHit::phoneNumber).containsExactly("+17149843774");
    }

    @Test
    @DisplayName("updateDeliveryStatus applies status, a known error code's plain-language message, and a timestamp")
    void updateDeliveryStatusAppliesKnownErrorCode() {
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("hi").status("SENT").twilioMessageSid("SM123").build();
        when(repository.findByTwilioMessageSid("SM123")).thenReturn(Optional.of(message));

        service.updateDeliveryStatus("SM123", "undelivered", "30003");

        assertThat(message.getDeliveryStatus()).isEqualTo("undelivered");
        assertThat(message.getDeliveryErrorCode()).isEqualTo("30003");
        assertThat(message.getDeliveryErrorMessage()).isEqualTo("Phone unreachable (turned off or out of coverage)");
        assertThat(message.getDeliveryUpdatedAt()).isNotNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("updateDeliveryStatus falls back to a generic message for an unrecognized error code")
    void updateDeliveryStatusFallsBackForUnknownErrorCode() {
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("hi").status("SENT").twilioMessageSid("SM123").build();
        when(repository.findByTwilioMessageSid("SM123")).thenReturn(Optional.of(message));

        service.updateDeliveryStatus("SM123", "failed", "99999");

        assertThat(message.getDeliveryErrorMessage()).isEqualTo("Delivery error (code 99999)");
    }

    @Test
    @DisplayName("updateDeliveryStatus for delivered clears any error code/message")
    void updateDeliveryStatusDeliveredHasNoError() {
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("hi").status("SENT").twilioMessageSid("SM123").build();
        when(repository.findByTwilioMessageSid("SM123")).thenReturn(Optional.of(message));

        service.updateDeliveryStatus("SM123", "delivered", null);

        assertThat(message.getDeliveryStatus()).isEqualTo("delivered");
        assertThat(message.getDeliveryErrorCode()).isNull();
        assertThat(message.getDeliveryErrorMessage()).isNull();
    }

    @Test
    @DisplayName("updateDeliveryStatus for an unknown SID is a no-op, doesn't error")
    void updateDeliveryStatusUnknownSidIsNoOp() {
        when(repository.findByTwilioMessageSid("SM999")).thenReturn(Optional.empty());

        service.updateDeliveryStatus("SM999", "delivered", null);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("hasNegativeFeedback normalizes the phone number and delegates to the repository")
    void hasNegativeFeedbackNormalizesAndDelegates() {
        when(repository.existsByBusinessIdAndPhoneNumberAndNegativeFeedbackAtIsNotNull(BUSINESS_ID, "+15551234567")).thenReturn(true);

        assertThat(service.hasNegativeFeedback(BUSINESS_ID, "(555) 123-4567")).isTrue();
    }

    @Test
    @DisplayName("phoneNumbersWithClickedLinkTarget delegates to the batch repository query")
    void phoneNumbersWithClickedLinkTargetDelegates() {
        List<String> phones = List.of("+15551234567", "+15559876543");
        when(repository.findPhoneNumbersWithClickedLinkTarget(BUSINESS_ID, phones, "GOOGLE_REVIEW"))
                .thenReturn(List.of("+15551234567"));

        assertThat(service.phoneNumbersWithClickedLinkTarget(BUSINESS_ID, phones, "GOOGLE_REVIEW"))
                .containsExactly("+15551234567");
    }

    @Test
    @DisplayName("phoneNumbersFlaggedAsSpam delegates to the batch repository query with the spam/opt-out error codes")
    void phoneNumbersFlaggedAsSpamDelegates() {
        List<String> phones = List.of("+15551234567", "+15559876543");
        when(repository.findPhoneNumbersWithDeliveryErrorCode(eq(BUSINESS_ID), eq(phones), any()))
                .thenReturn(List.of("+15559876543"));

        assertThat(service.phoneNumbersFlaggedAsSpam(BUSINESS_ID, phones)).containsExactly("+15559876543");
        verify(repository).findPhoneNumbersWithDeliveryErrorCode(BUSINESS_ID, phones, java.util.Set.of("30007", "21610"));
    }
}
