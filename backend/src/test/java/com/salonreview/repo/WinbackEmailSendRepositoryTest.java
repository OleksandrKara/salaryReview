package com.salonreview.repo;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SmsMessage;
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
}
