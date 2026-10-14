package com.salonreview.marketing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily refresh of {@code marketing_automated_ip} (Notion #51, 2026-10-14): an IP with 5+
 * different visitor ids in the last 30 days and not one action beyond page views (no click,
 * funnel step or submission) is treated as automated traffic and left out of the Marketing
 * dashboards ({@link TrafficSourceSql#notAutomated}). Recomputed from scratch every night, so an
 * IP that starts acting like a real visitor (a click or a booking) drops off the list the next day.
 */
@Component
public class AutomatedIpScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomatedIpScheduler.class);

    static final String DETECT_SQL = """
            INSERT INTO marketing_automated_ip (ip_address, visitors)
            WITH v AS (SELECT ip_address AS ip, visitor_id FROM marketing.visits
                       WHERE occurred_at > now() - interval '30 days' AND ip_address IS NOT NULL AND ip_address <> ''),
            act AS (SELECT session_id AS vid FROM marketing.events WHERE event_type <> 'page_view' AND created_at > now() - interval '30 days'
                    UNION SELECT session_id FROM marketing.funnel_events WHERE created_at > now() - interval '30 days'
                    UNION SELECT visitor_id FROM marketing.submissions WHERE occurred_at > now() - interval '30 days' AND visitor_id IS NOT NULL)
            SELECT ip, count(DISTINCT visitor_id) FROM v GROUP BY ip
            HAVING count(DISTINCT visitor_id) >= 5
               AND count(DISTINCT visitor_id) FILTER (WHERE visitor_id IN (SELECT vid FROM act)) = 0
            """;

    private final JdbcTemplate jdbc;

    public AutomatedIpScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "AutomatedIpScheduler_refresh", lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    @Transactional
    public void refresh() {
        jdbc.update("DELETE FROM marketing_automated_ip");
        int n = jdbc.update(DETECT_SQL);
        log.info("Automated-traffic IPs refreshed: {}", n);
    }
}
