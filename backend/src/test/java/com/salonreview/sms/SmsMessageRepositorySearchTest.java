package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real Postgres-only bug: {@code search} with every filter null (the
 * default, unfiltered activity-log load) threw {@code operator does not exist: text ~~ bytea}
 * because an un-cast null bind parameter inside {@code CONCAT()}/{@code LIKE} left Postgres
 * unable to infer its type. Mocked unit tests can't catch this — it only reproduces against a
 * real Postgres, same as {@code SalonreviewApplicationTests} (fails locally without one, passes
 * in CI).
 */
@SpringBootTest
class SmsMessageRepositorySearchTest {

    @Autowired
    private SmsMessageRepository repository;

    private static final Long BUSINESS_ID = 1L;

    @Test
    void searchWithAllFiltersNullDoesNotThrow() {
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("hi").status("SENT").build());

        var page = repository.search(BUSINESS_ID, null, null, null,
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).isNotEmpty();
    }

    @Test
    void searchWithPhoneNumberFilterStillWorks() {
        repository.save(SmsMessage.builder().businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15559998888")
                .body("hi").status("SENT").build());

        var page = repository.search(BUSINESS_ID, "9998888", null, null,
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).anyMatch(m -> m.getPhoneNumber().equals("+15559998888"));
    }
}
