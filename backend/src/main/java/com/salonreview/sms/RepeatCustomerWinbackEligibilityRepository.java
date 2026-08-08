package com.salonreview.sms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads {@code provider_visit} for the {@code repeat_customer_winback} automation's eligibility
 * query — same "plain JdbcTemplate for a single narrow query" reasoning as
 * {@link LapsedCustomerWinbackEligibilityRepository}.
 *
 * <p>The 40-day threshold and the "2+ completed visits" floor come directly from the deep-dive
 * churn analysis run against this same {@code provider_visit} table (see the visit #2 → #3 churn
 * analysis): the salon's own median time-between-visits is ~27-29 days at every stage, and 40 days
 * is comfortably past the point (~p75-p80) by which most customers who are going to return
 * naturally already have. Customers with exactly one all-time visit are excluded — they're already
 * covered by {@code lapsed_customer_winback}, which uses a narrower 21-35 day window built for a
 * one-shot nudge, not a recurring one.
 */
@Repository
public class RepeatCustomerWinbackEligibilityRepository {

    /** One eligible customer. {@code lastProvider}/{@code previousProvider} are that customer's
     * two most recent completed visits' {@code provider_visit.provider_name} — always both
     * present when eligible, since eligibility already requires 2+ visits. {@code rebookedSameDay}
     * is {@code true} if ANY provider row on the last visit's date had the flag set (covers the
     * rare same-day multi-technician/four-hands case the same way the retention-funnel analysis
     * did). {@code daysSinceLastVisit} is deliberately NOT included here — the scheduler computes
     * it fresh from {@code lastVisitDate} at process time in the salon's own zone, so a long-running
     * batch doesn't use a slightly-stale number computed when the query ran. */
    public record EligibleCustomer(String squareCustomerId, LocalDate lastVisitDate, int totalVisitCount,
                                    String lastProvider, String previousProvider, boolean rebookedSameDay) {}

    private final JdbcTemplate jdbcTemplate;

    public RepeatCustomerWinbackEligibilityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EligibleCustomer> findEligibleCustomers() {
        String sql = "WITH visits AS ("
                + "  SELECT customer_id, service_date,"
                + "         MIN(provider_name) AS provider_name,"
                + "         bool_or(rebooked_same_day) AS rebooked_same_day"
                + "  FROM provider_visit"
                + "  WHERE customer_id IS NOT NULL"
                + "  GROUP BY customer_id, service_date"
                + "), ranked AS ("
                + "  SELECT customer_id, service_date, provider_name, rebooked_same_day,"
                + "         row_number() OVER (PARTITION BY customer_id ORDER BY service_date DESC) AS rn,"
                + "         COUNT(*) OVER (PARTITION BY customer_id) AS visit_count"
                + "  FROM visits"
                + ") "
                + "SELECT last.customer_id, last.service_date AS last_visit_date, last.visit_count,"
                + "       last.provider_name AS last_provider, last.rebooked_same_day,"
                + "       prev.provider_name AS previous_provider "
                + "FROM ranked last "
                + "JOIN ranked prev ON prev.customer_id = last.customer_id AND prev.rn = 2 "
                + "WHERE last.rn = 1 "
                + "  AND last.visit_count >= 2 "
                + "  AND last.service_date <= (CURRENT_DATE - INTERVAL '40 days') "
                + "  AND NOT EXISTS (SELECT 1 FROM repeat_customer_winback_send w "
                + "                  WHERE w.square_customer_id = last.customer_id "
                + "                    AND w.state = 'SENT' "
                + "                    AND w.created_at >= (now() - INTERVAL '60 days'))";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EligibleCustomer(
                rs.getString("customer_id"),
                rs.getDate("last_visit_date").toLocalDate(),
                rs.getInt("visit_count"),
                rs.getString("last_provider"),
                rs.getString("previous_provider"),
                rs.getBoolean("rebooked_same_day")));
    }
}
