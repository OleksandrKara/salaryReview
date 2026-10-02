package com.salonreview.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.tasks.NotionTasksClient.Task;
import com.salonreview.tasks.TaskBotTelegram.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task bot for tasks the owner hands to Anya (owner request 2026-10-02: tasks kept getting
 * forgotten, with no word on whether they were done or stuck). Notion holds the tasks
 * ({@link NotionTasksClient}); this bot is how Anya sees and closes them without opening Notion:
 *
 * <ul>
 *   <li>10:00 PT: Anya gets every open task that is due within 2 days, overdue, or undated, each
 *       with buttons ✅ Done / 🚧 In progress / ⛔ Stuck / ⏰ Tomorrow. Every press updates Notion
 *       and tells the owner.</li>
 *   <li>⛔ Stuck asks her what's in the way; her next message is saved to the task and sent to the
 *       owner.</li>
 *   <li>18:00 PT: overdue tasks go to the owner, and back to Anya as cards.</li>
 *   <li>Sunday 19:00 PT: weekly summary to the owner.</li>
 *   <li>/tasks any time: her list (Anya) or her list as a summary (owner).</li>
 * </ul>
 *
 * Only the two configured Telegram users are served; everyone else is ignored. Chat ids are
 * learned when they message the bot (Telegram only allows a bot to write to someone who pressed
 * Start), or set via config so they survive restarts.
 */
@Service
public class TaskBotService {
    private static final Logger log = LoggerFactory.getLogger(TaskBotService.class);
    static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    static final String ASSIGNEE_NAME = "Аня";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("ru"));
    /** Old messages waiting in Telegram from before the bot started are not replayed. */
    private static final Duration MAX_MESSAGE_AGE = Duration.ofHours(1);

    private final NotionTasksClient notion;
    private final TaskBotTelegram telegram;
    private final Clock clock;
    private final String assigneeUsername;
    private final String ownerUsername;
    private final Map<String, Long> chatIds = new ConcurrentHashMap<>();
    /** chat id -> Notion page id the person is about to explain being stuck on. */
    private final Map<Long, String> awaitingReason = new ConcurrentHashMap<>();
    private long offset = 0;

    @Autowired
    public TaskBotService(NotionTasksClient notion, TaskBotTelegram telegram,
                          @Value("${tasks.assignee-telegram-username:}") String assigneeUsername,
                          @Value("${tasks.owner-telegram-username:}") String ownerUsername,
                          @Value("${tasks.assignee-chat-id:}") String assigneeChatId,
                          @Value("${tasks.owner-chat-id:}") String ownerChatId) {
        this(notion, telegram, assigneeUsername, ownerUsername, assigneeChatId, ownerChatId, Clock.system(ZONE));
    }

    /** Test-only: fixed clock. */
    TaskBotService(NotionTasksClient notion, TaskBotTelegram telegram, String assigneeUsername, String ownerUsername,
                   String assigneeChatId, String ownerChatId, Clock clock) {
        this.notion = notion;
        this.telegram = telegram;
        this.clock = clock;
        this.assigneeUsername = normalize(assigneeUsername);
        this.ownerUsername = normalize(ownerUsername);
        if (assigneeChatId != null && !assigneeChatId.isBlank()) chatIds.put("assignee", Long.parseLong(assigneeChatId.trim()));
        if (ownerChatId != null && !ownerChatId.isBlank()) chatIds.put("owner", Long.parseLong(ownerChatId.trim()));
    }

    public boolean enabled() {
        return notion.configured() && telegram.configured() && !assigneeUsername.isEmpty();
    }

    // ---------------------------------------------------------------- polling

    public void poll() throws Exception {
        JsonNode updates = telegram.getUpdates(offset);
        long next = offset;
        for (JsonNode u : updates) {
            next = Math.max(next, u.path("update_id").asLong() + 1);
            try {
                handleUpdate(u);
            } catch (Exception e) {
                log.warn("Task bot: failed to handle update {}: {}", u.path("update_id").asLong(), e.getMessage());
            }
        }
        if (next != offset) {
            offset = next;
            telegram.getUpdates(offset); // acknowledge right away, so the other blue/green container never replays these
        }
    }

    void handleUpdate(JsonNode u) throws Exception {
        if (u.has("callback_query")) {
            handleCallback(u.path("callback_query"));
        } else if (u.has("message")) {
            handleMessage(u.path("message"));
        }
    }

    private void handleMessage(JsonNode m) throws Exception {
        if (!"private".equals(m.path("chat").path("type").asText())) return;
        Instant sent = Instant.ofEpochSecond(m.path("date").asLong());
        if (Duration.between(sent, clock.instant()).compareTo(MAX_MESSAGE_AGE) > 0) return;
        long chatId = m.path("chat").path("id").asLong();
        String role = role(m.path("from"), chatId);
        if (role == null) return;
        chatIds.put(role, chatId);
        String text = m.path("text").asText("").trim();

        if (text.startsWith("/start")) {
            log.info("Task bot: {} started the bot, chat id {}", role, chatId);
            if (role.equals("assignee")) {
                telegram.sendMessage(chatId, "Привет, Аня! 👋 Я буду присылать тебе задачи по салону каждое утро в 10:00. "
                        + "Под каждой задачей кнопки: ✅ сделала, 🚧 в работе, ⛔ застряла, ⏰ перенести на завтра. "
                        + "Алекс сразу увидит, что ты нажала. Список в любой момент: /tasks", null);
                sendAssigneeTasks(chatId, false);
            } else {
                telegram.sendMessage(chatId, "Готово. Сюда будут приходить отметки Ани, просрочки и сводка по воскресеньям. "
                        + "Её задачи сейчас: /tasks", null);
            }
            return;
        }
        if (text.startsWith("/tasks")) {
            if (role.equals("assignee")) sendAssigneeTasks(chatId, true);
            else {
                List<Task> open = notion.openTasksFor(ASSIGNEE_NAME);
                telegram.sendMessage(chatId, ownerOverview(open), detailButtons(open));
            }
            return;
        }
        String pending = awaitingReason.remove(chatId);
        if (role.equals("assignee") && pending != null && !text.isBlank()) {
            notion.setBlocker(pending, text);
            Task task = notion.get(pending);
            telegram.sendMessage(chatId, "Спасибо, передала Алексу 🙏", null);
            notifyOwner("⛔ Аня застряла: " + task.title() + "\nЧто мешает: " + text, pending);
            return;
        }
        if (role.equals("assignee")) {
            telegram.sendMessage(chatId, "Чтобы отметить задачу, нажми кнопку под ней. Список задач: /tasks", null);
        }
    }

    private void handleCallback(JsonNode q) throws Exception {
        long chatId = q.path("message").path("chat").path("id").asLong();
        long messageId = q.path("message").path("message_id").asLong();
        String role = role(q.path("from"), chatId);
        String[] parts = q.path("data").asText("").split("\\|");
        if (role == null || parts.length != 3 || !parts[0].equals("t")) return;
        String pageId = parts[1];
        if (parts[2].equals("i")) { // details: for both of them
            telegram.answerCallback(q.path("id").asText(), "Открываю детали");
            sendDetails(chatId, pageId, role.equals("assignee"));
            return;
        }
        if (!"assignee".equals(role)) {
            telegram.answerCallback(q.path("id").asText(), "Эти кнопки только для Ани");
            return;
        }
        chatIds.put(role, chatId);
        Task task = notion.get(pageId);
        switch (parts[2]) {
            case "d" -> {
                notion.setStatus(pageId, "Done");
                telegram.answerCallback(q.path("id").asText(), "Отлично! ✅");
                telegram.editMessage(chatId, messageId, "✅ Сделано: " + task.title());
                notifyOwner("✅ Аня сделала: " + task.title(), pageId);
            }
            case "p" -> {
                notion.setStatus(pageId, "In progress");
                telegram.answerCallback(q.path("id").asText(), "Отметила: в работе 🚧");
                notifyOwner("🚧 Аня взяла в работу: " + task.title(), pageId);
            }
            case "b" -> {
                awaitingReason.put(chatId, pageId);
                telegram.answerCallback(q.path("id").asText(), "Напиши, что мешает");
                telegram.sendMessage(chatId, "Что мешает с задачей «" + task.title() + "»? Напиши одним сообщением, я передам Алексу.", null);
            }
            case "l" -> {
                LocalDate tomorrow = today().plusDays(1);
                notion.setDue(pageId, tomorrow);
                telegram.answerCallback(q.path("id").asText(), "Перенесла на завтра ⏰");
                telegram.editMessage(chatId, messageId, "⏰ Перенесено на завтра: " + task.title());
                notifyOwner("⏰ Аня перенесла на завтра (" + DAY.format(tomorrow) + "): " + task.title(), pageId);
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- scheduled messages

    public void sendMorningDigest() throws Exception {
        Long chatId = chatIds.get("assignee");
        if (chatId == null) {
            log.info("Task bot: morning digest skipped, Anya hasn't pressed Start yet");
            return;
        }
        sendAssigneeTasks(chatId, false);
    }

    public void sendEveningOverdue() throws Exception {
        List<Task> overdue = notion.openTasksFor(ASSIGNEE_NAME).stream()
                .filter(t -> t.due() != null && t.due().isBefore(today())).toList();
        if (overdue.isEmpty()) return;
        StringBuilder sb = new StringBuilder("⚠️ Просрочено у Ани:\n");
        for (Task t : overdue) {
            sb.append("\n• ").append(t.title()).append(" (").append(dueLabel(t.due())).append(")").append(statusSuffix(t));
        }
        notifyOwner(sb.toString(), detailButtons(overdue));
        Long chatId = chatIds.get("assignee");
        if (chatId != null) {
            telegram.sendMessage(chatId, "Аня, напоминание: эти задачи уже просрочены 🙏", null);
            for (Task t : overdue) telegram.sendMessage(chatId, card(t), buttons(t));
        }
    }

    public void sendWeeklySummary() throws Exception {
        Instant weekAgo = clock.instant().minus(7, ChronoUnit.DAYS);
        List<Task> all = notion.allTasksFor(ASSIGNEE_NAME);
        StringBuilder sb = new StringBuilder("📋 Задачи Ани за неделю\n");
        section(sb, "✅ Сделано", all.stream().filter(t -> "Done".equals(t.status()) && t.lastEdited() != null && t.lastEdited().isAfter(weekAgo)).toList(), false);
        section(sb, "⛔ Застряла", all.stream().filter(t -> "Blocked".equals(t.status())).toList(), true);
        section(sb, "⚠️ Просрочено", all.stream().filter(t -> !"Done".equals(t.status()) && t.due() != null && t.due().isBefore(today())).toList(), false);
        section(sb, "🚧 В работе", all.stream().filter(t -> "In progress".equals(t.status())).toList(), false);
        section(sb, "📌 Впереди", all.stream().filter(t -> "To do".equals(t.status()) && (t.due() == null || !t.due().isBefore(today()))).toList(), false);
        List<Task> open = all.stream().filter(t -> !"Done".equals(t.status())).toList();
        notifyOwner(sb.toString(), detailButtons(open));
    }

    // ---------------------------------------------------------------- helpers

    private void sendAssigneeTasks(long chatId, boolean all) throws Exception {
        LocalDate horizon = today().plusDays(2);
        List<Task> tasks = notion.openTasksFor(ASSIGNEE_NAME).stream()
                .filter(t -> all || t.due() == null || !t.due().isAfter(horizon)).toList();
        if (tasks.isEmpty()) {
            if (all) telegram.sendMessage(chatId, "Открытых задач нет 🎉", null);
            return;
        }
        telegram.sendMessage(chatId, all ? "Твои открытые задачи:" : "Доброе утро, Аня! ☀️ Задачи на ближайшие дни:", null);
        for (Task t : tasks) telegram.sendMessage(chatId, card(t), buttons(t));
    }

    String card(Task t) {
        StringBuilder sb = new StringBuilder("📌 ").append(t.title());
        if (t.due() != null) sb.append("\nСрок: ").append(DAY.format(t.due())).append(" (").append(dueLabel(t.due())).append(")");
        if (!t.doneWhen().isBlank()) sb.append("\nГотово, когда: ").append(t.doneWhen());
        if ("In progress".equals(t.status())) sb.append("\nСейчас: в работе 🚧");
        if ("Blocked".equals(t.status())) sb.append("\nСейчас: застряла ⛔");
        return sb.toString();
    }

    static List<List<Button>> buttons(Task t) {
        String id = t.id();
        return List.of(
                List.of(new Button("✅ Сделала", "t|" + id + "|d"), new Button("🚧 В работе", "t|" + id + "|p")),
                List.of(new Button("⛔ Застряла", "t|" + id + "|b"), new Button("⏰ Завтра", "t|" + id + "|l")),
                List.of(new Button("📄 Подробнее", "t|" + id + "|i")));
    }

    String dueLabel(LocalDate due) {
        long days = ChronoUnit.DAYS.between(today(), due);
        if (days == 0) return "сегодня";
        if (days == 1) return "завтра";
        if (days < 0) return "просрочено на " + (-days) + " дн.";
        return "через " + days + " дн.";
    }

    private String ownerOverview(List<Task> open) {
        if (open.isEmpty()) return "У Ани нет открытых задач 🎉";
        StringBuilder sb = new StringBuilder("Открытые задачи Ани:\n");
        for (Task t : open) {
            sb.append("\n• ").append(t.title());
            if (t.due() != null) sb.append(" (").append(dueLabel(t.due())).append(")");
            sb.append(statusSuffix(t));
        }
        return sb.toString();
    }

    /** One "📄 title" button per task, opening its details right in Telegram. */
    static List<List<Button>> detailButtons(List<Task> tasks) {
        List<List<Button>> rows = new ArrayList<>();
        for (Task t : tasks.stream().limit(20).toList()) {
            String label = t.title().length() > 40 ? t.title().substring(0, 39) + "…" : t.title();
            rows.add(List.of(new Button("📄 " + label, "t|" + t.id() + "|i")));
        }
        return rows;
    }

    /** Full task, formatted, so nobody has to open Notion: fields + the page's own steps/notes. */
    void sendDetails(long chatId, String pageId, boolean forAssignee) throws Exception {
        Task t = notion.get(pageId);
        StringBuilder sb = new StringBuilder("<b>📌 ").append(NotionTasksClient.escapeHtml(t.title())).append("</b>\n");
        sb.append("\n<b>Статус:</b> ").append(statusRu(t.status()));
        if (t.due() != null) sb.append("\n<b>Срок:</b> ").append(DAY.format(t.due())).append(" (").append(dueLabel(t.due())).append(")");
        if (!t.doneWhen().isBlank()) sb.append("\n<b>Готово, когда:</b> ").append(NotionTasksClient.escapeHtml(t.doneWhen()));
        if (!t.blocker().isBlank()) sb.append("\n<b>Где застряла:</b> ").append(NotionTasksClient.escapeHtml(t.blocker()));
        String body = notion.bodyHtml(pageId);
        if (!body.isBlank()) sb.append("\n\n").append(body);
        String html = sb.length() > 3900 ? sb.substring(0, 3900) + "…" : sb.toString();
        telegram.sendHtml(chatId, html, forAssignee && !"Done".equals(t.status()) ? actionButtons(t.id()) : null);
    }

    private static List<List<Button>> actionButtons(String id) {
        return List.of(
                List.of(new Button("✅ Сделала", "t|" + id + "|d"), new Button("🚧 В работе", "t|" + id + "|p")),
                List.of(new Button("⛔ Застряла", "t|" + id + "|b"), new Button("⏰ Завтра", "t|" + id + "|l")));
    }

    private static String statusRu(String status) {
        return switch (status) {
            case "Done" -> "сделано ✅";
            case "In progress" -> "в работе 🚧";
            case "Blocked" -> "застряла ⛔";
            default -> "не начато";
        };
    }

    private static String statusSuffix(Task t) {
        return switch (t.status()) {
            case "In progress" -> " 🚧";
            case "Blocked" -> " ⛔" + (t.blocker().isBlank() ? "" : " " + t.blocker());
            default -> "";
        };
    }

    private void section(StringBuilder sb, String header, List<Task> tasks, boolean withBlocker) {
        if (tasks.isEmpty()) return;
        sb.append("\n").append(header).append(":\n");
        for (Task t : tasks) {
            sb.append("• ").append(t.title());
            if (t.due() != null && !"Done".equals(t.status())) sb.append(" (").append(dueLabel(t.due())).append(")");
            if (withBlocker && !t.blocker().isBlank()) sb.append(": ").append(t.blocker());
            sb.append("\n");
        }
    }

    private void notifyOwner(String text) throws Exception {
        notifyOwner(text, (List<List<Button>>) null);
    }

    private void notifyOwner(String text, String pageId) throws Exception {
        notifyOwner(text, List.of(List.of(new Button("📄 Подробнее", "t|" + pageId + "|i"))));
    }

    private void notifyOwner(String text, List<List<Button>> keyboard) throws Exception {
        Long chatId = chatIds.get("owner");
        if (chatId == null) {
            log.info("Task bot: owner hasn't pressed Start yet, not sent: {}", text);
            return;
        }
        telegram.sendMessage(chatId, text, keyboard == null || keyboard.isEmpty() ? null : keyboard);
    }

    private String role(JsonNode from, long chatId) {
        String username = normalize(from.path("username").asText(""));
        if (!assigneeUsername.isEmpty() && (username.equals(assigneeUsername) || Long.valueOf(chatId).equals(chatIds.get("assignee")))) {
            return "assignee";
        }
        if (!ownerUsername.isEmpty() && username.equals(ownerUsername) || Long.valueOf(chatId).equals(chatIds.get("owner"))) {
            return "owner";
        }
        return null;
    }

    private LocalDate today() {
        return clock.instant().atZone(ZONE).toLocalDate();
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    }
}
