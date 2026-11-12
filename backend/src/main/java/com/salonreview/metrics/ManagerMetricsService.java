package com.salonreview.metrics;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.metrics.ResponseTimes.Attribution;
import com.salonreview.metrics.ResponseTimes.Msg;
import com.salonreview.metrics.ResponseTimes.Reply;
import com.salonreview.metrics.ResponseTimes.Shift;
import com.salonreview.repo.AppUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manager response-speed ranking and the live shift summary. Rules live in {@link ResponseTimes}. */
@Service
public class ManagerMetricsService {

    static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    /** Look back this far for a text that was still waiting when the period began. */
    private static final Duration LOOKBACK = Duration.ofDays(3);
    /** An open shift older than this is a forgotten clock-out; it stops counting as coverage. */
    private static final Duration MAX_OPEN_SHIFT = Duration.ofHours(14);
    private static final Set<String> KEYWORDS = Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT",
            "START", "UNSTOP", "YES", "HELP", "INFO");
    private static final String PROCESSED_TYPES = "'STATUS','ATTEMPT','REACHED_OUT','NOTE','CALL','AGREEMENT','CALLBACK','LOST_REASON','SOURCE'";

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final LeadFollowUpService leads;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ManagerMetricsService(JdbcTemplate jdbc, AppUserRepository users, LeadFollowUpService leads) {
        this(jdbc, users, leads, Clock.systemUTC());
    }

    ManagerMetricsService(JdbcTemplate jdbc, AppUserRepository users, LeadFollowUpService leads, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.leads = leads;
        this.clock = clock;
    }

    public record ManagerRow(long userId, String username, int replies, Long medianSeconds, Long averageSeconds,
                             Integer within5Percent, Integer within15Percent, int leadsProcessed, double shiftHours,
                             int inferredReplies, List<SlowReply> slowest) {}

    /** Phone is reduced to its last 4 digits: enough to find the thread, not enough to leak the number into a report. */
    public record SlowReply(String phoneLast4, Instant inboundAt, Instant replyAt, long seconds) {}

    public record WaitingNow(String phoneLast4, Instant since, long seconds) {}

    public record Leaderboard(String period, Instant from, Instant to, List<ManagerRow> managers, ResponseTimes.Stats team,
                              int unknownReplies, int offShiftReplies, int unansweredNow, Long oldestUnansweredSeconds,
                              List<WaitingNow> waiting) {}

    public record ShiftSummary(boolean onShift, Instant shiftStart, Instant shiftEnd, int leadsProcessed, int replies,
                               Long medianSeconds, Integer within5Percent, int newLeadsWaiting, Instant oldestNewLeadAt,
                               int unansweredCount, Long oldestUnansweredSeconds, Instant oldestUnansweredSince,
                               List<Comparison> comparisons) {}

    /** One manager against the team for a period. Rank counts only managers who answered at least once. */
    public record Comparison(String period, Long myMedianSeconds, Long teamMedianSeconds, Integer rank, int ranked) {}

    /** 30d and all-time scan a lot of texts, so repeated polls within this window reuse one result. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);
    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private record Cached(Instant at, Leaderboard board) {}

    public Leaderboard leaderboard(long businessId, String period) {
        if (!"30d".equals(period) && !"all".equals(period)) return computeLeaderboard(businessId, period);
        Instant now = clock.instant();
        String key = businessId + ":" + period;
        Cached hit = cache.get(key);
        if (hit != null && Duration.between(hit.at(), now).compareTo(CACHE_TTL) < 0) return hit.board();
        Leaderboard fresh = computeLeaderboard(businessId, period);
        cache.put(key, new Cached(now, fresh));
        return fresh;
    }

    /** Start of the first recorded shift in this business: before that nobody could be answering as a manager. */
    private Instant firstShiftStart(long businessId, Instant now) {
        java.sql.Timestamp first = jdbc.queryForObject(
                "SELECT min(e.start_at) FROM manager_time_entry e JOIN app_user u ON u.id = e.user_id WHERE u.business_id = ?",
                java.sql.Timestamp.class, businessId);
        return first == null ? now : first.toInstant();
    }

    private Leaderboard computeLeaderboard(long businessId, String period) {
        Instant now = clock.instant();
        Instant from = switch (period) {
            case "today" -> now.atZone(PT).toLocalDate().atStartOfDay(PT).toInstant();
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            case "all" -> firstShiftStart(businessId, now);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be today, 7d, 30d or all");
        };
        Data d = load(businessId, from, now);
        List<AppUser> managers = users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(businessId, List.of(Role.MANAGER));
        List<Reply> inWindow = d.result.replies().stream().filter(r -> !r.replyAt().isBefore(from)).toList();
        Map<String, Set<Long>> processed = processed(businessId, from, now, inWindow, managers);

        List<ManagerRow> rows = new ArrayList<>();
        for (AppUser m : managers) {
            List<Reply> mine = inWindow.stream().filter(r -> r.onShift() && m.getId().equals(r.userId())).toList();
            var st = ResponseTimes.stats(mine.stream().map(Reply::effectiveSeconds).toList());
            if (st.replies() == 0 && processed.getOrDefault(m.getUsername(), Set.of()).isEmpty()) continue;
            double hours = ResponseTimes.shiftSeconds(d.shifts, m.getId(), from, now, now) / 3600.0;
            rows.add(new ManagerRow(m.getId(), m.getUsername(), st.replies(), st.medianSeconds(), st.averageSeconds(),
                    st.within5Percent(), st.within15Percent(), processed.getOrDefault(m.getUsername(), Set.of()).size(),
                    Math.round(hours * 10) / 10.0, (int) mine.stream().filter(r -> r.attribution() == Attribution.INFERRED).count(),
                    mine.stream().sorted(java.util.Comparator.comparingLong(Reply::effectiveSeconds).reversed()).limit(3)
                            .map(r -> new SlowReply(last4(r.phone()), r.inboundAt(), r.replyAt(), r.effectiveSeconds())).toList()));
        }
        var team = ResponseTimes.stats(inWindow.stream().filter(Reply::onShift).map(Reply::effectiveSeconds).toList());
        int unknown = (int) inWindow.stream().filter(r -> r.onShift() && r.attribution() == Attribution.UNKNOWN).count();
        int off = (int) inWindow.stream().filter(r -> !r.onShift()).count();
        Long oldest = d.result.unanswered().stream().map(ResponseTimes.Unanswered::effectiveSeconds).max(Long::compare).orElse(null);
        List<WaitingNow> waiting = d.result.unanswered().stream()
                .sorted(java.util.Comparator.comparingLong(ResponseTimes.Unanswered::effectiveSeconds).reversed()).limit(5)
                .map(u -> new WaitingNow(last4(u.phone()), u.since(), u.effectiveSeconds())).toList();
        return new Leaderboard(period, from, now, rows, team, unknown, off, d.result.unanswered().size(), oldest, waiting);
    }

    public ShiftSummary shiftSummary(long businessId, long userId, String username) {
        Instant now = clock.instant();
        var last = jdbc.query("SELECT start_at, end_at FROM manager_time_entry WHERE user_id = ? ORDER BY start_at DESC LIMIT 1",
                (rs, i) -> new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()},
                userId);
        List<LeadFollowUpService.LeadView> fresh = leads.listFor(businessId, "NEW");
        Instant oldestNew = fresh.stream().map(l -> Instant.parse(l.createdAt())).min(Instant::compareTo).orElse(null);
        Data now0 = load(businessId, now.minus(Duration.ofHours(1)), now);
        var unanswered = now0.result.unanswered();
        Long oldestSec = unanswered.stream().map(ResponseTimes.Unanswered::effectiveSeconds).max(Long::compare).orElse(null);
        Instant oldestSince = unanswered.stream().max((a, b) -> Long.compare(a.effectiveSeconds(), b.effectiveSeconds()))
                .map(ResponseTimes.Unanswered::since).orElse(null);
        List<Comparison> comparisons = List.of(comparison(businessId, userId, "7d"), comparison(businessId, userId, "30d"),
                comparison(businessId, userId, "all"));
        if (last.isEmpty()) {
            return new ShiftSummary(false, null, null, 0, 0, null, null, fresh.size(), oldestNew, unanswered.size(), oldestSec, oldestSince,
                    comparisons);
        }
        Instant start = last.get(0)[0];
        Instant end = last.get(0)[1];
        boolean open = end == null && Duration.between(start, now).compareTo(MAX_OPEN_SHIFT) <= 0;
        Instant to = end != null ? end : open ? now : start.plus(MAX_OPEN_SHIFT);
        Data d = load(businessId, start, to);
        List<Reply> mine = d.result.replies().stream()
                .filter(r -> !r.replyAt().isBefore(start) && r.replyAt().isBefore(to) && r.onShift() && Long.valueOf(userId).equals(r.userId()))
                .toList();
        var st = ResponseTimes.stats(mine.stream().map(Reply::effectiveSeconds).toList());
        AppUser me = new AppUser();
        me.setId(userId);
        me.setUsername(username);
        int processed = processed(businessId, start, to, mine, List.of(me)).getOrDefault(username, Set.of()).size();
        return new ShiftSummary(open, start, open ? null : to, processed, st.replies(), st.medianSeconds(), st.within5Percent(),
                fresh.size(), oldestNew, unanswered.size(), oldestSec, oldestSince, comparisons);
    }

    private Comparison comparison(long businessId, long userId, String period) {
        Leaderboard board = leaderboard(businessId, period);
        var ranked = board.managers().stream().filter(r -> r.replies() > 0)
                .sorted(java.util.Comparator.comparingLong(ManagerRow::medianSeconds)).toList();
        ManagerRow mine = board.managers().stream().filter(r -> r.userId() == userId && r.replies() > 0).findFirst().orElse(null);
        return new Comparison(period, mine == null ? null : mine.medianSeconds(), board.team().medianSeconds(),
                mine == null ? null : ranked.indexOf(mine) + 1, ranked.size());
    }

    private static String last4(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private record Data(List<Shift> shifts, ResponseTimes.Result result) {}

    private Data load(long businessId, Instant from, Instant to) {
        Instant lookFrom = from.minus(LOOKBACK);
        List<Shift> shifts = jdbc.query("""
                SELECT e.user_id, e.start_at, e.end_at FROM manager_time_entry e JOIN app_user u ON u.id = e.user_id
                WHERE u.business_id = ? AND e.start_at < ? AND (e.end_at IS NULL OR e.end_at > ?)
                """, (rs, i) -> {
            Instant s = rs.getTimestamp(2).toInstant();
            Instant e = rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant();
            Instant cap = s.plus(MAX_OPEN_SHIFT);
            return new Shift(rs.getLong(1), s, e != null ? e : cap.isBefore(to) ? cap : to);
        }, businessId, java.sql.Timestamp.from(to), java.sql.Timestamp.from(lookFrom));
        List<Msg> msgs = jdbc.query("""
                SELECT id, phone_number, direction, created_at, automation_key, template_key, status, sent_by_user_id, body
                FROM sms_message WHERE business_id = ? AND created_at >= ? AND created_at < ? ORDER BY created_at, id
                """, (rs, i) -> {
            boolean in = "INBOUND".equals(rs.getString(3));
            String auto = rs.getString(5);
            String body = rs.getString(9);
            boolean keyword = body != null && KEYWORDS.contains(body.trim().toUpperCase().replaceAll("[^A-Z]", ""));
            boolean human = !in && auto == null && rs.getString(6) == null && "SENT".equals(rs.getString(7));
            return new Msg(rs.getLong(1), rs.getString(2), in, rs.getTimestamp(4).toInstant(), human,
                    (Long) rs.getObject(8), in && (auto != null || keyword));
        }, businessId, java.sql.Timestamp.from(lookFrom), java.sql.Timestamp.from(to));
        return new Data(shifts, ResponseTimes.compute(msgs, shifts, to));
    }

    /** username -> distinct leads that manager touched in the window: a CRM event they wrote, or a text they sent to a lead's number. */
    private Map<String, Set<Long>> processed(long businessId, Instant from, Instant to, List<Reply> replies, List<AppUser> managers) {
        Map<String, Set<Long>> out = new HashMap<>();
        jdbc.query("SELECT created_by, lead_id FROM lead_events WHERE business_id = ? AND created_at >= ? AND created_at < ? AND type IN ("
                        + PROCESSED_TYPES + ") AND created_by IS NOT NULL",
                rs -> { out.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getLong(2)); },
                businessId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        Map<Long, String> nameById = new HashMap<>();
        for (AppUser m : managers) nameById.put(m.getId(), m.getUsername());
        Map<String, Long> leadByPhone = new HashMap<>();
        jdbc.query("""
                SELECT right(regexp_replace(phone_number, '\\D', '', 'g'), 10), min(id) FROM lead_intake
                WHERE business_id = ? AND outcome <> 'DUPLICATE' AND COALESCE(phone_number, '') <> '' GROUP BY 1
                """, rs -> { leadByPhone.put(rs.getString(1), rs.getLong(2)); }, businessId);
        for (Reply r : replies) {
            String name = r.userId() == null ? null : nameById.get(r.userId());
            if (name == null || r.attribution() == Attribution.UNKNOWN) continue;
            String digits = r.phone() == null ? "" : r.phone().replaceAll("\\D", "");
            Long lead = leadByPhone.get(digits.length() > 10 ? digits.substring(digits.length() - 10) : digits);
            if (lead != null) out.computeIfAbsent(name, k -> new HashSet<>()).add(lead);
        }
        return out;
    }
}
