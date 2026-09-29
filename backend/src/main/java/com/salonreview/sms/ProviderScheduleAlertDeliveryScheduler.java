package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.telegram.ProviderScheduleTelegramSender;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Claims durable notifications before sending; never holds a DB transaction over Telegram. */
@Component
public class ProviderScheduleAlertDeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ProviderScheduleAlertDeliveryScheduler.class);
    private final BusinessRepository businesses;
    private final SmsAutomationService automations;
    private final ProviderScheduleClosureAlertConfigService config;
    private final ProviderScheduleChangeStore store;
    private final ProviderScheduleTelegramSender telegram;
    private final CurrentBusinessContext context;
    private final Clock clock;

    @Autowired
    public ProviderScheduleAlertDeliveryScheduler(BusinessRepository businesses, SmsAutomationService automations,
            ProviderScheduleClosureAlertConfigService config, ProviderScheduleChangeStore store,
            ProviderScheduleTelegramSender telegram, CurrentBusinessContext context) {
        this(businesses, automations, config, store, telegram, context, Clock.systemUTC());
    }

    ProviderScheduleAlertDeliveryScheduler(BusinessRepository businesses, SmsAutomationService automations,
            ProviderScheduleClosureAlertConfigService config, ProviderScheduleChangeStore store,
            ProviderScheduleTelegramSender telegram, CurrentBusinessContext context, Clock clock) {
        this.businesses = businesses;
        this.automations = automations;
        this.config = config;
        this.store = store;
        this.telegram = telegram;
        this.context = context;
        this.clock = clock;
    }

    @Scheduled(cron = "30 * * * * *", zone = "UTC")
    @SchedulerLock(name = "ProviderScheduleAlertDeliveryScheduler_send", lockAtLeastFor = "PT20S", lockAtMostFor = "PT2M")
    public void sendPending() {
        for (var business : businesses.findAllByActiveTrue()) context.runAs(business.getId(), () -> {
            try {
                sendBusiness(business.getId());
            } catch (RuntimeException exception) {
                log.warn("Availability notification dispatch failed for business {} ({})", business.getId(), exception.getClass().getSimpleName());
            }
        });
    }

    private void sendBusiness(Long businessId) {
            Instant now = Instant.now(clock);
            store.recoverAmbiguousAttempts(businessId, now);
            if (!automations.isEnabled(businessId, ProviderScheduleClosureAlertScheduler.AUTOMATION_KEY)) return;
            var policy = config.getSettings(businessId);
            if (policy.observationOnly()) return;
            for (var alert : store.pendingAlerts(businessId, now)) {
                if (alert.noticeThresholdHours() != policy.noticeThresholdHours()
                        || alert.minimumLossWindowMinutes() != policy.minimumLossWindowMinutes()) continue;
                if (!store.claim(businessId, alert.id(), Instant.now(clock))) continue;
                var result = telegram.send(businessId, alert.providerName(), alert.firstStart(), alert.lastStart(),
                        alert.noticeThresholdHours(), alert.timezone());
                store.delivered(businessId, alert.id(), result.status(), result.messageId(), Instant.now(clock));
            }
    }
}
