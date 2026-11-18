package com.salonreview.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.SmsAutomationRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TelegramAutomationLogTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private TelegramAutomationLog log;

    @Test
    void rowsCountSentAndFailedPerLabelAndDirection() {
        long b = businesses.save(Business.builder().name("Log").shortCode("lg-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        log.record(b, "needs_reply_alert", "WAIT_15_MIN", TelegramAutomationLog.NAILS, true);
        log.record(b, "needs_reply_alert", "WAIT_15_MIN", TelegramAutomationLog.NAILS, true);
        log.record(b, "needs_reply_alert", "WAIT_15_MIN", TelegramAutomationLog.NAILS, false);
        log.record(b, "needs_reply_alert", "SHIFT_START", TelegramAutomationLog.ACADEMY, true);

        var rows = log.rows(b, "needs_reply_alert", Instant.now());
        assertThat(rows).hasSize(2);
        var wait = rows.stream().filter(r -> r.label().equals("WAIT_15_MIN")).findFirst().orElseThrow();
        assertThat(wait.sent30()).isEqualTo(2);
        assertThat(wait.failed30()).isEqualTo(1);
        assertThat(wait.lastSentAt()).isNotNull();
        assertThat(wait.lastFailedAt()).isNotNull();
        assertThat(log.rows(b + 1000, "needs_reply_alert", Instant.now())).isEmpty();
    }

    @Test
    void everyAlwaysOnTelegramAutomationIsRegisteredAndLogged() {
        for (String key : SmsAutomationRegistry.LOGGED) {
            assertThat(SmsAutomationRegistry.all()).containsKey(key);
            assertThat(SmsAutomationRegistry.all().get(key).channel()).isEqualTo(SmsAutomationRegistry.Channel.TELEGRAM);
        }
    }
}
