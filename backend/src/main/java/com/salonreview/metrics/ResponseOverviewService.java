package com.salonreview.metrics;

import com.salonreview.domain.AppUser;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.metrics.ResponseTimes.Stats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One report across every business the viewer may see: text response speed plus first-touch speed on brand-new leads. */
@Service
public class ResponseOverviewService {

    /** A lead that nobody has touched for longer than this is history, not a lead we can still save. */
    static final Duration PENDING_WINDOW = Duration.ofDays(14);
    private static final String LEADS_SQL = """
            WITH keyed AS (
              SELECT l.id, l.created_at, l.status, l.channel, l.phone_number,
                     regexp_replace(COALESCE(l.phone_number, ''), '\\D', '', 'g') AS digits, %s AS k
              FROM lead_intake l WHERE l.business_id = ? AND l.outcome <> 'DUPLICATE'
            ), rep AS (SELECT DISTINCT ON (k) * FROM keyed ORDER BY k, created_at, id)
            SELECT r.id, r.phone_number, r.created_at, r.status, t.at, t.uid, t.uname
            FROM rep r
            LEFT JOIN LATERAL (
              SELECT x.at, x.uid, x.uname FROM (
                SELECT m.created_at AS at, m.sent_by_user_id AS uid, NULL::text AS uname FROM sms_message m
                WHERE m.business_id = ? AND m.direction = 'OUTBOUND' AND m.automation_key IS NULL AND m.template_key IS NULL
                  AND m.status = 'SENT' AND length(r.digits) >= 7 AND m.created_at >= r.created_at
                  AND right(regexp_replace(COALESCE(m.phone_number, ''), '\\D', '', 'g'), 10) = right(r.digits, 10)
                UNION ALL
                SELECT e.created_at, NULL::bigint, e.created_by FROM lead_events e
                WHERE e.lead_id = r.id AND e.created_at >= r.created_at AND e.created_by IS NOT NULL AND e.created_by NOT IN ('System', 'Square')
                  AND (e.type IN ('ATTEMPT', 'REACHED_OUT', 'CALL') OR (e.type = 'STATUS' AND e.text = 'CONTACTED'))
              ) x ORDER BY x.at LIMIT 1
            ) t ON TRUE
            WHERE r.channel <> 'Square' AND r.created_at >= ?
            """;

    private final JdbcTemplate jdbc;
    private final ManagerMetricsService texts;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ResponseOverviewService(JdbcTemplate jdbc, ManagerMetricsService texts) {
        this(jdbc, texts, Clock.systemUTC());
    }

    ResponseOverviewService(JdbcTemplate jdbc, ManagerMetricsService texts, Clock clock) {
        this.jdbc = jdbc;
        this.texts = texts;
        this.clock = clock;
    }

    public record SlowLead(String phoneLast4, Instant createdAt, Instant touchedAt, long seconds) {}

    public record WaitingLead(long leadId, String phoneLast4, String phone, String name, String channel, Instant createdAt, long seconds) {}

    public record LeadManager(long userId, String username, int touches, Long medianSeconds, Integer within5Percent) {}

    public record NewLeads(int created, Stats firstTouch, int pending, Long oldestPendingSeconds, int closedWithoutContact,
                           int offShiftTouches, List<LeadManager> managers, List<SlowLead> slowest, List<WaitingLead> waiting) {}

    public record BusinessReport(long businessId, String name, ManagerMetricsService.Leaderboard texts, NewLeads newLeads) {}

    public record Total(Stats texts, int unansweredNow, Long oldestUnansweredSeconds, Stats firstTouch, int pendingLeads, Long oldestPendingSeconds) {}

    public record Overview(String period, Instant from, Instant to, List<BusinessReport> businesses, Total total) {}

    public record Biz(long id, String name) {}

    /** Active businesses this user may see: every one for a platform admin, otherwise their memberships. */
    public List<Biz> visibleBusinesses(long userId) {
        return jdbc.query("""
                SELECT b.id, b.name FROM business b WHERE b.active AND (
                  EXISTS (SELECT 1 FROM platform_admin pa WHERE pa.user_id = ?)
                  OR EXISTS (SELECT 1 FROM business_membership bm WHERE bm.business_id = b.id AND bm.user_id = ?))
                ORDER BY b.id
                """, (rs, i) -> new Biz(rs.getLong(1), rs.getString(2)), userId, userId);
    }

    public Overview overview(long userId, String period) {
        Instant now = clock.instant();
        List<BusinessReport> reports = new ArrayList<>();
        List<Long> allTexts = new ArrayList<>(), allTouches = new ArrayList<>();
        int unanswered = 0, pending = 0;
        Long oldestUnanswered = null, oldestPending = null;
        Instant earliest = now;
        for (Biz b : visibleBusinesses(userId)) {
            ManagerMetricsService.Board board = texts.board(b.id(), period);
            Instant from = board.leaderboard().from();
            if (from.isBefore(earliest)) earliest = from;
            Computed c = newLeads(b.id(), from, now);
            reports.add(new BusinessReport(b.id(), b.name(), board.leaderboard(), c.summary()));
            allTexts.addAll(board.teamSeconds());
            allTouches.addAll(c.seconds());
            unanswered += board.leaderboard().unansweredNow();
            oldestUnanswered = max(oldestUnanswered, board.leaderboard().oldestUnansweredSeconds());
            pending += c.summary().pending();
            oldestPending = max(oldestPending, c.summary().oldestPendingSeconds());
        }
        return new Overview(period, earliest, now, reports, new Total(ResponseTimes.stats(allTexts), unanswered, oldestUnanswered,
                ResponseTimes.stats(allTouches), pending, oldestPending));
    }

    private static Long max(Long a, Long b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private record Computed(NewLeads summary, List<Long> seconds) {}

    private Computed newLeads(long businessId, Instant from, Instant now) {
        Instant queryFrom = from.isBefore(now.minus(PENDING_WINDOW)) ? from : now.minus(PENDING_WINDOW);
        List<LeadFirstTouch.Lead> leads = jdbc.query(LEADS_SQL.formatted(LeadFollowUpService.personKey("l")), (rs, i) -> {
            Timestamp touch = rs.getTimestamp(5);
            return new LeadFirstTouch.Lead(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4),
                    touch == null ? null : touch.toInstant(), rs.getObject(6) != null ? Long.valueOf(rs.getLong(6)) : usernameId(rs.getString(7)));
        }, businessId, businessId, Timestamp.from(queryFrom));
        List<com.salonreview.metrics.ResponseTimes.Shift> shifts = texts.shifts(businessId, queryFrom, now);
        LeadFirstTouch.Result r = LeadFirstTouch.compute(leads, shifts, now);

        List<LeadFirstTouch.Touched> inPeriod = r.touched().stream().filter(t -> !t.createdAt().isBefore(from)).toList();
        List<LeadFirstTouch.Touched> measured = inPeriod.stream().filter(LeadFirstTouch.Touched::onShift).toList();
        List<Long> seconds = measured.stream().map(LeadFirstTouch.Touched::effectiveSeconds).toList();
        Map<Long, String> names = new HashMap<>();
        for (AppUser m : texts.managersOf(businessId)) names.put(m.getId(), m.getUsername());
        List<LeadManager> managers = new ArrayList<>();
        for (var e : names.entrySet()) {
            List<Long> mine = measured.stream().filter(t -> e.getKey().equals(t.userId())).map(LeadFirstTouch.Touched::effectiveSeconds).toList();
            if (mine.isEmpty()) continue;
            Stats st = ResponseTimes.stats(mine);
            managers.add(new LeadManager(e.getKey(), e.getValue(), st.replies(), st.medianSeconds(), st.within5Percent()));
        }
        managers.sort(Comparator.comparing(LeadManager::username));
        List<LeadFirstTouch.Pending> waiting = r.pending().stream().filter(p -> p.createdAt().isAfter(now.minus(PENDING_WINDOW)))
                .sorted(Comparator.comparingLong(LeadFirstTouch.Pending::effectiveSeconds).reversed()).toList();
        int created = (int) leads.stream().filter(l -> !l.createdAt().isBefore(from)).count();
        int closedWithout = (int) leads.stream().filter(l -> !l.createdAt().isBefore(from) && l.touchAt() == null && !"NEW".equals(l.status())).count();
        NewLeads summary = new NewLeads(created, ResponseTimes.stats(seconds), waiting.size(),
                waiting.stream().map(LeadFirstTouch.Pending::effectiveSeconds).max(Long::compare).orElse(null), closedWithout,
                (int) inPeriod.stream().filter(t -> !t.onShift()).count(), managers,
                measured.stream().sorted(Comparator.comparingLong(LeadFirstTouch.Touched::effectiveSeconds).reversed()).limit(3)
                        .map(t -> new SlowLead(ManagerMetricsService.last4(t.phone()), t.createdAt(), t.touchAt(), t.effectiveSeconds())).toList(),
                waiting.stream().limit(5).map(p -> waitingLead(businessId, p)).toList());
        return new Computed(summary, seconds);
    }

    private WaitingLead waitingLead(long businessId, LeadFirstTouch.Pending p) {
        String[] info = jdbc.query("SELECT name, channel FROM lead_intake WHERE id = ? AND business_id = ?",
                rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : new String[]{null, null}, p.leadId(), businessId);
        String name = info[0] == null || info[0].isBlank() ? null : info[0].trim();
        return new WaitingLead(p.leadId(), ManagerMetricsService.last4(p.phone()), p.phone(), name, info[1], p.createdAt(), p.effectiveSeconds());
    }

    private final Map<String, Long> idsByName = new java.util.concurrent.ConcurrentHashMap<>();

    private Long usernameId(String username) {
        if (username == null) return null;
        return idsByName.computeIfAbsent(username, u -> jdbc.query("SELECT id FROM app_user WHERE username = ?", rs -> rs.next() ? rs.getLong(1) : null, u));
    }
}
