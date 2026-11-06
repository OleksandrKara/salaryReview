package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.repo.MailchimpConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Remembers every single-recipient Mailchimp email so the lead card can list and show it. */
@Component
public class SentEmailLog {

    private static final Logger log = LoggerFactory.getLogger(SentEmailLog.class);
    private static final String BACKFILL_SINCE = "2026-08-01T00:00:00+00:00";

    private final JdbcTemplate jdbc;
    private final MailchimpClient client;
    private final MailchimpConfigRepository configs;

    public SentEmailLog(JdbcTemplate jdbc, MailchimpClient client, MailchimpConfigRepository configs) {
        this.jdbc = jdbc;
        this.client = client;
        this.configs = configs;
    }

    public void record(long businessId, String email, String subject, String title, String campaignId, Instant at) {
        try {
            jdbc.update("""
                    INSERT INTO mailchimp_sent_email (business_id, email, subject, campaign_title, campaign_id, sent_at)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (campaign_id) DO NOTHING
                    """, businessId, email, subject, title, campaignId, Timestamp.from(at));
        } catch (RuntimeException e) {
            log.warn("Sent-email log failed for campaign {}: {}", campaignId, e.toString());
        }
    }

    /** One-time fill from Mailchimp for emails sent before the log existed (idempotent, runs in the background). */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStart() {
        Thread t = new Thread(this::backfill, "sent-email-backfill");
        t.setDaemon(true);
        t.start();
    }

    void backfill() {
        try {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM mailchimp_sent_email", Integer.class);
            if (n != null && n > 20) return;
            for (MailchimpConfig mc : configs.findAll()) {
                if (!mc.isConfigured()) continue;
                int added = 0;
                for (MailchimpClient.SentCampaign c : client.listSentCampaigns(mc, BACKFILL_SINCE)) {
                    if (c.email() == null || c.id() == null) continue;
                    record(mc.getBusinessId(), c.email(), c.subject(), c.title(), c.id(), c.sentAt() == null ? Instant.now() : c.sentAt());
                    added++;
                }
                log.info("Sent-email log backfill for business {}: {} campaigns", mc.getBusinessId(), added);
            }
        } catch (Exception e) {
            log.warn("Sent-email log backfill failed: {}", e.toString());
        }
    }
}
