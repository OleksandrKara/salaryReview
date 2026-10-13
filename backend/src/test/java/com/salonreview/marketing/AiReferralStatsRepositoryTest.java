package com.salonreview.marketing;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AI-referred visitors -> leads -> paying clients, against a real Postgres (rolled back). */
@SpringBootTest
@Transactional
class AiReferralStatsRepositoryTest {

    @Autowired private AiReferralStatsRepository repo;
    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void countsAiVisitorsLeadsAndRevenueForThisBusinessOnly() {
        Long b = businesses.save(Business.builder().name("AI Referral Test").shortCode("ai-ref-" + System.nanoTime())
                .timezone("UTC").active(true).build()).getId();
        Long other = businesses.save(Business.builder().name("AI Referral Other").shortCode("ai-ref-o-" + System.nanoTime())
                .timezone("UTC").active(true).build()).getId();
        // marketing.* is owned by salonLandings (no Flyway migration here); create just the columns
        // this query reads when the test database doesn't have it. DDL is transactional in Postgres,
        // so this rolls back with the test.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS marketing");
        jdbc.execute("CREATE TABLE IF NOT EXISTS marketing.visits (visitor_id uuid NOT NULL, business_id bigint NOT NULL, occurred_at timestamptz DEFAULT now(), "
                + "referrer text, utm_source text, user_agent text, ip_address text)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS marketing.contacts (id uuid DEFAULT gen_random_uuid(), business_id bigint NOT NULL, phone_number text NOT NULL, "
                + "original_traffic_source text, referrer text, utm_source text, square_customer_id text, square_booking_id text, booking_status text, created_at timestamptz DEFAULT now())");
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1";
        jdbc.update("INSERT INTO marketing.visits (visitor_id, business_id, occurred_at, referrer, user_agent) VALUES (?, ?, now(), 'https://chatgpt.com/', ?)", UUID.randomUUID(), b, ua);
        jdbc.update("INSERT INTO marketing.visits (visitor_id, business_id, occurred_at, utm_source, user_agent) VALUES (?, ?, now(), 'perplexity', ?)", UUID.randomUUID(), b, ua);
        jdbc.update("INSERT INTO marketing.visits (visitor_id, business_id, occurred_at, referrer, user_agent) VALUES (?, ?, now(), 'https://www.google.com/', ?)", UUID.randomUUID(), b, ua);
        jdbc.update("INSERT INTO marketing.visits (visitor_id, business_id, occurred_at, referrer, user_agent) VALUES (?, ?, now(), 'https://chatgpt.com/', 'ChatGPT-User/1.0')", UUID.randomUUID(), b);
        jdbc.update("INSERT INTO marketing.visits (visitor_id, business_id, occurred_at, referrer, user_agent) VALUES (?, ?, now(), 'https://chatgpt.com/', ?)", UUID.randomUUID(), other, ua);

        jdbc.update("INSERT INTO marketing.contacts (business_id, phone_number, original_traffic_source, square_customer_id, square_booking_id, created_at) VALUES (?, '+16195550101', 'chatgpt.com', 'CUST-AI-1', 'BK1', now() - interval '3 days')", b);
        jdbc.update("INSERT INTO marketing.contacts (business_id, phone_number, original_traffic_source, created_at) VALUES (?, '+16195550102', 'Google (organic)', now() - interval '3 days')", b);
        long id = 990_000_000L + (System.nanoTime() % 1_000_000);
        jdbc.update("INSERT INTO square_payment (id, business_id, square_payment_id, square_customer_id, status, created_at, total_money, tip_money) VALUES (?, ?, ?, 'CUST-AI-1', 'COMPLETED', now() - interval '1 day', 120.00, 20.00)",
                id, b, "PAY-AI-" + id);

        AiReferralStatsRepository.Stats s = repo.stats(b, 90);

        assertThat(s.visitors()).isEqualTo(2);
        assertThat(s.visitorsBySource()).containsEntry("ChatGPT", 1).containsEntry("Perplexity", 1);
        assertThat(s.leads()).isEqualTo(1);
        assertThat(s.booked()).isEqualTo(1);
        assertThat(s.paidClients()).isEqualTo(1);
        assertThat(s.newClients()).isEqualTo(1);
        assertThat(s.revenue()).isEqualByComparingTo("100.00");
        assertThat(s.weekly()).isNotEmpty();
    }
}
