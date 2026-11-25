package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Website contacts that never booked must be picked up for the CRM; people already in it must not be. */
@SpringBootTest
@Transactional
class LeadIntakeMissingContactsTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeadIntakeService service;

    private void contact(long b, String phone, String email, String square, String slug, String age) {
        jdbc.update("""
                INSERT INTO marketing.contacts (business_id, phone_number, given_name, email_address, square_customer_id, landing_page_slug, created_at)
                VALUES (?, ?, 'N', ?, ?, ?, now() - ?::interval)
                """, b, phone, email, square, slug, age);
    }

    @Test
    void picksUpContactsWithoutALeadAndSkipsTheOthers() {
        long b = businesses.save(Business.builder().name("Missing").shortCode("ms-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        contact(b, "+13854182471", null, null, "pmu-website", "1 hour");          // never booked: must be picked up
        contact(b, "+16199939588", "a@x.com", null, "pmu-book", "2 hours");       // never booked, has email: picked up
        contact(b, "+14155550101", null, null, "pmu-website", "1 hour");          // fictional 555 test number: skipped
        contact(b, "+14085550111", null, null, "academy", "1 hour");              // academy has its own flow: skipped
        contact(b, "+16195551234", null, "SQ1", "home", "1 hour");                // has a lead by phone: skipped
        contact(b, "+16195557777", "have@x.com", null, "home", "1 hour");         // has a lead by email: skipped
        contact(b, "+16195558888", null, null, "home", "5 days");                 // older than the window: skipped
        jdbc.update("INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome) VALUES (?, 'Call', 'Direct', '(619) 555-1234', 'MATCHED')", b);
        jdbc.update("INSERT INTO lead_intake (business_id, channel, source, email, outcome) VALUES (?, 'Form', 'Direct', 'HAVE@x.com', 'MATCHED')", b);

        var phones = service.contactsMissingFromCrm(b).stream().map(r -> (String) r.get("phone_number")).collect(Collectors.toList());

        assertThat(phones).containsExactly("+16199939588", "+13854182471"); // oldest first
    }
}
