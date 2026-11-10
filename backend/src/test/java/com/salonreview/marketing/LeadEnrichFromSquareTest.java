package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A lead made from a text gets the name and email staff later add in Square, even on a second profile with the same phone. */
@SpringBootTest
@Transactional
class LeadEnrichFromSquareTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;

    private void customer(long b, String id, String phone, String given, String family, String email, String created) {
        jdbc.update("""
                INSERT INTO square_customer (business_id, square_customer_id, phone_number, given_name, family_name, email_address, square_created_at, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, now())
                """, b, id, phone, given, family, email, created);
    }

    @Test
    void emptyLeadTakesNameAndEmailFromNewerProfileWithSamePhoneAndKeepsExistingValues() {
        long b = businesses.save(Business.builder().name("Enrich").shortCode("en-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        LeadIntakeService real = new LeadIntakeService(jdbc, null, null, String.valueOf(b));
        customer(b, "C_OLD", "+14085550100", null, null, null, "2026-11-10T15:00:00Z");
        customer(b, "C_NEW", "+14085550100", "Devyn", "Wood", "d@example.com", "2026-11-10T18:00:00Z");
        long empty = jdbc.queryForObject("INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, square_customer_id) VALUES (?, 'SMS', 'Direct', '+14085550100', 'CREATED', 'C_OLD') RETURNING id", Long.class, b);
        long named = jdbc.queryForObject("INSERT INTO lead_intake (business_id, channel, source, phone_number, name, outcome, square_customer_id) VALUES (?, 'SMS', 'Direct', '+14085550100', 'Typed Name', 'CREATED', 'C_OLD') RETURNING id", Long.class, b);

        assertThat(real.enrichFromSquare()).isEqualTo(2);

        Map<String, Object> e = jdbc.queryForMap("SELECT name, email FROM lead_intake WHERE id = ?", empty);
        assertThat(e.get("name")).isEqualTo("Devyn Wood");
        assertThat(e.get("email")).isEqualTo("d@example.com");
        Map<String, Object> n = jdbc.queryForMap("SELECT name, email FROM lead_intake WHERE id = ?", named);
        assertThat(n.get("name")).isEqualTo("Typed Name");
        assertThat(n.get("email")).isEqualTo("d@example.com");
    }
}
