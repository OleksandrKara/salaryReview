package com.salonreview.marketing;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.sms.SmsAutomationService;
import com.salonreview.telegram.TelegramAutomationLog;
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
 * Start-of-shift (08:05 PT, what needs processing) and end-of-shift (19:50 PT, what was done) Telegram messages.
 * Procedures (nails or PMU) and Academy are separate messages; Academy is self-contained so it can be forwarded,
 * and says out loud when there was nothing to do. Each (business, direction, kind, day) is sent at most once,
 * an empty procedures day sends nothing, and a failed delivery is retried every 5 minutes for half an hour.
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
    private final TelegramAutomationLog telegramLog;
    private final String publicBaseUrl;

    public LeadDigestService(JdbcTemplate jdbc, LeadFollowUpService leads, LeadIntakeService intake, LeadDigestLog digestLog,
                             com.salonreview.square.SquareClientProvider squares, SmsAutomationService automations, TelegramNotificationService telegram,
                             TelegramAutomationLog telegramLog, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.leads = leads;
        this.intake = intake;
        this.digestLog = digestLog;
        this.squares = squares;
        this.automations = automations;
        this.telegram = telegram;
        this.telegramLog = telegramLog;
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

    /** Procedures (nails or PMU) and Academy are separate messages with their own retry state; Academy exists only where the business has Academy leads. */
    void process(long businessId, Kind kind, LocalDate day, Instant now) {
        if (!automations.isEnabled(businessId, AUTOMATION_KEY)) return;
        List<LeadView> all = leads.listFor(businessId, null);
        boolean hasAcademy = all.stream().anyMatch(LeadView::academy);
        deliver(businessId, kind, day, now, false, hasAcademy, all);
        if (hasAcademy) deliver(businessId, kind, day, now, true, true, all);
    }

    private void deliver(long businessId, Kind kind, LocalDate day, Instant now, boolean academy, boolean split, List<LeadView> all) {
        String logKind = academy ? kind.name() + "_ACADEMY" : kind.name();
        LeadDigestLog.Entry entry = digestLog.find(businessId, logKind, day);
        if (entry != null && !"FAILED".equals(entry.status())) return;
        int attempts = entry == null ? 0 : entry.attempts();
        List<LeadView> group = all.stream().filter(l -> l.academy() == academy).toList();
        String base = publicBaseUrl + "/admin/leads?business=" + businessId;
        java.util.function.Predicate<Long> inGroup = id -> inGroup(id, academy);
        String text;
        if (academy) {
            text = kind == Kind.MORNING ? academyMorning(group, day, now, publicBaseUrl, businessId)
                    : academyEvening(group, handled(businessId, day, inGroup), day, now, publicBaseUrl, businessId);
        } else {
            String title = split ? businessName(businessId) + " (услуги)" : "";
            text = kind == Kind.MORNING ? morning(group, day, now, title)
                    : evening(group, handled(businessId, day, inGroup), day, now, title);
        }
        if (text == null) {
            digestLog.record(businessId, logKind, day, "SKIPPED", attempts, null);
            return;
        }
        boolean ok = telegram.sendPlainAlert(businessId, text, academy ? "Открыть лиды Академии" : "Открыть лиды", base);
        attempts++;
        digestLog.record(businessId, logKind, day, ok ? "SENT" : attempts >= MAX_ATTEMPTS ? "GAVE_UP" : "FAILED", attempts, text);
        if (ok || attempts >= MAX_ATTEMPTS) {
            telegramLog.record(businessId, AUTOMATION_KEY, kind == Kind.MORNING ? "SHIFT_START" : "SHIFT_END", direction(businessId, academy), ok);
        }
    }

    static String direction(long businessId, boolean academy) {
        return academy ? TelegramAutomationLog.ACADEMY : businessId == 2 ? TelegramAutomationLog.PMU : TelegramAutomationLog.NAILS;
    }

    private boolean inGroup(long leadId, boolean academy) {
        try {
            return leads.isAcademy(leadId) == academy;
        } catch (RuntimeException e) {
            return !academy;
        }
    }

    private String businessName(long businessId) {
        var names = jdbc.queryForList("SELECT name FROM business WHERE id = ?", String.class, businessId);
        return names.isEmpty() || names.get(0) == null ? "Business " + businessId : names.get(0);
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
        return morning(all, day, now, "");
    }

    static String morning(List<LeadView> all, LocalDate day, Instant now, String title) {
        List<LeadView> overdue = all.stream().filter(l -> overdue(l, now))
                .sorted(Comparator.comparing((LeadView l) -> Instant.parse(l.callbackAt()))).toList();
        List<LeadView> fresh = new ArrayList<>();
        List<LeadView> cold = new ArrayList<>();
        for (LeadView l : all) {
            if (!"NEW".equals(l.status()) || overdue(l, now)) continue;
            (Duration.between(activity(l), now).toDays() > COLD_DAYS ? cold : fresh).add(l);
        }
        fresh.sort(Comparator.comparing(LeadDigestService::activity));
        long stalled = all.stream().filter(l -> active(l) && ("DONE".equals(l.consultState()) || "NO_SHOW".equals(l.consultState()))
                && !"NEW".equals(l.status()) && !overdue(l, now)).count();
        int todo = overdue.size() + fresh.size() + cold.size();
        long closable = all.stream().filter(LeadView::closeReady).count();
        if (todo == 0 && stalled == 0 && closable == 0) return null;

        StringBuilder sb = new StringBuilder(title.isEmpty() ? "" : title + ". ").append("Лиды на сегодня, ").append(dateLabel(day)).append('\n');
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
        if (closable > 0) sb.append("Можно закрыть как Lost (нет ответа после попыток): ").append(closable).append('\n');
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

    record HandledLead(long leadId, String status, String by) {}

    record Handled(Map<String, Integer> byStatus, int auto, int callbacks, Map<String, Map<String, Integer>> byPerson,
                   Map<String, Integer> callbacksByPerson, List<HandledLead> leads) {
        Handled(Map<String, Integer> byStatus, int auto, int callbacks, Map<String, Map<String, Integer>> byPerson,
                Map<String, Integer> callbacksByPerson) {
            this(byStatus, auto, callbacks, byPerson, callbacksByPerson, List.of());
        }
        Handled(Map<String, Integer> byStatus, int auto, int callbacks, Map<String, Map<String, Integer>> byPerson) {
            this(byStatus, auto, callbacks, byPerson, Map.of(), List.of());
        }
        int staffTotal() { return byStatus.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    /** Each lead counts once, under the last status it was moved to during the day; only leads accepted by {@code inGroup} count. */
    Handled handled(long businessId, LocalDate day, java.util.function.Predicate<Long> inGroup) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> byPerson = new TreeMap<>();
        List<HandledLead> handledLeads = new ArrayList<>();
        int[] auto = {0};
        Map<String, String> names = new java.util.HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (e.lead_id) e.lead_id, e.text, e.created_by FROM lead_events e
                WHERE e.business_id = ? AND e.type = 'STATUS' AND (e.created_at AT TIME ZONE 'America/Los_Angeles')::date = ?
                ORDER BY e.lead_id, e.created_at DESC, e.id DESC
                """, rs -> {
            long leadId = rs.getLong("lead_id");
            String status = rs.getString("text");
            String by = rs.getString("created_by");
            if ("NEW".equals(status) || !inGroup.test(leadId)) return;
            if (by == null || NOT_STAFF.contains(by)) {
                if ("BOOKED".equals(status)) auto[0]++;
                return;
            }
            String person = names.computeIfAbsent(by, u -> personName(businessId, u));
            byStatus.merge(status, 1, Integer::sum);
            byPerson.computeIfAbsent(person, k -> new LinkedHashMap<>()).merge(status, 1, Integer::sum);
            handledLeads.add(new HandledLead(leadId, status, person));
        }, businessId, java.sql.Date.valueOf(day));
        Set<Long> callbackLeads = new java.util.HashSet<>();
        Map<String, Set<Long>> cbByPerson = new TreeMap<>();
        jdbc.query("""
                SELECT DISTINCT lead_id, created_by FROM lead_events WHERE business_id = ? AND type = 'CALLBACK'
                  AND (created_at AT TIME ZONE 'America/Los_Angeles')::date = ?
                  AND COALESCE(created_by, '') NOT IN ('System', 'Square')
                """, rs -> {
            long leadId = rs.getLong("lead_id");
            if (!inGroup.test(leadId)) return;
            callbackLeads.add(leadId);
            String by = rs.getString("created_by");
            if (by == null) return;
            String person = names.computeIfAbsent(by, u -> personName(businessId, u));
            cbByPerson.computeIfAbsent(person, k -> new java.util.HashSet<>()).add(leadId);
        }, businessId, java.sql.Date.valueOf(day));
        Map<String, Integer> cbPerson = new TreeMap<>();
        cbByPerson.forEach((k, v) -> cbPerson.put(k, v.size()));
        return new Handled(byStatus, auto[0], callbackLeads.size(), byPerson, cbPerson, handledLeads);
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
        return evening(all, h, day, now, "");
    }

    static String evening(List<LeadView> all, Handled h, LocalDate day, Instant now, String title) {
        List<LeadView> today = all.stream().filter(l -> !"SPAM".equals(l.status())
                && Instant.parse(l.createdAt()).atZone(TZ).toLocalDate().equals(day)).toList();
        long untouchedToday = today.stream().filter(l -> "NEW".equals(l.status())).count();
        long overdue = all.stream().filter(l -> overdue(l, now)).count();
        long remaining = all.stream().filter(l -> overdue(l, now) || "NEW".equals(l.status())).count();
        long tomorrow = all.stream().filter(l -> active(l) && l.callbackAt() != null
                && Instant.parse(l.callbackAt()).atZone(TZ).toLocalDate().equals(day.plusDays(1))).count();
        if (today.isEmpty() && h.staffTotal() == 0 && h.auto() == 0 && h.callbacks() == 0 && remaining == 0) return null;

        StringBuilder sb = new StringBuilder(title.isEmpty() ? "" : title + ". ").append("Итоги дня, ").append(dateLabel(day)).append('\n');
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
        long closable = all.stream().filter(LeadView::closeReady).count();
        if (closable > 0) sb.append("Готовы к закрытию как Lost (нет ответа): ").append(closable).append('\n');
        Set<String> names = new java.util.TreeSet<>(h.byPerson().keySet());
        names.addAll(h.callbacksByPerson().keySet());
        if (names.size() > 1) {
            sb.append("\nКто что сделал:\n");
            names.stream().sorted(Comparator.comparingInt((String n) -> -personTotal(h, n)).thenComparing(n -> n)).forEach(n -> {
                Map<String, Integer> st = h.byPerson().getOrDefault(n, Map.of());
                List<String> parts = new ArrayList<>();
                addPart(parts, "связались", st.get("CONTACTED"));
                addPart(parts, "записались", st.get("BOOKED"));
                addPart(parts, "потеряно", st.get("LOST"));
                addPart(parts, "спам или тест", st.get("SPAM"));
                addPart(parts, "перезвонов назначено", h.callbacksByPerson().get(n));
                sb.append("• ").append(n).append(": ").append(String.join(", ", parts)).append('\n');
            });
        } else if (names.size() == 1) {
            sb.append("Работал: ").append(names.iterator().next()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ---- helpers ----

    private static int personTotal(Handled h, String name) {
        return h.byPerson().getOrDefault(name, Map.of()).values().stream().mapToInt(Integer::intValue).sum()
                + h.callbacksByPerson().getOrDefault(name, 0);
    }

    private static void addPart(List<String> parts, String label, Integer n) {
        if (n != null && n > 0) parts.add(label + " " + n);
    }

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
        String last4 = last4(l.phoneNumber());
        if (l.name() != null && !l.name().isBlank()) return l.name().trim();
        if (last4 != null) return "тел. ***" + last4;
        if (l.email() != null && !l.email().isBlank()) return l.email();
        return "без имени";
    }

    private static String last4(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits.substring(Math.max(0, digits.length() - 4));
    }

    /** Name plus masked phone, for messages that are forwarded outside the CRM. */
    private static String whoMasked(LeadView l) {
        String name = l.name() != null && !l.name().isBlank() ? l.name().trim() : "Без имени";
        String last4 = last4(l.phoneNumber());
        return last4 == null ? name : name + ", тел. ***" + last4;
    }

    private static String leadLink(String baseUrl, long businessId, long leadId) {
        return baseUrl + "/admin/leads/" + leadId + "?business=" + businessId;
    }

    // ---- Academy: separate, self-contained Russian messages that can be forwarded as they are ----

    private static final int ACADEMY_MAX_LINES = 8;
    private static final Map<String, String> STATUS_RU = Map.of("CONTACTED", "связались", "BOOKED", "записались",
            "LOST", "потеряно", "SPAM", "спам или тест");

    static String academyMorning(List<LeadView> all, LocalDate day, Instant now, String baseUrl, long businessId) {
        List<LeadView> overdue = all.stream().filter(l -> overdue(l, now))
                .sorted(Comparator.comparing((LeadView l) -> Instant.parse(l.callbackAt()))).toList();
        List<LeadView> fresh = all.stream().filter(l -> "NEW".equals(l.status()) && !overdue(l, now))
                .sorted(Comparator.comparing(LeadDigestService::activity)).toList();
        long stalled = all.stream().filter(l -> active(l) && ("DONE".equals(l.consultState()) || "NO_SHOW".equals(l.consultState()))
                && !"NEW".equals(l.status()) && !overdue(l, now)).count();
        StringBuilder sb = new StringBuilder("Академия. Лиды на сегодня, ").append(dateLabel(day)).append('\n');
        int todo = overdue.size() + fresh.size();
        if (todo == 0 && stalled == 0) {
            return sb.append("Новых лидов и просроченных перезвонов нет, к обработке ничего.\n\nВсе лиды Академии: ")
                    .append(baseUrl).append("/admin/leads?business=").append(businessId).toString();
        }
        sb.append("К обработке: ").append(todo).append('\n');
        if (!overdue.isEmpty()) sb.append("• просроченные перезвоны: ").append(overdue.size()).append('\n');
        if (!fresh.isEmpty()) sb.append("• новые, ещё без реакции: ").append(fresh.size()).append('\n');
        if (stalled > 0) sb.append("• после консультации не записались: ").append(stalled).append('\n');
        int shown = 0;
        for (LeadView l : overdue) {
            if (shown++ == ACADEMY_MAX_LINES) break;
            sb.append("\n- ").append(whoMasked(l)).append(", перезвон просрочен на ").append(age(Instant.parse(l.callbackAt()), now))
                    .append("\n  ").append(leadLink(baseUrl, businessId, l.id()));
        }
        for (LeadView l : fresh) {
            if (shown++ >= ACADEMY_MAX_LINES) break;
            sb.append("\n- ").append(whoMasked(l)).append(", ждёт ").append(age(activity(l), now))
                    .append("\n  ").append(leadLink(baseUrl, businessId, l.id()));
        }
        if (todo > ACADEMY_MAX_LINES) sb.append("\n\nи ещё ").append(todo - ACADEMY_MAX_LINES);
        return sb.append("\n\nВсе лиды Академии: ").append(baseUrl).append("/admin/leads?business=").append(businessId).toString();
    }

    /** Never null: a day with no Academy activity is said out loud instead of leaving silence. */
    static String academyEvening(List<LeadView> all, Handled h, LocalDate day, Instant now, String baseUrl, long businessId) {
        String list = baseUrl + "/admin/leads?business=" + businessId;
        List<LeadView> today = all.stream().filter(l -> !"SPAM".equals(l.status())
                && Instant.parse(l.createdAt()).atZone(TZ).toLocalDate().equals(day)).toList();
        List<LeadView> waiting = all.stream().filter(l -> overdue(l, now) || "NEW".equals(l.status()))
                .sorted(Comparator.comparing(LeadDigestService::activity)).toList();
        long tomorrow = all.stream().filter(l -> active(l) && l.callbackAt() != null
                && Instant.parse(l.callbackAt()).atZone(TZ).toLocalDate().equals(day.plusDays(1))).count();
        StringBuilder sb = new StringBuilder("Академия. Итоги дня, ").append(dateLabel(day)).append('\n');
        sb.append("Пришло лидов: ").append(today.size()).append('\n');
        boolean worked = h.staffTotal() > 0 || h.callbacks() > 0;
        if (!worked && today.isEmpty() && waiting.isEmpty()) {
            return sb.append("Академия: ничего не сделано, лидов сегодня не было и к обработке ничего не осталось.\n\nВсе лиды Академии: ")
                    .append(list).toString();
        }
        if (worked) {
            sb.append("Обработано командой: ").append(h.staffTotal()).append('\n');
            if (h.callbacks() > 0) sb.append("Назначено перезвонов: ").append(h.callbacks()).append('\n');
            Map<Long, LeadView> byId = new java.util.HashMap<>();
            all.forEach(l -> byId.put(l.id(), l));
            int shown = 0;
            for (HandledLead hl : h.leads()) {
                if (shown++ == ACADEMY_MAX_LINES) break;
                LeadView l = byId.get(hl.leadId());
                sb.append("\n- ").append(l == null ? "лид №" + hl.leadId() : whoMasked(l)).append(": ")
                        .append(STATUS_RU.getOrDefault(hl.status(), hl.status())).append(", ").append(hl.by());
                if (l != null) sb.append("\n  ").append(leadLink(baseUrl, businessId, l.id()));
            }
            if (h.leads().size() > ACADEMY_MAX_LINES) sb.append("\n\nи ещё ").append(h.leads().size() - ACADEMY_MAX_LINES);
            sb.append('\n');
        } else {
            sb.append("Команда сегодня лиды Академии не обрабатывала.\n");
        }
        if (h.auto() > 0) sb.append("Записались сами: ").append(h.auto()).append('\n');
        sb.append("\nНе обработано: ").append(waiting.size()).append('\n');
        int shown = 0;
        for (LeadView l : waiting) {
            if (shown++ == ACADEMY_MAX_LINES) break;
            sb.append("- ").append(whoMasked(l)).append(overdue(l, now) ? ", перезвон просрочен на " + age(Instant.parse(l.callbackAt()), now)
                    : ", ждёт " + age(activity(l), now)).append("\n  ").append(leadLink(baseUrl, businessId, l.id())).append('\n');
        }
        if (waiting.size() > ACADEMY_MAX_LINES) sb.append("и ещё ").append(waiting.size() - ACADEMY_MAX_LINES).append('\n');
        if (tomorrow > 0) sb.append("\nПерезвонить завтра: ").append(tomorrow).append('\n');
        return sb.append("\nВсе лиды Академии: ").append(list).toString();
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
