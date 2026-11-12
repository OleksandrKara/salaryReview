package com.salonreview.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The "needs a reply" queue: one definition used by the CRM tab, the menu badge and the Telegram escalation.
 *
 * <p>A chat is in the queue when its client wrote something that is not STOP/START/HELP and not a thank-you,
 * and no human has answered since, whatever the lead status (or no lead at all). A question or request stays
 * until a person answers (CRM text, call, attempt, "reached out") or the lead is closed as LOST/SPAM after it.
 * A plain statement ("running late") also leaves once staff opened the chat. An automated text never clears
 * it; the row says so instead. Waits older than {@link #MAX_AGE} drop out of the queue (they stay in the metric).
 */
@Service
public class NeedsReplyService {

    public static final Duration MAX_AGE = Duration.ofDays(7);

    public record Item(String phone, String phoneLast4, Instant since, long waitSeconds, boolean ask, String name, Long leadId,
                       String leadStatus, String preview, String automationKey, Instant automationAt) {}

    public record Queue(int count, Long oldestSeconds, List<Item> items) {}

    private final ManagerMetricsService metrics;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public NeedsReplyService(ManagerMetricsService metrics, JdbcTemplate jdbc) {
        this(metrics, jdbc, Clock.systemUTC());
    }

    NeedsReplyService(ManagerMetricsService metrics, JdbcTemplate jdbc, Clock clock) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Queue queue(long businessId) {
        Instant now = clock.instant();
        Instant oldestAllowed = now.minus(MAX_AGE);
        var data = metrics.load(businessId, oldestAllowed, now);
        List<Item> items = metrics.needsReplyNow(businessId, data.result().unanswered()).stream()
                .filter(u -> !u.since().isBefore(oldestAllowed))
                .sorted(Comparator.comparingLong(ResponseTimes.Unanswered::effectiveSeconds).reversed()
                        .thenComparing(ResponseTimes.Unanswered::since))
                .map(u -> item(businessId, u))
                .toList();
        return new Queue(items.size(), items.stream().map(Item::waitSeconds).max(Long::compare).orElse(null), items);
    }

    private Item item(long businessId, ResponseTimes.Unanswered u) {
        var w = metrics.waitingNow(businessId, u);
        return new Item(u.phone(), w.phoneLast4(), u.since(), u.effectiveSeconds(), u.ask(), w.name(), w.leadId(), w.leadStatus(),
                w.preview(), w.lastAutoKey(), w.lastAutoAt());
    }
}
