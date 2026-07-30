package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code conversationSummaries()} is native SQL (Postgres {@code DISTINCT ON}) — a mocked unit
 * test can't catch a syntax error there, same reasoning as {@link SmsMessageRepositorySearchTest}.
 * See openspec/changes/lead-followup-and-manager-inbox design.md D8.
 */
@SpringBootTest
class SmsMessageRepositoryConversationTest {

    @Autowired
    private SmsMessageRepository repository;

    @Test
    void conversationSummariesGroupsByPhoneWithLatestMessageAndUnreadCount() {
        String phone = "+15557778899";
        repository.save(SmsMessage.builder().direction("OUTBOUND").phoneNumber(phone)
                .body("first").status("SENT").createdAt(Instant.now().minusSeconds(120)).build());
        repository.save(SmsMessage.builder().direction("INBOUND").phoneNumber(phone)
                .body("reply one").status("RECEIVED").createdAt(Instant.now().minusSeconds(60)).build());
        repository.save(SmsMessage.builder().direction("INBOUND").phoneNumber(phone)
                .body("latest reply").status("RECEIVED").createdAt(Instant.now()).build());

        List<SmsMessageRepository.ConversationSummaryProjection> conversations = repository.conversationSummaries();

        var match = conversations.stream().filter(c -> c.getPhoneNumber().equals(phone)).findFirst().orElseThrow();
        assertThat(match.getLastMessageBody()).isEqualTo("latest reply");
        assertThat(match.getLastMessageDirection()).isEqualTo("INBOUND");
        assertThat(match.getUnreadCount()).isEqualTo(2L);
    }

    @Test
    void threadReturnsFullChronologicalHistoryForOnePhoneNumber() {
        String phone = "+15551112233";
        repository.save(SmsMessage.builder().direction("OUTBOUND").phoneNumber(phone)
                .body("hello").status("SENT").createdAt(Instant.now().minusSeconds(30)).build());
        repository.save(SmsMessage.builder().direction("INBOUND").phoneNumber(phone)
                .body("hi back").status("RECEIVED").createdAt(Instant.now()).build());

        List<SmsMessage> thread = repository.findByPhoneNumberOrderByCreatedAtAsc(phone);

        assertThat(thread).extracting(SmsMessage::getBody).containsExactly("hello", "hi back");
    }
}
