package com.salonreview.marketing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LeadIntakeScheduler {

    private final LeadIntakeService service;
    private final LeadFollowUpService followUp;

    public LeadIntakeScheduler(LeadIntakeService service, LeadFollowUpService followUp) {
        this.service = service;
        this.followUp = followUp;
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "leadCallbackReminders", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void callbackReminders() {
        followUp.sendDueReminders();
        followUp.autoBook();
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "leadIntakeReconcile", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        service.reconcile();
    }
}
