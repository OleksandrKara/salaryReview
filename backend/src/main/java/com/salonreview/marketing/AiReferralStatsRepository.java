package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Visitors, leads, bookings and money that came from AI assistants (ChatGPT, Perplexity, Gemini,
 * Copilot, Claude) for the AI tab's "From AI to clients" block (owner request 2026-10-13, after
 * comparing with InWeb's VYDAI, which shows AI traffic only). Visits are bot-filtered with the same
 * rule as every other marketing count (TrafficSourceSql.notAutomated). Leads are first-touch
 * (marketing.contacts.original_traffic_source / referrer / utm_source); money is Square COMPLETED
 * payments of that customer from the day before the lead onward, tips excluded. */
@Repository
public class AiReferralStatsRepository {

    static final String AI_REGEX = "chatgpt|openai|perplexity|gemini\\.google|bard\\.google|copilot|claude\\.ai";

    private static String sourceCase(String referrer, String utmSource) {
        return "CASE WHEN COALESCE(" + referrer + ",'') ~* 'chatgpt|openai' OR COALESCE(" + utmSource + ",'') ~* 'chatgpt|openai' THEN 'ChatGPT'"
                + " WHEN COALESCE(" + referrer + ",'') ~* 'perplexity' OR COALESCE(" + utmSource + ",'') ~* 'perplexity' THEN 'Perplexity'"
                + " WHEN COALESCE(" + referrer + ",'') ~* 'gemini\\.google|bard\\.google' OR COALESCE(" + utmSource + ",'') ~* 'gemini' THEN 'Gemini'"
                + " WHEN COALESCE(" + referrer + ",'') ~* 'copilot' OR COALESCE(" + utmSource + ",'') ~* 'copilot' THEN 'Copilot'"
                + " WHEN COALESCE(" + referrer + ",'') ~* 'claude\\.ai' OR COALESCE(" + utmSource + ",'') ~* 'claude' THEN 'Claude'"
                + " END";
    }

    public record WeekVisitors(LocalDate weekStart, int visitors) {
    }

    public record Stats(int days, Map<String, Integer> visitorsBySource, int visitors, List<WeekVisitors> weekly,
            int leads, int booked, int paidClients, int newClients, BigDecimal revenue) {
    }

    private final JdbcTemplate jdbc;

    public AiReferralStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Stats stats(Long businessId, int days) {
        String src = sourceCase("v.referrer", "v.utm_source");
        String visitWhere = " FROM marketing.visits v WHERE v.business_id = ? AND v.occurred_at > now() - make_interval(days => ?)"
                + " AND " + src + " IS NOT NULL AND " + TrafficSourceSql.notAutomated("v.visitor_id");
        Map<String, Integer> bySource = new LinkedHashMap<>();
        jdbc.query("SELECT " + src + " AS src, COUNT(DISTINCT v.visitor_id) AS n" + visitWhere + " GROUP BY 1 ORDER BY 2 DESC",
                rs -> { bySource.put(rs.getString("src"), rs.getInt("n")); }, businessId, days);
        Integer total = jdbc.queryForObject("SELECT COUNT(DISTINCT v.visitor_id)" + visitWhere, Integer.class, businessId, days);
        List<WeekVisitors> weekly = new ArrayList<>();
        jdbc.query("SELECT date_trunc('week', v.occurred_at AT TIME ZONE 'America/Los_Angeles')::date AS wk, COUNT(DISTINCT v.visitor_id) AS n"
                        + visitWhere + " GROUP BY 1 ORDER BY 1",
                rs -> { weekly.add(new WeekVisitors(rs.getDate("wk").toLocalDate(), rs.getInt("n"))); }, businessId, days);

        String aiContact = "(COALESCE(c.original_traffic_source,'') ~* '" + AI_REGEX + "' OR COALESCE(c.referrer,'') ~* '" + AI_REGEX
                + "' OR COALESCE(c.utm_source,'') ~* '" + AI_REGEX + "')";
        String sql = """
                WITH c AS (
                    SELECT c.square_customer_id, c.square_booking_id, c.booking_status, c.created_at
                    FROM marketing.contacts c
                    WHERE c.business_id = ? AND c.created_at > now() - make_interval(days => ?) AND %s
                ), paid AS (
                    SELECT c.square_customer_id, SUM(p.total_money - COALESCE(p.tip_money, 0)) AS amt,
                           bool_and(NOT EXISTS (SELECT 1 FROM square_payment e WHERE e.business_id = ? AND e.square_customer_id = c.square_customer_id
                                                AND e.status = 'COMPLETED' AND e.created_at < c.created_at - interval '1 day')) AS is_new
                    FROM c JOIN square_payment p ON p.business_id = ? AND p.square_customer_id = c.square_customer_id
                         AND p.status = 'COMPLETED' AND p.created_at >= c.created_at - interval '1 day'
                    WHERE c.square_customer_id IS NOT NULL
                    GROUP BY c.square_customer_id
                )
                SELECT (SELECT COUNT(*) FROM c) AS leads,
                       (SELECT COUNT(*) FROM c WHERE c.square_booking_id IS NOT NULL OR c.booking_status IS NOT NULL) AS booked,
                       (SELECT COUNT(*) FROM paid) AS paid_clients,
                       (SELECT COUNT(*) FROM paid WHERE is_new) AS new_clients,
                       (SELECT COALESCE(SUM(amt), 0) FROM paid) AS revenue
                """.formatted(aiContact);
        return jdbc.queryForObject(sql, (rs, i) -> new Stats(days, bySource, total == null ? 0 : total, weekly,
                rs.getInt("leads"), rs.getInt("booked"), rs.getInt("paid_clients"), rs.getInt("new_clients"), rs.getBigDecimal("revenue")),
                businessId, days, businessId, businessId);
    }
}
