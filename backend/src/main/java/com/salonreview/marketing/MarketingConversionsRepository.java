package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class MarketingConversionsRepository {

    public record ContactRow(String customerId, String bookingId, String serviceName, Instant createdAt) {}

    public record PaymentRow(String customerId, BigDecimal amount, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    public MarketingConversionsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countPageViews(UUID landingPageId, Instant from, Instant to) {
        return eventCount("e.event_type = 'page_view'", landingPageId, from, to, "COUNT(DISTINCT e.session_id)");
    }

    /** Distinct visitors who tapped a text link (target "sms") or a call link (target "phone"). */
    public long countClickVisitors(UUID landingPageId, String target, Instant from, Instant to) {
        return eventCount("e.event_type = 'click' AND e.metadata->>'target' = '" + target + "'",
                landingPageId, from, to, "COUNT(DISTINCT e.session_id)");
    }

    private long eventCount(String where, UUID landingPageId, Instant from, Instant to, String agg) {
        String sql = "SELECT " + agg + " FROM marketing.events e WHERE e.landing_page_id = ? AND " + where
                + " AND (?::timestamptz IS NULL OR e.created_at >= ?) AND (?::timestamptz IS NULL OR e.created_at < ?)"
                + " AND " + TrafficSourceSql.notAutomated("e.session_id");
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        Long n = jdbc.queryForObject(sql, Long.class, landingPageId, f, f, t, t);
        return n == null ? 0 : n;
    }

    /** People who left contact details on this page in the window (one row per phone number). */
    public List<ContactRow> findContacts(Long businessId, String slug, Instant from, Instant to) {
        Timestamp f = from == null ? null : Timestamp.from(from);
        Timestamp t = to == null ? null : Timestamp.from(to);
        return jdbc.query(
                "SELECT c.square_customer_id, c.square_booking_id, c.booking_service_name, c.created_at "
                        + "FROM marketing.contacts c WHERE c.business_id = ? AND c.landing_page_slug = ? "
                        + "AND (?::timestamptz IS NULL OR c.created_at >= ?) AND (?::timestamptz IS NULL OR c.created_at < ?)",
                (rs, i) -> new ContactRow(rs.getString("square_customer_id"), rs.getString("square_booking_id"),
                        rs.getString("booking_service_name"), rs.getTimestamp("created_at").toInstant()),
                businessId, slug, f, f, t, t);
    }

    public List<PaymentRow> findPayments(Long businessId, Collection<String> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(customerIds.size(), "?"));
        Object[] args = new Object[customerIds.size() + 1];
        args[0] = businessId;
        int i = 1;
        for (String id : customerIds) args[i++] = id;
        return jdbc.query(
                "SELECT square_customer_id, total_money, created_at FROM square_payment "
                        + "WHERE business_id = ? AND status = 'COMPLETED' AND total_money IS NOT NULL "
                        + "AND created_at IS NOT NULL AND square_customer_id IN (" + marks + ")",
                (rs, n) -> new PaymentRow(rs.getString("square_customer_id"), rs.getBigDecimal("total_money"),
                        rs.getTimestamp("created_at").toInstant()),
                args);
    }
}
