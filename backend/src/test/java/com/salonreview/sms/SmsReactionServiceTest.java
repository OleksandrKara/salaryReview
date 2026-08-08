package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsMessageReaction;
import com.salonreview.repo.SmsMessageReactionRepository;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmsReactionServiceTest {

    private static final String PHONE = "+15551234567";

    private SmsMessageReactionRepository repository;
    private SmsMessageRepository messageRepository;
    private SmsEventBroadcaster events;
    private SmsReactionService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageReactionRepository.class);
        messageRepository = mock(SmsMessageRepository.class);
        events = mock(SmsEventBroadcaster.class);
        service = new SmsReactionService(repository, messageRepository, events);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findBySmsMessageId(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("tryAttachCustomerReaction: 'Loved \"...\"' matching a recent outbound message attaches a heart reaction")
    void lovedTapbackMatchesRecentOutbound() {
        SmsMessage sent = SmsMessage.builder().id(42L).direction("OUTBOUND").body("Thanks so much for coming in!").build();
        when(messageRepository.findTop20ByPhoneNumberAndDirectionOrderByCreatedAtDesc(PHONE, "OUTBOUND"))
                .thenReturn(List.of(sent));

        boolean attached = service.tryAttachCustomerReaction(PHONE, "Loved “Thanks so much for coming in!”");

        assertThat(attached).isTrue();
        org.mockito.ArgumentCaptor<SmsMessageReaction> captor = org.mockito.ArgumentCaptor.forClass(SmsMessageReaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEmoji()).isEqualTo("❤️");
        assertThat(captor.getValue().getSmsMessageId()).isEqualTo(42L);
        verify(events).broadcast(PHONE);
    }

    @Test
    @DisplayName("tryAttachCustomerReaction: every tapback keyword maps to its emoji")
    void everyTapbackKeywordMaps() {
        SmsMessage sent = SmsMessage.builder().id(1L).direction("OUTBOUND").body("See you soon").build();
        when(messageRepository.findTop20ByPhoneNumberAndDirectionOrderByCreatedAtDesc(PHONE, "OUTBOUND"))
                .thenReturn(List.of(sent));

        assertThat(parseEmoji("Liked \"See you soon\"")).isEqualTo("👍");
        assertThat(parseEmoji("Disliked \"See you soon\"")).isEqualTo("👎");
        assertThat(parseEmoji("Laughed at \"See you soon\"")).isEqualTo("😂");
        assertThat(parseEmoji("Emphasized \"See you soon\"")).isEqualTo("‼️");
        assertThat(parseEmoji("Questioned \"See you soon\"")).isEqualTo("❓");
    }

    private String parseEmoji(String body) {
        reset(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findBySmsMessageId(any())).thenReturn(Optional.empty());
        service.tryAttachCustomerReaction(PHONE, body);
        org.mockito.ArgumentCaptor<SmsMessageReaction> captor = org.mockito.ArgumentCaptor.forClass(SmsMessageReaction.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getEmoji();
    }

    @Test
    @DisplayName("tryAttachCustomerReaction: a truncated (ellipsis) quoted excerpt still matches via prefix")
    void truncatedQuoteMatchesViaPrefix() {
        SmsMessage sent = SmsMessage.builder().id(7L).direction("OUTBOUND")
                .body("Just a reminder that your appointment is tomorrow at 2pm, see you then!").build();
        when(messageRepository.findTop20ByPhoneNumberAndDirectionOrderByCreatedAtDesc(PHONE, "OUTBOUND"))
                .thenReturn(List.of(sent));

        boolean attached = service.tryAttachCustomerReaction(PHONE, "Liked “Just a reminder that your appointment is…”");

        assertThat(attached).isTrue();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("tryAttachCustomerReaction: an ordinary reply (not a tapback) is left alone")
    void ordinaryReplyIsIgnored() {
        boolean attached = service.tryAttachCustomerReaction(PHONE, "Thanks, see you then!");

        assertThat(attached).isFalse();
        verifyNoInteractions(messageRepository, repository, events);
    }

    @Test
    @DisplayName("tryAttachCustomerReaction: tapback text with no matching outbound message is a no-op")
    void tapbackWithNoMatchIsNoop() {
        when(messageRepository.findTop20ByPhoneNumberAndDirectionOrderByCreatedAtDesc(PHONE, "OUTBOUND"))
                .thenReturn(List.of(SmsMessage.builder().id(1L).direction("OUTBOUND").body("completely different text").build()));

        boolean attached = service.tryAttachCustomerReaction(PHONE, "Loved \"Something we never sent\"");

        assertThat(attached).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("tryAttachCustomerReaction: a re-tap on the same message updates the existing row instead of duplicating")
    void reTapUpdatesExistingRow() {
        SmsMessage sent = SmsMessage.builder().id(42L).direction("OUTBOUND").body("Thanks so much for coming in!").build();
        when(messageRepository.findTop20ByPhoneNumberAndDirectionOrderByCreatedAtDesc(PHONE, "OUTBOUND"))
                .thenReturn(List.of(sent));
        SmsMessageReaction existing = SmsMessageReaction.builder().id(9L).smsMessageId(42L).emoji("👍").build();
        when(repository.findBySmsMessageId(42L)).thenReturn(Optional.of(existing));

        service.tryAttachCustomerReaction(PHONE, "Loved “Thanks so much for coming in!”");

        verify(repository).save(existing);
        assertThat(existing.getEmoji()).isEqualTo("❤️");
    }

    @Test
    @DisplayName("reactionsForMessages: empty input short-circuits with no repository query")
    void reactionsForMessagesEmptyInputSkipsQuery() {
        assertThat(service.reactionsForMessages(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("reactionsForMessages: groups by message id")
    void reactionsForMessagesGroupsByMessageId() {
        when(repository.findBySmsMessageIdIn(List.of(1L))).thenReturn(List.of(
                SmsMessageReaction.builder().smsMessageId(1L).emoji("❤️").build()));

        var result = service.reactionsForMessages(List.of(1L));

        assertThat(result.get(1L)).extracting(SmsReactionService.ReactionDto::emoji).containsExactly("❤️");
    }
}
