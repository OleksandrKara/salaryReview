package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A contact made by an Academy request gets the Academy funnel only, even when its row has no landing slug. */
@SpringBootTest
@Transactional
class LeadFollowUpSkipsAcademyTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void contactWithAcademyRequestIsNotPendingForStudioNudge() {
        long b = businesses.save(Business.builder().name("Acad").shortCode("ac-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        jdbc.update("INSERT INTO marketing.contacts (phone_number, business_id, updated_at, created_at) VALUES ('+14085550111', ?, now() - interval '5 minutes', now() - interval '5 minutes')", b);
        jdbc.update("INSERT INTO marketing.contacts (phone_number, business_id, updated_at, created_at) VALUES ('+14085550122', ?, now() - interval '5 minutes', now() - interval '5 minutes')", b);
        jdbc.update("INSERT INTO academy_lead (business_id, name, phone, created_at) VALUES (?, 'Luz', '+14085550111', now() - interval '5 minutes')", b);

        var pending = new MarketingContactsRepository(jdbc)
                .findPendingFollowUp(Instant.now().minusSeconds(120), Instant.now().minusSeconds(600), b);

        assertThat(pending).extracting(MarketingContactsRepository.RawContact::phoneNumber).containsExactly("+14085550122");
    }
}
