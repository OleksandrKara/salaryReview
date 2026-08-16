package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    private static final Long BUSINESS_ID = 1L;

    @Test
    void conversationSummariesGroupsByPhoneWithLatestMessageAndUnreadCount() {
        String phone = "+15557778899";
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber(phone)
                .body("first").status("SENT").createdAt(Instant.now().minusSeconds(120)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phone)
                .body("reply one").status("RECEIVED").createdAt(Instant.now().minusSeconds(60)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phone)
                .body("latest reply").status("RECEIVED").createdAt(Instant.now()).build());

        List<SmsMessageRepository.ConversationSummaryProjection> conversations = repository.conversationSummaries(BUSINESS_ID);

        var match = conversations.stream().filter(c -> c.getPhoneNumber().equals(phone)).findFirst().orElseThrow();
        assertThat(match.getLastMessageBody()).isEqualTo("latest reply");
        assertThat(match.getLastMessageDirection()).isEqualTo("INBOUND");
        assertThat(match.getUnreadCount()).isEqualTo(2L);
        assertThat(match.getLastMessageDeliveryStatus()).isNull();
        assertThat(match.getHasNegativeFeedback()).isFalse();
    }

    @Test
    void conversationSummariesSurfacesNegativeFeedbackEvenWhenNotTheLastMessage() {
        String phone = "+15554443322";
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phone)
                .body("2, not happy").status("RECEIVED").negativeFeedbackAt(Instant.now().minusSeconds(120)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber(phone)
                .body("a later, unrelated message").status("SENT").createdAt(Instant.now()).build());

        List<SmsMessageRepository.ConversationSummaryProjection> conversations = repository.conversationSummaries(BUSINESS_ID);

        var match = conversations.stream().filter(c -> c.getPhoneNumber().equals(phone)).findFirst().orElseThrow();
        assertThat(match.getLastMessageBody()).isEqualTo("a later, unrelated message");
        assertThat(match.getHasNegativeFeedback()).isTrue();
    }

    @Test
    void conversationSummariesSurfacesLastMessageDeliveryFailure() {
        String phone = "+15556665544";
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber(phone)
                .body("rebooking nudge").status("SENT").twilioMessageSid("SM-undelivered")
                .deliveryStatus("undelivered").deliveryErrorCode("30003")
                .deliveryErrorMessage("Phone unreachable (turned off or out of coverage)")
                .createdAt(Instant.now()).build());

        List<SmsMessageRepository.ConversationSummaryProjection> conversations = repository.conversationSummaries(BUSINESS_ID);

        var match = conversations.stream().filter(c -> c.getPhoneNumber().equals(phone)).findFirst().orElseThrow();
        assertThat(match.getLastMessageDeliveryStatus()).isEqualTo("undelivered");
        assertThat(match.getLastMessageDeliveryErrorMessage()).isEqualTo("Phone unreachable (turned off or out of coverage)");
    }

    @Test
    void conversationSummariesPageReturnsNewestFirstPageAndAdvancesByCursor() {
        // This class isn't @Transactional (see other tests' own rows persisting across methods in
        // the same run), so other conversations may already exist with their own "just now"
        // timestamps — every assertion below filters down to just these three known phone numbers
        // rather than assuming these are literally the newest conversations in the whole table.
        String phoneA = "+15551110001";
        String phoneB = "+15551110002";
        String phoneC = "+15551110003";
        Instant now = Instant.now();
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phoneA)
                .body("a").status("RECEIVED").createdAt(now.minusSeconds(10)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phoneB)
                .body("b").status("RECEIVED").createdAt(now.minusSeconds(20)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phoneC)
                .body("c").status("RECEIVED").createdAt(now.minusSeconds(30)).build());
        List<String> ours = List.of(phoneA, phoneB, phoneC);

        List<SmsMessageRepository.ConversationSummaryProjection> everything =
                repository.conversationSummariesPage(BUSINESS_ID, null, 1000);
        List<String> ourOrder = everything.stream()
                .map(SmsMessageRepository.ConversationSummaryProjection::getPhoneNumber)
                .filter(ours::contains)
                .toList();
        assertThat(ourOrder).containsExactly(phoneA, phoneB, phoneC);

        Instant cursor = repository.conversationSummaryForPhone(BUSINESS_ID, phoneB).orElseThrow().getLastMessageAt();
        List<SmsMessageRepository.ConversationSummaryProjection> afterB =
                repository.conversationSummariesPage(BUSINESS_ID, cursor, 1000);
        List<String> afterBOurs = afterB.stream()
                .map(SmsMessageRepository.ConversationSummaryProjection::getPhoneNumber)
                .filter(ours::contains)
                .toList();
        assertThat(afterBOurs).containsExactly(phoneC);
    }

    @Test
    void conversationSummaryForPhoneReturnsThatPhonesLatestMessageAndUnreadCount() {
        String phone = "+15552220001";
        String otherPhone = "+15552220002";
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phone)
                .body("older").status("RECEIVED").createdAt(Instant.now().minusSeconds(60)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phone)
                .body("newest").status("RECEIVED").createdAt(Instant.now()).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(otherPhone)
                .body("unrelated").status("RECEIVED").build());

        Optional<SmsMessageRepository.ConversationSummaryProjection> result =
                repository.conversationSummaryForPhone(BUSINESS_ID, phone);

        assertThat(result).isPresent();
        assertThat(result.get().getPhoneNumber()).isEqualTo(phone);
        assertThat(result.get().getLastMessageBody()).isEqualTo("newest");
        assertThat(result.get().getUnreadCount()).isEqualTo(2L);
    }

    @Test
    void conversationSummaryForPhoneIsEmptyWhenNoMessagesExist() {
        Optional<SmsMessageRepository.ConversationSummaryProjection> result =
                repository.conversationSummaryForPhone(BUSINESS_ID, "+15559990000");

        assertThat(result).isEmpty();
    }

    @Test
    void threadReturnsFullChronologicalHistoryForOnePhoneNumber() {
        String phone = "+15551112233";
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber(phone)
                .body("hello").status("SENT").createdAt(Instant.now().minusSeconds(30)).build());
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("INBOUND").phoneNumber(phone)
                .body("hi back").status("RECEIVED").createdAt(Instant.now()).build());

        List<SmsMessage> thread = repository.findByBusinessIdAndPhoneNumberOrderByCreatedAtAsc(BUSINESS_ID, phone);

        assertThat(thread).extracting(SmsMessage::getBody).containsExactly("hello", "hi back");
    }
}
