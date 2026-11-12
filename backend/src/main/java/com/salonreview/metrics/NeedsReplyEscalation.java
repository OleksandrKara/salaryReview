package com.salonreview.metrics;

import com.salonreview.telegram.TelegramNotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram escalation for the needs-reply queue. Wait time counts only while someone is on shift, so nothing fires
 * overnight. After {@link #MANAGER_AFTER} on shift the staff chat is told once; after {@link #OWNER_AFTER} the owner chat
 * is told once. When a shift starts, one digest says how many chats are waiting. Nothing is ever sent to clients,
 * and only waits that began after the escalation went live are alerted one by one.
 */
@Component
public class NeedsReplyEscalation {
    private static final Logger log = LoggerFactory.getLogger(NeedsReplyEscalation.class);

    static final Duration MANAGER_AFTER = Duration.ofMinutes(15);
    static final Duration OWNER_AFTER = Duration.ofMinutes(30);
    private static final Duration SHIFT_START_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_LINES = 8;

    private final NeedsReplyService queue;
    private final TelegramNotificationService telegram;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String publicBaseUrl;

    @Autowired
    public NeedsReplyEscalation(NeedsReplyService queue, TelegramNotificationService telegram, JdbcTemplate jdbc,
                                @Value("${app.public-base-url}") String publicBaseUrl) {
        this(queue, telegram, jdbc, publicBaseUrl, Clock.systemUTC());
    }

    NeedsReplyEscalation(NeedsReplyService queue, TelegramNotificationService telegram, JdbcTemplate jdbc, String publicBaseUrl, Clock clock) {
        this.queue = queue;
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.publicBaseUrl = publicBaseUrl;
        this.clock = clock;
    }

    @Scheduled(cron = "30 * * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "NeedsReplyEscalation_run", lockAtLeastFor = "PT20S", lockAtMostFor = "PT2M")
    public void run() {
        for (long businessId : jdbc.queryForList("SELECT id FROM business WHERE active", Long.class)) {
            try {
                process(businessId);
            } catch (RuntimeException e) {
                log.warn("Needs-reply escalation failed for business {}: {}", businessId, e.toString());
            }
        }
    }

    void process(long businessId) {
        Instant now = clock.instant();
        NeedsReplyService.Queue q = queue.queue(businessId);
        if (q.count() == 0) return;
        Instant live = jdbc.queryForObject("SELECT started_at FROM needs_reply_escalation_start WHERE id = 1", Timestamp.class).toInstant();
        String url = publicBaseUrl + "/admin/leads?business=" + businessId + "&tab=needs-reply";

        List<NeedsReplyService.Item> forManager = new ArrayList<>(), forOwner = new ArrayList<>();
        for (NeedsReplyService.Item it : q.items()) {
            if (it.since().isBefore(live)) continue;
            Duration wait = Duration.ofSeconds(it.waitSeconds());
            if (wait.compareTo(MANAGER_AFTER) >= 0 && claim(businessId, "MANAGER", key(it))) forManager.add(it);
            if (wait.compareTo(OWNER_AFTER) >= 0 && claim(businessId, "OWNER", key(it))) forOwner.add(it);
        }
        if (!forManager.isEmpty()) {
            String text = "Клиенты ждут ответа на смене дольше 15 минут: " + forManager.size() + "\n" + lines(forManager)
                    + "\nОтветьте или откройте чат.";
            if (!telegram.sendPlainAlert(businessId, text, "Нужен ответ", url)) forManager.forEach(it -> release(businessId, "MANAGER", key(it)));
        }
        if (!forOwner.isEmpty()) {
            String text = "Без ответа уже больше 30 минут на смене: " + forOwner.size() + "\n" + lines(forOwner) + "\n" + url;
            if (!telegram.sendOwnerAlert(businessId, text)) forOwner.forEach(it -> release(businessId, "OWNER", key(it)));
        }
        shiftStartDigest(businessId, now, q, url);
    }

    /** One message per shift: waits that were pending when the shift began (their clock was paused overnight). */
    private void shiftStartDigest(long businessId, Instant now, NeedsReplyService.Queue q, String url) {
        List<Long> started = jdbc.queryForList("""
                SELECT e.id FROM manager_time_entry e JOIN app_user u ON u.id = e.user_id
                WHERE """ + ManagerMetricsService.MEMBER_SQL + """
                 AND e.end_at IS NULL AND e.start_at >= ? AND e.start_at <= ? ORDER BY e.start_at LIMIT 1
                """, Long.class, businessId, businessId, Timestamp.from(now.minus(SHIFT_START_WINDOW)), Timestamp.from(now));
        if (started.isEmpty()) return;
        String ref = "shift:" + started.get(0);
        if (!claim(businessId, "DIGEST", ref)) return;
        String text = "Начало смены: ждут ответа чатов " + q.count() + ".\n" + lines(q.items());
        if (!telegram.sendPlainAlert(businessId, text, "Нужен ответ", url)) release(businessId, "DIGEST", ref);
    }

    private static String key(NeedsReplyService.Item it) {
        return it.phone() + "|" + it.since().getEpochSecond();
    }

    private static String lines(List<NeedsReplyService.Item> items) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (NeedsReplyService.Item it : items) {
            if (n++ == MAX_LINES) {
                sb.append("и ещё ").append(items.size() - MAX_LINES).append("\n");
                break;
            }
            sb.append("···").append(it.phoneLast4()).append(" · ").append(Math.max(1, it.waitSeconds() / 60)).append(" мин")
                    .append(it.ask() ? " · вопрос" : "").append("\n");
        }
        return sb.toString();
    }

    private boolean claim(long businessId, String kind, String ref) {
        return jdbc.update("INSERT INTO needs_reply_notice (business_id, kind, ref_key) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                businessId, kind, ref) == 1;
    }

    private void release(long businessId, String kind, String ref) {
        jdbc.update("DELETE FROM needs_reply_notice WHERE business_id = ? AND kind = ? AND ref_key = ?", businessId, kind, ref);
    }
}
