package com.salonreview.marketing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

@Component
public class LeadDigestScheduler {

    private final LeadDigestService digest;

    public LeadDigestScheduler(LeadDigestService digest) {
        this.digest = digest;
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "leadDailyDigest", lockAtMostFor = "PT4M", lockAtLeastFor = "PT20S")
    public void tick() {
        digest.runDue(ZonedDateTime.now(LeadDigestService.TZ));
    }
}
