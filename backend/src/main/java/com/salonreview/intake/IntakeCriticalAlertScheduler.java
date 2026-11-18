package com.salonreview.intake;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Every minute: managers' alert and Square booking banner for flagged intake answers. */
@Component
public class IntakeCriticalAlertScheduler {

    private final IntakeCriticalAlertService alerts;

    public IntakeCriticalAlertScheduler(IntakeCriticalAlertService alerts) {
        this.alerts = alerts;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    @SchedulerLock(name = "IntakeCriticalAlert_run", lockAtLeastFor = "PT20S", lockAtMostFor = "PT10M")
    public void run() {
        alerts.run();
    }
}
