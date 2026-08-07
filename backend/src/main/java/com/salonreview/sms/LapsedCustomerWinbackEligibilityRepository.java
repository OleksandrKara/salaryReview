package com.salonreview.sms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads {@code provider_visit} for the {@code lapsed_customer_winback} automation's eligibility
 * query — plain JdbcTemplate, same reasoning as {@link SmsConsentRepository}: this is a single,
 * narrow, one-off query, not general-purpose CRUD, so a full Spring Data repository would be
 * overkill — see openspec/changes/lapsed-customer-winback-automation design.md D2.
 */
@Repository
public class LapsedCustomerWinbackEligibilityRepository {

    /** One eligible customer: exactly one all-time visit, 21-35 days ago, never processed by this
     * automation before. {@code technicianName} comes straight out of this same query (that one
     * visit's own {@code provider_name}) — no separate Square lookup needed, see design.md D5. */
    public record EligibleCustomer(String squareCustomerId, LocalDate visitDate, String technicianName) {}

    private final JdbcTemplate jdbcTemplate;

    public LapsedCustomerWinbackEligibilityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EligibleCustomer> findEligibleCustomers() {
        String sql = "SELECT customer_id, MIN(service_date) AS only_visit_date, "
                + "MIN(provider_name) AS technician_name "
                + "FROM provider_visit "
                + "WHERE customer_id IS NOT NULL "
                + "GROUP BY customer_id "
                + "HAVING COUNT(*) = 1 "
                + "   AND MIN(service_date) BETWEEN (CURRENT_DATE - INTERVAL '35 days') "
                + "                              AND (CURRENT_DATE - INTERVAL '21 days') "
                + "   AND NOT EXISTS (SELECT 1 FROM lapsed_customer_winback_send w "
                + "                   WHERE w.square_customer_id = provider_visit.customer_id)";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EligibleCustomer(
                rs.getString("customer_id"),
                rs.getDate("only_visit_date").toLocalDate(),
                rs.getString("technician_name")));
    }
}
