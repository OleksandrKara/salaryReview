package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.MailchimpDeferredSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.MailchimpDeferredSendRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sends the campaigns {@link MailchimpEmailService} queued after Mailchimp kept answering
 * "recipients not ready" (V165, 2026-10-07). One quick try per due row each minute, backing off a
 * few more minutes after every miss; a campaign still not sendable after {@link #MAX_ATTEMPTS}
 * (about an hour) is given up on and logged as an error. A failure of any other kind ends it at
 * once: retrying won't fix it.
 */
@Component
public class MailchimpDeferredSendScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailchimpDeferredSendScheduler.class);
    static final int MAX_ATTEMPTS = 12;

    private final MailchimpDeferredSendRepository repository;
    private final MailchimpConfigRepository configRepository;
    private final MailchimpClient client;
    private final Clock clock;

    @Autowired
    public MailchimpDeferredSendScheduler(MailchimpDeferredSendRepository repository, MailchimpConfigRepository configRepository,
                                          MailchimpClient client) {
        this(repository, configRepository, client, Clock.systemUTC());
    }

    MailchimpDeferredSendScheduler(MailchimpDeferredSendRepository repository, MailchimpConfigRepository configRepository,
                                   MailchimpClient client, Clock clock) {
        this.repository = repository;
        this.configRepository = configRepository;
        this.client = client;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    @SchedulerLock(name = "MailchimpDeferredSendScheduler_run", lockAtLeastFor = "PT20S", lockAtMostFor = "PT5M")
    public void run() {
        Instant now = clock.instant();
        for (MailchimpDeferredSend row : repository.findByStateAndNextAttemptAtBefore(MailchimpDeferredSend.STATE_PENDING, now)) {
            attempt(row, now);
        }
    }

    void attempt(MailchimpDeferredSend row, Instant now) {
        row.setAttempts(row.getAttempts() + 1);
        Optional<MailchimpConfig> config = configRepository.findByBusinessId(row.getBusinessId()).filter(MailchimpConfig::isConfigured);
        try {
            if (config.isEmpty()) throw new IllegalStateException("Mailchimp is no longer configured");
            client.send(config.get(), row.getCampaignId());
            row.setState(MailchimpDeferredSend.STATE_SENT);
            row.setFinishedAt(now);
            log.info("Deferred Mailchimp campaign {} ({}) sent on attempt {}", row.getCampaignId(), row.getCampaignTitle(), row.getAttempts());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            row.setLastError(e.getMessage());
            boolean notReady = e instanceof IOException io && MailchimpEmailService.isRecipientsNotReady(io);
            if (notReady && row.getAttempts() < MAX_ATTEMPTS) {
                row.setNextAttemptAt(now.plus(Duration.ofMinutes(Math.min(row.getAttempts(), 10))));
            } else {
                row.setState(MailchimpDeferredSend.STATE_FAILED);
                row.setFinishedAt(now);
                log.error("Deferred Mailchimp campaign {} ({}) not sent after {} attempts: {}",
                        row.getCampaignId(), row.getCampaignTitle(), row.getAttempts(), e.getMessage());
            }
        }
        repository.save(row);
    }
}
