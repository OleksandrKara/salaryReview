package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.util.PhoneNumbers;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Safety net: an inbound text whose Telegram alert was never confirmed (handler error, Telegram
 * timeout, restart mid-request) is re-sent here, so staff never miss a customer message. */
@Component
public class InboundSmsAlertSweeper {
    private static final Logger log = LoggerFactory.getLogger(InboundSmsAlertSweeper.class);
    private final SmsMessageRepository messages;
    private final SmsMessageLogService messageLog;
    private final BlockedNumberRepository blocked;
    private final TelegramNotificationService telegram;

    public InboundSmsAlertSweeper(SmsMessageRepository messages, SmsMessageLogService messageLog,
                                  BlockedNumberRepository blocked, TelegramNotificationService telegram) {
        this.messages = messages;
        this.messageLog = messageLog;
        this.blocked = blocked;
        this.telegram = telegram;
    }

    @Scheduled(cron = "15 * * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "InboundSmsAlertSweeper_sweep", lockAtLeastFor = "PT20S", lockAtMostFor = "PT2M")
    public void sweep() {
        Instant now = Instant.now();
        List<SmsMessage> missed = messages.findUnalertedInbound(now.minus(Duration.ofSeconds(90)), now.minus(Duration.ofHours(24)));
        for (SmsMessage m : missed) {
            try {
                if (blocked.existsById(PhoneNumbers.normalize(m.getPhoneNumber()))) {
                    messageLog.markTelegramAlerted(m.getId());
                    continue;
                }
                String body = "(delayed alert, the first one did not go through)\n" + m.getBody();
                if (telegram.sendInboundSmsAlert(m.getBusinessId(), m.getPhoneNumber(), null, body, m.getAutomationKey())) {
                    messageLog.markTelegramAlerted(m.getId());
                    log.warn("Inbound SMS {} had no Telegram alert, re-sent by sweeper", m.getId());
                }
            } catch (RuntimeException e) {
                log.warn("Sweeper could not alert inbound SMS {}: {}", m.getId(), e.toString());
            }
        }
    }
}
