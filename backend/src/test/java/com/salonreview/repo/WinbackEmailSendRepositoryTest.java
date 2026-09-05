package com.salonreview.repo;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.WinbackEmailSend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link WinbackEmailSendRepository#countConvertedSince}'s native query (added
 * 2026-09-04 to back each automation card's own email-channel "returned" stat — see
 * SmsAutomationService#list) against a real Postgres, same reasoning as
 * SameDayRebookingSendRepositoryTest's own doc for why a mocked test alone isn't enough here.
 */
@SpringBootTest
class WinbackEmailSendRepositoryTest {

    @Autowired
    private WinbackEmailSendRepository emailSendRepository;
    @Autowired
    private ProviderVisitRepository providerVisitRepository;
    @Autowired
    private SmsMessageRepository smsMessageRepository;
    @Autowired
    private SmsReplyFlowRepository replyFlowRepository;
    @Autowired
    private BusinessRepository businesses;

    private static SmsMessage smsMessage(Long businessId) {
        return SmsMessage.builder().businessId(businessId).direction("OUTBOUND")
                .phoneNumber("+15550000000").body("").status("SENT").build();
    }

    @Test
    void countsOnlyScopedToTheGivenAutomationKeyAndSentState() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant since = Instant.parse("2026-01-01T00:00:00Z");

        // winback_email_send.sms_message_id has a real FK into sms_message — needs a real row,
        // not a made-up literal id.
        Long smsId1 = smsMessageRepository.save(smsMessage(businessId)).getId();
        Long smsId2 = smsMessageRepository.save(smsMessage(businessId)).getId();
        Long smsId3 = smsMessageRepository.save(smsMessage(businessId)).getId();

        // Converted, same_day_rebooking_discount: email sent 2026-06-01, real visit 2026-06-10.
        emailSendRepository.save(WinbackEmailSend.builder()
                .businessId(businessId).automationKey("same_day_rebooking_discount").smsMessageId(smsId1)
                .squareCustomerId("email-conv-cust").emailAddress("a@example.com")
                .state(WinbackEmailSend.STATE_SENT).createdAt(Instant.parse("2026-06-01T19:00:00Z")).build());
        providerVisitRepository.save(ProviderVisit.builder().businessId(businessId).customerId("email-conv-cust")
                .providerRef("prov1").providerName("Lesya").serviceDate(LocalDate.of(2026, 6, 10)).build());

        // Same customer/visit, but a DIFFERENT automationKey — must not be counted under
        // same_day_rebooking_discount just because the customer_id and visit line up.
        emailSendRepository.save(WinbackEmailSend.builder()
                .businessId(businessId).automationKey("lapsed_customer_winback").smsMessageId(smsId2)
                .squareCustomerId("email-conv-cust").emailAddress("a@example.com")
                .state(WinbackEmailSend.STATE_SENT).createdAt(Instant.parse("2026-06-01T19:00:00Z")).build());

        // Real visit exists, but this row's state isn't SENT (e.g. SKIPPED_REPLIED) — never sent,
        // so a later visit can't be credited to this email.
        emailSendRepository.save(WinbackEmailSend.builder()
                .businessId(businessId).automationKey("same_day_rebooking_discount").smsMessageId(smsId3)
                .squareCustomerId("email-skipped-cust").emailAddress("")
                .state(WinbackEmailSend.STATE_SKIPPED_REPLIED).createdAt(Instant.parse("2026-06-01T19:00:00Z")).build());
        providerVisitRepository.save(ProviderVisit.builder().businessId(businessId).customerId("email-skipped-cust")
                .providerRef("prov1").providerName("Lesya").serviceDate(LocalDate.of(2026, 6, 10)).build());

        long sameDayCount = emailSendRepository.countConvertedSince(
                businessId, "same_day_rebooking_discount", WinbackEmailSend.STATE_SENT, since);
        long lapsedCount = emailSendRepository.countConvertedSince(
                businessId, "lapsed_customer_winback", WinbackEmailSend.STATE_SENT, since);

        assertThat(sameDayCount).isEqualTo(1);
        // Same customer's real visit also satisfies lapsed_customer_winback's own SENT row (each
        // automationKey's count is independent, not mutually exclusive) — the point of this
        // assertion is that both queries correctly scope by automationKey rather than one
        // accidentally excluding a real conversion the other legitimately counts.
        assertThat(lapsedCount).isEqualTo(1);
    }

    private static SmsReplyFlow replyFlow(Long businessId, Long askMessageId, String state) {
        return SmsReplyFlow.builder().businessId(businessId).automationKey("checkout_review_request")
                .phoneNumber("+15550000000").state(state).askSmsMessageId(askMessageId)
                .sendDueAt(Instant.parse("2026-06-01T19:00:00Z")).build();
    }

    /** {@link WinbackEmailSendRepository#countRespondedSince} — checkout_review_request's own
     * "returned" stat (see SmsAutomationService#list): "converted" means "actually rated", not
     * "came back for a visit," since a satisfaction request has no discount to earn a real visit
     * back for. Exercised against a real Postgres for the same reason as the query above — a
     * hand-written JOIN in a native query is exactly the kind of thing a mocked test can't catch a
     * typo/column-name mistake in. */
    @Test
    void countRespondedSinceCountsOnlyFlowsThatActuallyCompleted() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant since = Instant.parse("2026-01-01T00:00:00Z");

        Long completedAskId = smsMessageRepository.save(smsMessage(businessId)).getId();
        Long expiredAskId = smsMessageRepository.save(smsMessage(businessId)).getId();
        Long otherAutomationAskId = smsMessageRepository.save(smsMessage(businessId)).getId();

        // Responded via the email fallback (see CheckoutReviewRatingController) — flow completed.
        replyFlowRepository.save(replyFlow(businessId, completedAskId, SmsReplyFlow.STATE_COMPLETED));
        emailSendRepository.save(WinbackEmailSend.builder()
                .businessId(businessId).automationKey("checkout_review_request").smsMessageId(completedAskId)
                .squareCustomerId("responded-cust").emailAddress("a@example.com")
                .state(WinbackEmailSend.STATE_SENT).createdAt(Instant.parse("2026-06-01T19:00:00Z")).build());

        // Email sent, but the customer never clicked any rating — flow stayed EXPIRED.
        replyFlowRepository.save(replyFlow(businessId, expiredAskId, SmsReplyFlow.STATE_EXPIRED));
        emailSendRepository.save(WinbackEmailSend.builder()
                .businessId(businessId).automationKey("checkout_review_request").smsMessageId(expiredAskId)
                .squareCustomerId("no-response-cust").emailAddress("b@example.com")
                .state(WinbackEmailSend.STATE_SENT).createdAt(Instant.parse("2026-06-01T19:00:00Z")).build());

        // A different automation's own completed flow, sharing no row here — must not leak in.
        replyFlowRepository.save(SmsReplyFlow.builder().businessId(businessId).automationKey("lead_follow_up")
                .phoneNumber("+15550000001").state(SmsReplyFlow.STATE_COMPLETED).askSmsMessageId(otherAutomationAskId)
                .sendDueAt(Instant.parse("2026-06-01T19:00:00Z")).build());
        emailSendRepository.save(WinbackEmailSend.builder()
                .businessId(businessId).automationKey("checkout_review_request").smsMessageId(otherAutomationAskId)
                .squareCustomerId("wrong-automation-cust").emailAddress("c@example.com")
                .state(WinbackEmailSend.STATE_SENT).createdAt(Instant.parse("2026-06-01T19:00:00Z")).build());

        long responded = emailSendRepository.countRespondedSince(
                businessId, "checkout_review_request", WinbackEmailSend.STATE_SENT, since);

        assertThat(responded).isEqualTo(1);
    }
}
