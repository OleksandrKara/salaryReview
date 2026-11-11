package com.salonreview.marketing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries sms_sent events GA4 did not accept the first time. */
@Component
public class Ga4SmsSentScheduler {

    private static final Logger log = LoggerFactory.getLogger(Ga4SmsSentScheduler.class);
    private final Ga4SmsSentService service;

    public Ga4SmsSentScheduler(Ga4SmsSentService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "Ga4SmsSentScheduler_retry", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void retry() {
        if (!service.enabled()) return;
        try {
            service.sendPending();
        } catch (RuntimeException e) {
            log.warn("sms_sent retry failed: {}", e.toString());
        }
    }
}
