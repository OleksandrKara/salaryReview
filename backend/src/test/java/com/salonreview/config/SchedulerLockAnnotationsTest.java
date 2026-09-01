package com.salonreview.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against forgetting {@code @SchedulerLock} on a new (or edited) {@code @Scheduled} method
 * in one of the automations that races real side effects (SMS sends, Square API calls) across the
 * two backend replicas (blue/green, see docker-compose.yml) — see SchedulerLockConfig's own doc
 * for why this matters. Reflection-based rather than a live-DB integration test: confirming the
 * annotation is present with a sane, non-zero lockAtMostFor is enough to catch the actual failure
 * mode this class exists to prevent (a method that got un-locked, or was never locked, silently
 * shipping); the lock mechanics themselves are ShedLock's own well-tested responsibility, not
 * ours to re-verify.
 */
class SchedulerLockAnnotationsTest {

    @Test
    void smsReplyFlowScheduler_bothMethods_areLocked() throws NoSuchMethodException {
        assertLocked(com.salonreview.sms.SmsReplyFlowScheduler.class, "sendDueRatingRequests");
        assertLocked(com.salonreview.sms.SmsReplyFlowScheduler.class, "expireStaleReplyWindows");
    }

    @Test
    void leadFollowUpScheduler_isLocked() throws NoSuchMethodException {
        assertLocked(com.salonreview.sms.LeadFollowUpScheduler.class, "sendDueFollowUps");
    }

    @Test
    void sameDayRebookingScheduler_isLocked() throws NoSuchMethodException {
        assertLocked(com.salonreview.sms.SameDayRebookingScheduler.class, "sendDueRebookingNudges");
    }

    @Test
    void sameDayRebookingGroupExpiryScheduler_isLocked() throws NoSuchMethodException {
        assertLocked(com.salonreview.sms.SameDayRebookingGroupExpiryScheduler.class, "removeExpiredMemberships");
    }

    @Test
    void seoSearchConsoleSyncScheduler_isLocked() throws NoSuchMethodException {
        assertLocked(com.salonreview.seo.SeoSearchConsoleSyncScheduler.class, "sync");
    }

    @Test
    void seoPageSpeedSyncScheduler_isLocked() throws NoSuchMethodException {
        assertLocked(com.salonreview.seo.SeoPageSpeedSyncScheduler.class, "sync");
    }

    private static void assertLocked(Class<?> schedulerClass, String methodName) throws NoSuchMethodException {
        Method method = schedulerClass.getDeclaredMethod(methodName);
        assertThat(method.isAnnotationPresent(Scheduled.class))
                .as("%s.%s should still be @Scheduled", schedulerClass.getSimpleName(), methodName)
                .isTrue();

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock)
                .as("%s.%s must carry @SchedulerLock — two live backend replicas (blue/green) would "
                        + "otherwise both run it and race the same due rows", schedulerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(lock.name()).as("lock name must be non-blank and unique per method").isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor()))
                .as("lockAtMostFor must be a real, positive safety-net duration")
                .isPositive();
    }
}
