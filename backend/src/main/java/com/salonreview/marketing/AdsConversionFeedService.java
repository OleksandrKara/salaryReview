package com.salonreview.marketing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Google Ads offline conversions for PMU (Notion TECH-33, PPC team's answer 2026-10-19: import paid
 * procedures as a secondary conversion first). A daily CSV that Ads' scheduled upload reads:
 *
 * <ul>
 *   <li><b>PMU Consultation Attended</b>: the consultation booked from an ad click took place
 *       (booking still ACCEPTED and its start time has passed);</li>
 *   <li><b>PMU Procedure Paid</b>: the client's completed Square payments after the ad click reach
 *       {@link #PROCEDURE_MIN_PAID} within 90 days (deposits and consultations stay below it); the
 *       value is what was paid up to that payment, so the row never changes once it appears.</li>
 * </ul>
 *
 * Matching: the gclid our site saved on the booking submission (marketing.submissions), plus the
 * client's email SHA-256 hashed the way Google asks (enhanced conversions for leads). No phone
 * numbers: the privacy policy promises mobile numbers aren't shared with third parties for
 * marketing (SMS/A2P terms), so they stay out even hashed.
 * Only clicks from the last 89 days: Ads rejects conversions for older clicks.
 */
@Service
public class AdsConversionFeedService {

    static final String CONSULTATION = "PMU Consultation Attended";
    static final String PROCEDURE = "PMU Procedure Paid";
    static final BigDecimal PROCEDURE_MIN_PAID = new BigDecimal("150");
    private static final ZoneId TZ = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TZ);

    private final JdbcTemplate jdbc;

    public AdsConversionFeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Click(String gclid, Instant clickedAt, String serviceName, String bookingStatus, Instant bookingStart,
                 String customerId, String email) {}

    record Payment(Instant paidAt, BigDecimal amount) {}

    record Row(String gclid, String name, Instant at, BigDecimal value, String email) {}

    public String csv(long businessId) {
        List<Row> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (Click c : clicks(businessId)) {
            rows.addAll(conversionsFor(c, c.customerId() == null ? List.of() : payments(businessId, c.customerId(), c.clickedAt()), now));
        }
        return render(rows);
    }

    private List<Click> clicks(long businessId) {
        return jdbc.query("""
                SELECT DISTINCT ON (s.gclid) s.gclid, s.occurred_at, s.service_name, s.customer_email,
                       b.status, b.start_at, b.square_customer_id
                FROM marketing.submissions s
                LEFT JOIN square_booking b ON b.business_id = s.business_id AND b.square_booking_id = s.square_booking_id
                WHERE s.business_id = ? AND s.gclid IS NOT NULL AND s.gclid <> ''
                  AND s.occurred_at > now() - interval '89 days'
                ORDER BY s.gclid, s.occurred_at
                """, (rs, i) -> new Click(
                rs.getString("gclid"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("service_name"),
                rs.getString("status"),
                rs.getTimestamp("start_at") == null ? null : rs.getTimestamp("start_at").toInstant(),
                rs.getString("square_customer_id"),
                rs.getString("customer_email")), businessId);
    }

    /** Completed Square orders of the client within 90 days after the click: what was paid (tenders
     * minus tip), in payment order. */
    private List<Payment> payments(long businessId, String customerId, Instant clickedAt) {
        return jdbc.query("""
                SELECT o.closed_at,
                       COALESCE((SELECT SUM((t->>'amount')::numeric) FROM jsonb_array_elements(o.tenders_json) t), 0)
                         - COALESCE(o.total_tip_money, 0) AS paid
                FROM square_order o
                WHERE o.business_id = ? AND o.square_customer_id = ? AND o.state = 'COMPLETED'
                  AND o.closed_at > ? AND o.closed_at <= ?
                ORDER BY o.closed_at
                """, (rs, i) -> new Payment(rs.getTimestamp("closed_at").toInstant(), rs.getBigDecimal("paid")),
                businessId, customerId, Timestamp.from(clickedAt), Timestamp.from(clickedAt.plusSeconds(90L * 86400)));
    }

    static List<Row> conversionsFor(Click c, List<Payment> payments, Instant now) {
        List<Row> out = new ArrayList<>();
        String email = hashEmail(c.email());
        boolean consultation = c.serviceName() != null && c.serviceName().toLowerCase(Locale.ROOT).contains("consult");
        if (consultation && "ACCEPTED".equals(c.bookingStatus()) && c.bookingStart() != null && c.bookingStart().isBefore(now)) {
            out.add(new Row(c.gclid(), CONSULTATION, c.bookingStart(), null, email));
        }
        BigDecimal paid = BigDecimal.ZERO;
        for (Payment p : payments) {
            paid = paid.add(p.amount() == null ? BigDecimal.ZERO : p.amount());
            if (paid.compareTo(PROCEDURE_MIN_PAID) >= 0) {
                out.add(new Row(c.gclid(), PROCEDURE, p.paidAt(), paid.setScale(2, RoundingMode.HALF_UP), email));
                break;
            }
        }
        return out;
    }

    static String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder("Parameters:TimeZone=America/Los_Angeles\n")
                .append("Google Click ID,Conversion Name,Conversion Time,Conversion Value,Conversion Currency,Email\n");
        for (Row r : rows) {
            sb.append(r.gclid()).append(',').append(r.name()).append(',').append(TIME.format(r.at())).append(',')
                    .append(r.value() == null ? "" : r.value().toPlainString()).append(',')
                    .append(r.value() == null ? "" : "USD").append(',')
                    .append(r.email() == null ? "" : r.email()).append('\n');
        }
        return sb.toString();
    }

    /** Google's normalization: trimmed, lower case; for gmail.com/googlemail.com also no dots and
     * nothing after '+' in the name. */
    static String hashEmail(String email) {
        if (email == null || !email.contains("@")) return null;
        String e = email.trim().toLowerCase(Locale.ROOT);
        int at = e.lastIndexOf('@');
        String name = e.substring(0, at), domain = e.substring(at + 1);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            int plus = name.indexOf('+');
            if (plus >= 0) name = name.substring(0, plus);
            name = name.replace(".", "");
        }
        return sha256(name + "@" + domain);
    }


    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
