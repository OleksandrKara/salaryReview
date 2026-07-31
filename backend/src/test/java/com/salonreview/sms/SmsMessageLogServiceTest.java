package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    private SmsMessageRepository repository;
    private SmsMessageLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageRepository.class);
        service = new SmsMessageLogService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a newly-logged inbound message has read_at = null")
    void newInboundMessageIsUnread() {
        SmsMessage logged = service.logInbound("+15551234567", "hello", null);

        assertThat(logged.getReadAt()).isNull();
        assertThat(logged.getDirection()).isEqualTo("INBOUND");
    }

    @Test
    @DisplayName("unreadCount delegates to the repository's unread-inbound count")
    void unreadCountDelegates() {
        when(repository.countByDirectionAndReadAtIsNull("INBOUND")).thenReturn(3L);

        assertThat(service.unreadCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("marking an unread message read sets read_at")
    void markReadSetsTimestamp() {
        SmsMessage message = SmsMessage.builder().id(1L).direction("INBOUND").phoneNumber("+15551234567")
                .body("hi").status("RECEIVED").build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        service.markRead(1L);

        assertThat(message.getReadAt()).isNotNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("marking an already-read message read again is a no-op — doesn't change the original timestamp, doesn't error")
    void markReadOnAlreadyReadIsNoOp() {
        Instant original = Instant.now().minusSeconds(60);
        SmsMessage message = SmsMessage.builder().id(1L).direction("INBOUND").phoneNumber("+15551234567")
                .body("hi").status("RECEIVED").readAt(original).build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        service.markRead(1L);

        assertThat(message.getReadAt()).isEqualTo(original);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("marking an unknown id read is a no-op, doesn't error")
    void markReadOnUnknownIdIsNoOp() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        service.markRead(999L);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("markThreadRead normalizes the phone number and delegates to the bulk repository update")
    void markThreadReadNormalizesAndDelegates() {
        service.markThreadRead("(555) 123-4567");

        verify(repository).markThreadRead(eq("+15551234567"), any(Instant.class));
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
}
