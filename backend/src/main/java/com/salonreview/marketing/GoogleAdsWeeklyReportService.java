package com.salonreview.marketing;

import com.salonreview.tasks.TaskBotTelegram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Weekly Telegram digest of Google Ads results with ROMI. Revenue is cash collected in the week from
 * clients whose marketing contact is classified google_ads (Square tenders minus tips), so it also
 * counts repeat visits of clients who first came from an ad. ROMI = (revenue - spend) / spend.
 */
@Service
public class GoogleAdsWeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAdsWeeklyReportService.class);
    private static final ZoneId TZ = ZoneId.of("America/Los_Angeles");
    private static final List<String> PRIMARY_ACTIONS = List.of("consultation", "procedure", "call", "sms", "book");

    private final JdbcTemplate jdbc;
    private final GoogleAdsSyncService sync;
    private final TaskBotTelegram telegram;
    private final String ownerChatId;

    public GoogleAdsWeeklyReportService(JdbcTemplate jdbc, GoogleAdsSyncService sync, TaskBotTelegram telegram,
                                        @Value("${tasks.owner-chat-id:}") String ownerChatId) {
        this.jdbc = jdbc;
        this.sync = sync;
        this.telegram = telegram;
        this.ownerChatId = ownerChatId;
    }

    public record Period(BigDecimal spend, long clicks, long impressions, BigDecimal revenue, int newLeads, int newCustomers) {}

    public Period period(long businessId, LocalDate from, LocalDate to) {
        Map<String, Object> ads = jdbc.queryForMap("""
                SELECT COALESCE(SUM(cost_micros), 0) AS cost, COALESCE(SUM(clicks), 0) AS clicks, COALESCE(SUM(impressions), 0) AS imps
                FROM marketing.google_ads_daily WHERE business_id = ? AND day BETWEEN ? AND ?
                """, businessId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        BigDecimal spend = new BigDecimal(ads.get("cost").toString()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP);
        String adsContact = TrafficSourceSql.contactInSources("c", java.util.Set.of("google_ads"));
        BigDecimal revenue = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE((SELECT SUM((t->>'amount')::numeric) FROM jsonb_array_elements(o.tenders_json) t), 0)
                                  - COALESCE(o.total_tip_money, 0)), 0)
                FROM square_order o
                WHERE o.business_id = ? AND o.state = 'COMPLETED' AND (o.closed_at AT TIME ZONE 'America/Los_Angeles')::date BETWEEN ? AND ?
                  AND o.square_customer_id IN (SELECT c.square_customer_id FROM marketing.contacts c
                                               WHERE c.business_id = ? AND c.square_customer_id IS NOT NULL AND %s)
                """.formatted(adsContact), BigDecimal.class, businessId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), businessId);
        Integer leads = jdbc.queryForObject("""
                SELECT count(*) FROM marketing.contacts c WHERE c.business_id = ? AND %s
                  AND (c.created_at AT TIME ZONE 'America/Los_Angeles')::date BETWEEN ? AND ?
                """.formatted(adsContact), Integer.class, businessId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        Integer customers = jdbc.queryForObject("""
                SELECT count(*) FROM marketing.contacts c WHERE c.business_id = ? AND c.square_customer_id IS NOT NULL AND %s
                  AND (c.created_at AT TIME ZONE 'America/Los_Angeles')::date BETWEEN ? AND ?
                """.formatted(adsContact), Integer.class, businessId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return new Period(spend, ((Number) ads.get("clicks")).longValue(), ((Number) ads.get("imps")).longValue(),
                revenue == null ? BigDecimal.ZERO : revenue, leads == null ? 0 : leads, customers == null ? 0 : customers);
    }

    /** Conversion actions of the week, grouped by name (only those that happened). */
    List<String> topActions(long businessId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT action_name, SUM(all_conversions) AS n FROM marketing.google_ads_conversions_daily
                WHERE business_id = ? AND day BETWEEN ? AND ? GROUP BY action_name ORDER BY n DESC LIMIT 6
                """, (rs, i) -> rs.getString("action_name") + ": " + trim(rs.getBigDecimal("n")),
                businessId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }

    List<String> changes(long businessId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT resource_type, operation, count(*) AS n FROM marketing.google_ads_changes
                WHERE business_id = ? AND (changed_at AT TIME ZONE 'America/Los_Angeles')::date BETWEEN ? AND ?
                GROUP BY resource_type, operation ORDER BY n DESC LIMIT 5
                """, (rs, i) -> rs.getString("resource_type") + " " + rs.getString("operation") + " x" + rs.getInt("n"),
                businessId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }

    /** Builds the HTML digest for the 7 days ending yesterday against the 7 days before. */
    public String build(long businessId, LocalDate today) {
        LocalDate to = today.minusDays(1);
        LocalDate from = to.minusDays(6);
        Period cur = period(businessId, from, to);
        Period prev = period(businessId, from.minusDays(7), from.minusDays(1));
        Period m30 = period(businessId, to.minusDays(29), to);
        StringBuilder sb = new StringBuilder();
        sb.append("<b>Google Ads, PMU и Academy</b>\n").append(from).append(" - ").append(to).append("\n\n");
        sb.append(line("Расход", money(cur.spend()), money(prev.spend()))).append('\n');
        sb.append(line("Клики", String.valueOf(cur.clicks()), String.valueOf(prev.clicks()))).append('\n');
        sb.append(line("Новые контакты из рекламы", String.valueOf(cur.newLeads()), String.valueOf(prev.newLeads()))).append('\n');
        sb.append(line("Из них клиенты в Square", String.valueOf(cur.newCustomers()), String.valueOf(prev.newCustomers()))).append('\n');
        sb.append(line("Оплаты клиентов из рекламы", money(cur.revenue()), money(prev.revenue()))).append("\n\n");
        sb.append("<b>ROMI за неделю:</b> ").append(romi(cur)).append('\n');
        sb.append("<b>ROMI за 30 дней:</b> ").append(romi(m30)).append(" (расход ").append(money(m30.spend()))
                .append(", оплаты ").append(money(m30.revenue())).append(")\n");
        if (cur.newLeads() > 0 && cur.spend().signum() > 0) {
            sb.append("Цена контакта: ").append(money(cur.spend().divide(BigDecimal.valueOf(cur.newLeads()), 2, RoundingMode.HALF_UP))).append('\n');
        }
        List<String> actions = topActions(businessId, from, to);
        if (!actions.isEmpty()) sb.append("\nКонверсии Google: ").append(String.join("; ", actions)).append('\n');
        List<String> ch = changes(businessId, from, to);
        if (!ch.isEmpty()) sb.append("Изменения в кабинете: ").append(String.join("; ", ch)).append('\n');
        sb.append("\nОплаты считаются по клиентам, чей первый контакт пришёл из Google Ads (Square, чаевые не входят). ")
                .append("ROMI = (оплаты - расход) / расход.");
        return sb.toString();
    }

    /** Sends the digest to the owner; returns the text sent (or built when send is false). */
    public String run(boolean send) {
        if (!sync.enabled()) return "google ads is not configured";
        StringBuilder all = new StringBuilder();
        for (long businessId : sync.accounts().keySet()) {
            String html = build(businessId, LocalDate.now(TZ));
            all.append(html);
            if (send) deliver(html);
        }
        return all.toString();
    }

    private void deliver(String html) {
        if (ownerChatId == null || ownerChatId.isBlank() || !telegram.configured()) {
            log.warn("Google Ads weekly report not sent: Telegram bot or owner chat id is not configured");
            return;
        }
        try {
            telegram.sendHtml(Long.parseLong(ownerChatId.trim()), html, null);
        } catch (Exception e) {
            log.warn("Google Ads weekly report send failed: {}", e.toString());
        }
    }

    private static String line(String label, String now, String before) {
        return label + ": <b>" + now + "</b> (неделей ранее " + before + ")";
    }

    static String romi(Period p) {
        if (p.spend().signum() <= 0) return "нет расхода";
        BigDecimal pct = p.revenue().subtract(p.spend()).multiply(BigDecimal.valueOf(100)).divide(p.spend(), 0, RoundingMode.HALF_UP);
        return pct + "%";
    }

    static String money(BigDecimal v) {
        return String.format(Locale.US, "$%,.0f", v);
    }

    private static String trim(BigDecimal v) {
        return v.stripTrailingZeros().scale() <= 0 ? v.toBigInteger().toString() : v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
