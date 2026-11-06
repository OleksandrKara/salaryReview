package com.salonreview.marketing;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.sms.SmsAutomationService;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Two Telegram messages a day per business: 08:05 PT what needs processing, 19:50 PT what was done.
 * Each (business, kind, day) is sent at most once, an empty day sends nothing, and a failed delivery is
 * retried every 5 minutes for half an hour.
 */
@Service
public class LeadDigestService {

    private static final Logger log = LoggerFactory.getLogger(LeadDigestService.class);
    static final String AUTOMATION_KEY = "lead_daily_digest";
    static final ZoneId TZ = ZoneId.of("America/Los_Angeles");
    private static final int MAX_ATTEMPTS = 7;
    private static final int COLD_DAYS = 7;
    private static final Set<String> NOT_STAFF = Set.of("System", "Square");
    private static final Map<String, String> CHANNELS = Map.of(
            "Call", "звонки", "Form", "формы", "SMS", "SMS с сайта", "Text", "SMS клиентов",
            "Square", "из Square", "Academy", "Academy");

    public enum Kind {
        MORNING(LocalTime.of(8, 5)), EVENING(LocalTime.of(19, 50));
        final LocalTime at;
        Kind(LocalTime at) { this.at = at; }
    }

    private final JdbcTemplate jdbc;
    private final LeadFollowUpService leads;
    private final LeadIntakeService intake;
    private final LeadDigestLog digestLog;
    private final com.salonreview.square.SquareClientProvider squares;
    private final SmsAutomationService automations;
    private final TelegramNotificationService telegram;
    private final String publicBaseUrl;

    public LeadDigestService(JdbcTemplate jdbc, LeadFollowUpService leads, LeadIntakeService intake, LeadDigestLog digestLog,
                             com.salonreview.square.SquareClientProvider squares, SmsAutomationService automations, TelegramNotificationService telegram,
                             @Value("${app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.leads = leads;
        this.intake = intake;
        this.digestLog = digestLog;
        this.squares = squares;
        this.automations = automations;
        this.telegram = telegram;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Called every 5 minutes; acts only inside the 30 minute window after each digest's time. */
    public void runDue(ZonedDateTime now) {
        for (Kind kind : Kind.values()) {
            LocalTime t = now.toLocalTime();
            if (t.isBefore(kind.at) || !t.isBefore(kind.at.plusMinutes(30))) continue;
            for (long businessId : intake.enabledBusinesses()) {
                try {
                    process(businessId, kind, now.toLocalDate(), now.toInstant());
                } catch (Exception e) {
                    log.warn("Lead digest {} for business {} failed: {}", kind, businessId, e.getMessage());
                }
            }
        }
    }

    void process(long businessId, Kind kind, LocalDate day, Instant now) {
        if (!automations.isEnabled(businessId, AUTOMATION_KEY)) return;
        LeadDigestLog.Entry entry = digestLog.find(businessId, kind.name(), day);
        if (entry != null && !"FAILED".equals(entry.status())) return;
        int attempts = entry == null ? 0 : entry.attempts();
        List<LeadView> all = leads.listFor(businessId, null);
        String text = kind == Kind.MORNING ? morning(all, day, now) : evening(all, handled(businessId, day), day, now);
        if (text == null) {
            digestLog.record(businessId, kind.name(), day, "SKIPPED", attempts, null);
            return;
        }
        String url = publicBaseUrl + "/admin/leads?business=" + businessId;
        boolean ok = telegram.sendPlainAlert(businessId, text, "Открыть лиды", url);
        attempts++;
        digestLog.record(businessId, kind.name(), day, ok ? "SENT" : attempts >= MAX_ATTEMPTS ? "GAVE_UP" : "FAILED", attempts, text);
    }

    // ---- morning ----

    static boolean active(LeadView l) {
        return "NEW".equals(l.status()) || "CONTACTED".equals(l.status());
    }

    static boolean overdue(LeadView l, Instant now) {
        return active(l) && l.callbackAt() != null && !Instant.parse(l.callbackAt()).isAfter(now);
    }

    static Instant activity(LeadView l) {
        return Instant.parse(l.activityAt() != null ? l.activityAt() : l.createdAt());
    }

    /** Returns null when there is nothing to process, so no message is sent. */
    static String morning(List<LeadView> all, LocalDate day, Instant now) {
        List<LeadView> overdue = all.stream().filter(l -> overdue(l, now))
                .sorted(Comparator.comparing((LeadView l) -> Instant.parse(l.callbackAt()))).toList();
        List<LeadView> fresh = new ArrayList<>();
        List<LeadView> cold = new ArrayList<>();
        for (LeadView l : all) {
            if (!"NEW".equals(l.status()) || overdue(l, now)) continue;
            (Duration.between(activity(l), now).toDays() > COLD_DAYS ? cold : fresh).add(l);
        }
        fresh.sort(Comparator.comparing(LeadDigestService::activity));
        long stalled = all.stream().filter(l -> active(l) && "DONE".equals(l.consultState())
                && !"NEW".equals(l.status()) && !overdue(l, now)).count();
        int todo = overdue.size() + fresh.size() + cold.size();
        if (todo == 0 && stalled == 0) return null;

        StringBuilder sb = new StringBuilder("Лиды на сегодня, ").append(dateLabel(day)).append('\n');
        sb.append("К обработке: ").append(todo).append('\n');
        if (!overdue.isEmpty()) sb.append("• просроченные перезвоны: ").append(overdue.size()).append('\n');
        int news = fresh.size() + cold.size();
        if (news > 0) {
            sb.append("• новые: ").append(news);
            String split = channelSplit(java.util.stream.Stream.concat(fresh.stream(), cold.stream()).toList());
            if (!split.isEmpty()) sb.append(" (").append(split).append(')');
            sb.append('\n');
        }
        if (!cold.isEmpty()) sb.append("• из них старше ").append(COLD_DAYS).append(" дн.: ").append(cold.size()).append('\n');
        if (stalled > 0) sb.append("После консультации не записались: ").append(stalled).append('\n');
        List<String> top = new ArrayList<>();
        for (LeadView l : overdue) {
            if (top.size() < 5) top.add("• " + who(l) + ", " + channel(l) + ", перезвон просрочен на " + age(Instant.parse(l.callbackAt()), now));
        }
        for (LeadView l : fresh) {
            if (top.size() < 5) top.add("• " + who(l) + ", " + channel(l) + ", ждёт " + age(activity(l), now));
        }
        if (!top.isEmpty()) sb.append("\nПервые в очереди:\n").append(String.join("\n", top));
        if (todo > top.size() && !top.isEmpty()) sb.append("\nи ещё ").append(todo - top.size());
        return sb.toString();
    }

    // ---- evening ----

    record Handled(Map<String, Integer> byStatus, int auto, int callbacks, Map<String, Integer> byPerson) {
        int staffTotal() { return byStatus.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    /** Each lead counts once, under the last status it was moved to during the day. */
    Handled handled(long businessId, LocalDate day) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byPerson = new TreeMap<>();
        int[] auto = {0};
        jdbc.query("""
                SELECT text, created_by, count(*) AS n FROM (
                  SELECT DISTINCT ON (e.lead_id) e.lead_id, e.text, e.created_by FROM lead_events e
                  WHERE e.business_id = ? AND e.type = 'STATUS' AND (e.created_at AT TIME ZONE 'America/Los_Angeles')::date = ?
                  ORDER BY e.lead_id, e.created_at DESC, e.id DESC) x
                WHERE text <> 'NEW' GROUP BY text, created_by
                """, rs -> {
            String status = rs.getString("text");
            String by = rs.getString("created_by");
            int n = rs.getInt("n");
            if (by == null || NOT_STAFF.contains(by)) {
                if ("BOOKED".equals(status)) auto[0] += n;
                return;
            }
            byStatus.merge(status, n, Integer::sum);
            byPerson.merge(personName(businessId, by), n, Integer::sum);
        }, businessId, java.sql.Date.valueOf(day));
        Integer callbacks = jdbc.queryForObject("""
                SELECT count(DISTINCT lead_id) FROM lead_events WHERE business_id = ? AND type = 'CALLBACK'
                  AND (created_at AT TIME ZONE 'America/Los_Angeles')::date = ?
                  AND COALESCE(created_by, '') NOT IN ('System', 'Square')
                """, Integer.class, businessId, java.sql.Date.valueOf(day));
        return new Handled(byStatus, auto[0], callbacks == null ? 0 : callbacks, byPerson);
    }

    /** Real name for a login: provider name, else the Square team member name, else the login itself. */
    String personName(long businessId, String username) {
        try {
            var row = jdbc.queryForList("""
                    SELECT a.role, p.display_name, a.square_team_member_id FROM app_user a
                    LEFT JOIN providers p ON p.id = a.provider_id
                    WHERE a.username = ? AND a.business_id = ?""", username, businessId);
            if (row.isEmpty()) return username;
            String display = (String) row.get(0).get("display_name");
            if (display != null && !display.isBlank()) return display.trim();
            String teamMember = (String) row.get(0).get("square_team_member_id");
            if (teamMember != null) {
                for (var m : squares.forBusiness(businessId).allTeamMembers()) {
                    if (teamMember.equals(m.id()) && !m.fullName().isBlank()) return m.fullName();
                }
            }
            return "OWNER".equals(row.get(0).get("role")) ? "Владелец (" + username + ")" : username;
        } catch (RuntimeException e) {
            log.warn("Could not resolve name for {}: {}", username, e.toString());
            return username;
        }
    }

    /** Returns null when nothing happened and nothing is waiting, so no message is sent. */
    static String evening(List<LeadView> all, Handled h, LocalDate day, Instant now) {
        List<LeadView> today = all.stream().filter(l -> !"SPAM".equals(l.status())
                && Instant.parse(l.createdAt()).atZone(TZ).toLocalDate().equals(day)).toList();
        long untouchedToday = today.stream().filter(l -> "NEW".equals(l.status())).count();
        long overdue = all.stream().filter(l -> overdue(l, now)).count();
        long remaining = all.stream().filter(l -> overdue(l, now) || "NEW".equals(l.status())).count();
        long tomorrow = all.stream().filter(l -> active(l) && l.callbackAt() != null
                && Instant.parse(l.callbackAt()).atZone(TZ).toLocalDate().equals(day.plusDays(1))).count();
        if (today.isEmpty() && h.staffTotal() == 0 && h.auto() == 0 && h.callbacks() == 0 && remaining == 0) return null;

        StringBuilder sb = new StringBuilder("Итоги дня, ").append(dateLabel(day)).append('\n');
        sb.append("Пришло лидов: ").append(today.size());
        String split = channelSplit(today);
        if (!split.isEmpty()) sb.append(" (").append(split).append(')');
        sb.append('\n');
        if (h.staffTotal() > 0 || h.callbacks() > 0) {
            sb.append("Обработано командой: ").append(h.staffTotal()).append('\n');
            line(sb, "связались", h.byStatus().get("CONTACTED"));
            line(sb, "записались", h.byStatus().get("BOOKED"));
            line(sb, "потеряно", h.byStatus().get("LOST"));
            line(sb, "спам или тест", h.byStatus().get("SPAM"));
            line(sb, "назначены перезвоны", h.callbacks());
        } else {
            sb.append("Команда сегодня лиды не обрабатывала\n");
        }
        if (h.auto() > 0) sb.append("Записались сами (Square): ").append(h.auto()).append('\n');
        if (untouchedToday > 0) sb.append("Сегодняшние без реакции: ").append(untouchedToday).append('\n');
        sb.append("Остаётся к обработке: ").append(remaining);
        if (overdue > 0) sb.append(" (просрочено перезвонов: ").append(overdue).append(')');
        sb.append('\n');
        if (tomorrow > 0) sb.append("Перезвонить завтра: ").append(tomorrow).append('\n');
        if (!h.byPerson().isEmpty()) {
            List<String> people = h.byPerson().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey() + ": " + e.getValue()).toList();
            sb.append(people.size() == 1 ? "Работал: " : "Работали: ").append(String.join(", ", people)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ---- helpers ----

    private static void line(StringBuilder sb, String label, Integer n) {
        if (n != null && n > 0) sb.append("• ").append(label).append(": ").append(n).append('\n');
    }

    private static String channelSplit(List<LeadView> list) {
        Map<String, Integer> by = new TreeMap<>();
        for (LeadView l : list) by.merge(CHANNELS.getOrDefault(l.channel(), String.valueOf(l.channel())), 1, Integer::sum);
        return by.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String channel(LeadView l) {
        return CHANNELS.getOrDefault(l.channel(), String.valueOf(l.channel()));
    }

    private static String who(LeadView l) {
        if (l.name() != null && !l.name().isBlank()) return l.name().trim();
        if (l.phoneNumber() != null && !l.phoneNumber().isBlank()) return l.phoneNumber();
        if (l.email() != null && !l.email().isBlank()) return l.email();
        return "без имени";
    }

    static String age(Instant from, Instant now) {
        Duration d = Duration.between(from, now);
        if (d.toMinutes() < 60) return Math.max(1, d.toMinutes()) + " мин";
        if (d.toHours() < 48) return d.toHours() + " ч";
        return d.toDays() + " дн.";
    }

    private static final String[] MONTHS = {"янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек."};
    private static final Map<DayOfWeek, String> DOW = Map.of(DayOfWeek.MONDAY, "пн", DayOfWeek.TUESDAY, "вт", DayOfWeek.WEDNESDAY, "ср",
            DayOfWeek.THURSDAY, "чт", DayOfWeek.FRIDAY, "пт", DayOfWeek.SATURDAY, "сб", DayOfWeek.SUNDAY, "вс");

    static String dateLabel(LocalDate d) {
        return DOW.get(d.getDayOfWeek()) + " " + d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }
}
