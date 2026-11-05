package com.salonreview.marketing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LeadIntakeScheduler {

    private final LeadIntakeService service;

    public LeadIntakeScheduler(LeadIntakeService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "leadIntakeReconcile", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        service.reconcile();
    }
}
