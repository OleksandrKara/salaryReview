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
                             int inferredReplies) {}

    public record Leaderboard(String period, Instant from, Instant to, List<ManagerRow> managers, ResponseTimes.Stats team,
                              int unknownReplies, int offShiftReplies, int unansweredNow, Long oldestUnansweredSeconds) {}

    public record ShiftSummary(boolean onShift, Instant shiftStart, Instant shiftEnd, int leadsProcessed, int replies,
                               Long medianSeconds, Integer within5Percent, int newLeadsWaiting, Instant oldestNewLeadAt,
                               int unansweredCount, Long oldestUnansweredSeconds, Instant oldestUnansweredSince) {}

    public Leaderboard leaderboard(long businessId, String period) {
        Instant now = clock.instant();
        Instant from = switch (period) {
            case "today" -> now.atZone(PT).toLocalDate().atStartOfDay(PT).toInstant();
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be today, 7d or 30d");
        };
        Data d = load(businessId, from, now);
        List<AppUser> managers = users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(businessId, List.of(Role.MANAGER));
        List<Reply> inWindow = d.result.replies().stream().filter(r -> !r.replyAt().isBefore(from)).toList();
        Map<String, Set<Long>> processed = processed(businessId, from, now, inWindow, managers);

        List<ManagerRow> rows = new ArrayList<>();
        for (AppUser m : managers) {
            List<Reply> mine = inWindow.stream().filter(r -> r.onShift() && m.getId().equals(r.userId())).toList();
            var st = ResponseTimes.stats(mine.stream().map(Reply::effectiveSeconds).toList());
            double hours = ResponseTimes.shiftSeconds(d.shifts, m.getId(), from, now, now) / 3600.0;
            rows.add(new ManagerRow(m.getId(), m.getUsername(), st.replies(), st.medianSeconds(), st.averageSeconds(),
                    st.within5Percent(), st.within15Percent(), processed.getOrDefault(m.getUsername(), Set.of()).size(),
                    Math.round(hours * 10) / 10.0, (int) mine.stream().filter(r -> r.attribution() == Attribution.INFERRED).count()));
        }
        var team = ResponseTimes.stats(inWindow.stream().filter(Reply::onShift).map(Reply::effectiveSeconds).toList());
        int unknown = (int) inWindow.stream().filter(r -> r.onShift() && r.attribution() == Attribution.UNKNOWN).count();
        int off = (int) inWindow.stream().filter(r -> !r.onShift()).count();
        Long oldest = d.result.unanswered().stream().map(ResponseTimes.Unanswered::effectiveSeconds).max(Long::compare).orElse(null);
        return new Leaderboard(period, from, now, rows, team, unknown, off, d.result.unanswered().size(), oldest);
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
        if (last.isEmpty()) {
            return new ShiftSummary(false, null, null, 0, 0, null, null, fresh.size(), oldestNew, unanswered.size(), oldestSec, oldestSince);
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
                fresh.size(), oldestNew, unanswered.size(), oldestSec, oldestSince);
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
