package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tells the owner (Telegram owner chat) when an SMS silently did not go out: Twilio rejected it,
 * the call timed out, or the carrier later reported undelivered/failed. Deduped per business +
 * template + error code so a bad template or a Twilio outage produces one alert per window, with
 * the number of suppressed repeats appended to the next one. Never throws. */
@Service
public class SmsFailureAlertService {

    private static final Logger log = LoggerFactory.getLogger(SmsFailureAlertService.class);
    static final Duration WINDOW = Duration.ofMinutes(30);

    private final TelegramNotificationService telegram;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private final Map<String, Integer> suppressed = new ConcurrentHashMap<>();

    public SmsFailureAlertService(TelegramNotificationService telegram) {
        this.telegram = telegram;
    }

    public void alert(SmsMessage m, String code, String text, String kind) {
        try {
            if (m == null || m.getBusinessId() == null) return;
            String source = m.getSentByUserId() != null ? "manual" : label(m.getAutomationKey(), m.getTemplateKey());
            String key = m.getBusinessId() + "|" + source + "|" + code + "|" + kind;
            Instant now = Instant.now();
            Instant prev = lastSent.get(key);
            if (prev != null && Duration.between(prev, now).compareTo(WINDOW) < 0) {
                suppressed.merge(key, 1, Integer::sum);
                return;
            }
            lastSent.put(key, now);
            int extra = suppressed.getOrDefault(key, 0);
            suppressed.remove(key);
            String phone = m.getPhoneNumber() == null ? "" : m.getPhoneNumber();
            String last4 = phone.length() >= 4 ? phone.substring(phone.length() - 4) : phone;
            StringBuilder sb = new StringBuilder("SMS NOT DELIVERED (").append(kind).append(")\n")
                    .append("Source: ").append(source).append('\n')
                    .append("Phone: ...").append(last4).append('\n')
                    .append("Error: ").append(code == null ? "unknown" : code).append(" ").append(text == null ? "" : text);
            if (extra > 0) sb.append("\n(+").append(extra).append(" similar in the last 30 min)");
            telegram.sendOwnerAlert(m.getBusinessId(), sb.toString());
        } catch (Exception e) {
            log.warn("SMS failure alert could not be sent: {}", e.getMessage());
        }
    }

    private static String label(String automationKey, String templateKey) {
        if (automationKey != null) return automationKey;
        return templateKey != null ? templateKey : "other";
    }
}
