package com.salonreview.square;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Confirms the scheduler's cron strings are valid and fire at the documented times, and that each
 * registered task is wrapped in a distributed lock (see class doc) rather than calling the
 * service directly — otherwise both backend replicas (blue/green) would double-capture the same
 * day's/month's snapshot. Doesn't run a real timer — that would require a live Spring context plus
 * a clock fast-forward.
 */
class RevenueSnapshotSchedulerTest {

    private RevenueSnapshotService service;
    private SquareClient square;
    private LockingTaskExecutor lockingTaskExecutor;
    private RevenueSnapshotScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(RevenueSnapshotService.class);
        square = mock(SquareClient.class);
        lockingTaskExecutor = mock(LockingTaskExecutor.class);
        when(square.locationTimeZone()).thenReturn("UTC");
        scheduler = new RevenueSnapshotScheduler(service, square, lockingTaskExecutor);
    }

    @Test
    void dailyCaptureCron_fires_at_0130_every_day() {
        CronExpression cron = CronExpression.parse(RevenueSnapshotScheduler.DAILY_CAPTURE_CRON);
        LocalDateTime base = LocalDateTime.of(2026, 6, 10, 0, 0);
        LocalDateTime next = cron.next(base);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 6, 10, 1, 30));
        // Following day, same time
        assertThat(cron.next(next)).isEqualTo(LocalDateTime.of(2026, 6, 11, 1, 30));
    }

    @Test
    void monthlyActualFillCron_fires_at_0200_on_day_1_of_each_month() {
        CronExpression cron = CronExpression.parse(RevenueSnapshotScheduler.MONTHLY_ACTUAL_FILL_CRON);
        LocalDateTime base = LocalDateTime.of(2026, 6, 15, 0, 0);
        LocalDateTime next = cron.next(base);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 1, 2, 0));
        // Following month
        assertThat(cron.next(next)).isEqualTo(LocalDateTime.of(2026, 8, 1, 2, 0));
    }

    @Test
    void bothCronTasks_goThroughLockingTaskExecutor_notTheServiceDirectly() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);

        List<CronTask> tasks = registrar.getCronTaskList();
        assertThat(tasks).hasSize(2);

        for (CronTask task : tasks) {
            task.getRunnable().run();
        }

        verify(lockingTaskExecutor, times(2)).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
        verifyNoInteractions(service);
    }

    @Test
    void dailyCaptureTask_actuallyCapturesYesterday_onceLockIsAcquired() {
        // Simulate the lock being acquired: run the given Runnable through.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any(LockConfiguration.class));

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        registrar.getCronTaskList().get(0).getRunnable().run();

        verify(service).captureFor(any());
    }

    @Test
    void monthlyActualFillTask_actuallyFillsPriorMonth_onceLockIsAcquired() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any(LockConfiguration.class));

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        registrar.getCronTaskList().get(1).getRunnable().run();

        verify(service).fillMonthEndActualsFor(any(YearMonth.class));
    }
}
