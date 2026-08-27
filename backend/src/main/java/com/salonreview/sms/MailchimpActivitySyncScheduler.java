package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Pulls open/click telemetry from Mailchimp for recently-sent win-back emails, so the owner's
 * activity dashboard reads from our own DB (fast, no live API calls per page load) instead of
 * querying Mailchimp on every request. Runs every 30 minutes — opens/clicks trickle in over hours,
 * not seconds, so this doesn't need to be anywhere near real-time; ShedLock's own
 * {@code lockAtMostFor} keeps two overlapping runs from double-processing.
 *
 * <p>Only checks sends from the last 14 days (see {@link WinbackEmailSendRepository#findNeedingActivitySync})
 * — a customer who hasn't opened an email within two weeks essentially never will, so there's no
 * value in checking indefinitely.
 */
@Component
public class MailchimpActivitySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailchimpActivitySyncScheduler.class);
    private static final Duration SYNC_WINDOW = Duration.ofDays(14);

    private final WinbackEmailSendRepository winbackEmailSendRepository;
    private final MailchimpConfigRepository mailchimpConfigRepository;
    private final MailchimpClient client;

    public MailchimpActivitySyncScheduler(WinbackEmailSendRepository winbackEmailSendRepository,
                                           MailchimpConfigRepository mailchimpConfigRepository,
                                           MailchimpClient client) {
        this.winbackEmailSendRepository = winbackEmailSendRepository;
        this.mailchimpConfigRepository = mailchimpConfigRepository;
        this.client = client;
    }

    @Scheduled(cron = "0 */30 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "MailchimpActivitySyncScheduler_sync", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sync() {
        Instant since = Instant.now().minus(SYNC_WINDOW);
        var pending = winbackEmailSendRepository.findNeedingActivitySync(since);
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, MailchimpConfig> configByBusiness = new HashMap<>();
        for (WinbackEmailSend row : pending) {
            MailchimpConfig config = configByBusiness.computeIfAbsent(row.getBusinessId(),
                    id -> mailchimpConfigRepository.findByBusinessId(id).orElse(null));
            if (config == null || !config.isConfigured()) {
                continue; // credentials were cleared since the send; nothing to check against
            }
            try {
                MailchimpClient.EmailActivity activity = client.fetchEmailActivity(config, row.getMailchimpCampaignId());
                boolean changed = false;
                if (activity.openedAt() != null && row.getOpenedAt() == null) {
                    row.setOpenedAt(activity.openedAt());
                    changed = true;
                }
                if (activity.clickedAt() != null && row.getEmailClickedAt() == null) {
                    row.setEmailClickedAt(activity.clickedAt());
                    changed = true;
                }
                if (changed) {
                    winbackEmailSendRepository.save(row);
                }
            } catch (Exception e) {
                log.warn("Failed to sync Mailchimp activity for winback_email_send {} (retried next run): {}",
                        row.getId(), e.getMessage());
            }
        }
    }
}
