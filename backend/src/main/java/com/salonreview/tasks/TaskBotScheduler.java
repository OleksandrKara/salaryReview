package com.salonreview.tasks;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timers for {@link TaskBotService}. Every job holds a ShedLock so the blue and green containers
 * never both poll Telegram (it rejects concurrent getUpdates) or both send the same digest.
 * All times are Pacific, like every other schedule in this app.
 */
@Component
public class TaskBotScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskBotScheduler.class);
    private final TaskBotService bot;

    public TaskBotScheduler(TaskBotService bot) {
        this.bot = bot;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    @SchedulerLock(name = "TaskBot_poll", lockAtLeastFor = "PT2S", lockAtMostFor = "PT1M")
    public void poll() {
        if (!bot.enabled()) return;
        try {
            bot.poll();
        } catch (Exception e) {
            log.warn("Task bot poll failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "TaskBot_morningDigest", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void morningDigest() {
        if (!bot.enabled()) return;
        try {
            bot.sendMorningDigest();
        } catch (Exception e) {
            log.warn("Task bot morning digest failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "TaskBot_eveningOverdue", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void eveningOverdue() {
        if (!bot.enabled()) return;
        try {
            bot.sendEveningOverdue();
        } catch (Exception e) {
            log.warn("Task bot overdue check failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 19 * * SUN", zone = "America/Los_Angeles")
    @SchedulerLock(name = "TaskBot_weeklySummary", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void weeklySummary() {
        if (!bot.enabled()) return;
        try {
            bot.sendWeeklySummary();
        } catch (Exception e) {
            log.warn("Task bot weekly summary failed: {}", e.getMessage());
        }
    }
}
