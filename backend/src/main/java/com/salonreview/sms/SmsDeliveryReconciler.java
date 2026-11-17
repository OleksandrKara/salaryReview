package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsMessageRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Catches outbound messages Twilio accepted but whose delivery status never moved on (the status
 * callback was lost or the message is stuck queued). Looks at rows 30 min to 24 h old, asks Twilio
 * for the current state and applies it; if Twilio still says queued/sending the owner is alerted
 * once. Each row is handled at most once ({@code stuck_alerted_at}). Interval based, no cron zone. */
@Component
public class SmsDeliveryReconciler {

    private static final Logger log = LoggerFactory.getLogger(SmsDeliveryReconciler.class);
    static final Duration MIN_AGE = Duration.ofMinutes(30);
    static final Duration MAX_AGE = Duration.ofHours(24);
    private static final int BATCH = 50;

    private final SmsMessageRepository repository;
    private final SmsMessageLogService logService;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final SmsFailureAlertService alerts;

    public SmsDeliveryReconciler(SmsMessageRepository repository, SmsMessageLogService logService,
                                 TwilioSmsConfigService configService, TwilioSmsClient client,
                                 SmsFailureAlertService alerts) {
        this.repository = repository;
        this.logService = logService;
        this.configService = configService;
        this.client = client;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    @SchedulerLock(name = "SmsDeliveryReconciler_reconcile", lockAtLeastFor = "PT30S", lockAtMostFor = "PT9M")
    public void reconcile() {
        try {
            run(Instant.now());
        } catch (Exception e) {
            log.warn("SMS delivery reconciler failed: {}", e.getMessage());
        }
    }

    int run(Instant now) {
        List<SmsMessage> stuck = repository.findStuckDeliveries(now.minus(MIN_AGE), now.minus(MAX_AGE), PageRequest.of(0, BATCH));
        int handled = 0;
        for (SmsMessage m : stuck) {
            try {
                TwilioSmsConfig config = configService.get(m.getBusinessId());
                if (!config.isConfigured()) continue;
                TwilioSmsClient.MessageStatus st = client.fetchStatus(config, m.getTwilioMessageSid());
                String status = st == null ? null : st.status();
                if (status != null && !List.of("queued", "sending", "accepted").contains(status)) {
                    logService.updateDeliveryStatus(m.getTwilioMessageSid(), status,
                            st.error_code() == null ? null : String.valueOf(st.error_code()));
                } else {
                    alerts.alert(m, "stuck", "Twilio still reports '" + status + "' after 30+ min", "stuck");
                }
                markHandled(m.getId());
                handled++;
            } catch (Exception e) {
                log.warn("SMS reconcile of message {} failed: {}", m.getId(), e.getMessage());
            }
        }
        return handled;
    }

    private void markHandled(Long id) {
        repository.findById(id).ifPresent(m -> {
            m.setStuckAlertedAt(Instant.now());
            repository.save(m);
        });
    }
}
