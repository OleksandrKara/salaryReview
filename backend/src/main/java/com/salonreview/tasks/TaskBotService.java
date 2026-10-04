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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Two-person Telegram interface to the Notion task board. Notion is the source of truth. */
@Service
public class TaskBotService {
    private static final Logger log = LoggerFactory.getLogger(TaskBotService.class);
    static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("ru"));
    private static final Duration MAX_MESSAGE_AGE = Duration.ofHours(1);
    private static final Pattern NOTION_LINK = Pattern.compile("https://www\\.notion\\.so/([0-9a-fA-F]{32})");

    enum Actor {
        ANYA("Аня", "Ани"), ALEX("Алекс", "Алекса");

        final String notionName;
        final String genitive;

        Actor(String notionName, String genitive) {
            this.notionName = notionName;
            this.genitive = genitive;
        }

        Actor other() { return this == ANYA ? ALEX : ANYA; }
    }

    private enum View { MINE, ANYA, ALEX, ALL }

    private final NotionTasksClient notion;
    private final TaskBotTelegram telegram;
    private final Clock clock;
    private final String assigneeUsername;
    private final String ownerUsername;
    private final Map<Actor, Long> chatIds = new ConcurrentHashMap<>();
    private long offset = 0;

    @Autowired
    public TaskBotService(NotionTasksClient notion, TaskBotTelegram telegram,
                          @Value("${tasks.assignee-telegram-username:}") String assigneeUsername,
                          @Value("${tasks.owner-telegram-username:}") String ownerUsername,
                          @Value("${tasks.assignee-chat-id:}") String assigneeChatId,
                          @Value("${tasks.owner-chat-id:}") String ownerChatId) {
        this(notion, telegram, assigneeUsername, ownerUsername, assigneeChatId, ownerChatId, Clock.system(ZONE));
    }

    TaskBotService(NotionTasksClient notion, TaskBotTelegram telegram, String assigneeUsername, String ownerUsername,
                   String assigneeChatId, String ownerChatId, Clock clock) {
        this.notion = notion;
        this.telegram = telegram;
        this.clock = clock;
        this.assigneeUsername = normalize(assigneeUsername);
        this.ownerUsername = normalize(ownerUsername);
        if (assigneeChatId != null && !assigneeChatId.isBlank()) chatIds.put(Actor.ANYA, Long.parseLong(assigneeChatId.trim()));
        if (ownerChatId != null && !ownerChatId.isBlank()) chatIds.put(Actor.ALEX, Long.parseLong(ownerChatId.trim()));
    }

    public boolean enabled() {
        return notion.configured() && telegram.configured() && !assigneeUsername.isEmpty();
    }

    public void poll() throws Exception {
        JsonNode updates = telegram.getUpdates(offset);
        int batches = 0;
        while (!updates.isEmpty()) {
            long next = offset;
            for (JsonNode update : updates) {
                next = Math.max(next, update.path("update_id").asLong() + 1);
                try {
                    handleUpdate(update);
                } catch (Exception exception) {
                    log.warn("Task bot: failed to handle update {}: {}", update.path("update_id").asLong(), exception.getMessage());
                }
            }
            offset = next;
            // Leave the final batch for the next scheduled poll to acknowledge if traffic is sustained.
            if (++batches >= 20) return;
            // The next call acknowledges this batch and may itself return new updates. Process them too.
            updates = telegram.getUpdates(offset);
        }
    }

    void handleUpdate(JsonNode update) throws Exception {
        if (update.has("callback_query")) handleCallback(update.path("callback_query"));
        else if (update.has("message")) handleMessage(update.path("message"));
    }

    private void handleMessage(JsonNode message) throws Exception {
        long chatId = message.path("chat").path("id").asLong();
        if (!privateChat(message.path("chat"), message.path("from"))) return;
        Instant sent = Instant.ofEpochSecond(message.path("date").asLong());
        if (Duration.between(sent, clock.instant()).compareTo(MAX_MESSAGE_AGE) > 0) return;
        Actor actor = actor(message.path("from"), chatId);
        if (actor == null) return;
        chatIds.put(actor, chatId);
        String text = message.path("text").asText("").trim();

        if (text.startsWith("/start")) {
            telegram.sendMessage(chatId, "Привет, " + actor.notionName + "! 👋 Здесь твои задачи из Notion. "
                    + "Кнопками можно взять задачу в работу, завершить, сообщить о препятствии или перенести срок. "
                    + "Другой участник увидит твои изменения. Список и фильтры: /tasks", null);
            showTasks(chatId, actor, View.MINE);
            return;
        }
        if (text.equals("/tasks") || text.startsWith("/tasks ")) {
            showTasks(chatId, actor, commandView(text));
            return;
        }

        String blockedPageId = blockedReplyPageId(message);
        if (blockedPageId != null) {
            handleBlockedReply(chatId, actor, blockedPageId, text);
            return;
        }
        telegram.sendMessage(chatId, "Открой /tasks, чтобы посмотреть задачи и отметить работу. "
                + "Если сообщаешь о препятствии, ответь на вопрос бота под нужной задачей.", null);
    }

    private void handleBlockedReply(long chatId, Actor actor, String pageId, String text) throws Exception {
        if (text.isBlank()) {
            telegram.sendMessage(chatId, "Напиши одним сообщением, что мешает выполнить задачу.", null);
            return;
        }
        Task task = notion.get(pageId);
        if (!canAct(actor, task) || "Done".equals(task.status())) {
            telegram.sendMessage(chatId, "Эта задача уже завершена или назначена другому человеку. Открой /tasks для свежего списка.", null);
            return;
        }
        String reason = text.length() > 1500 ? text.substring(0, 1499) + "…" : text;
        if ("Blocked".equals(task.status()) && reason.equals(task.blocker())) {
            telegram.sendMessage(chatId, "Это препятствие уже записано в Notion.", null);
            return;
        }
        notion.setBlocker(pageId, reason);
        notifyOther(actor, "⛔ Препятствие: " + task.title() + "\nИсполнитель: " + actor.notionName
                + "\nЧто мешает: " + reason, pageId);
        telegram.sendMessage(chatId, "Записано в Notion и передано " + actor.other().genitive + " 🙏", null);
    }

    private void handleCallback(JsonNode callback) throws Exception {
        JsonNode message = callback.path("message");
        long chatId = message.path("chat").path("id").asLong();
        if (!privateChat(message.path("chat"), callback.path("from"))) return;
        Actor actor = actor(callback.path("from"), chatId);
        if (actor == null) return;
        chatIds.put(actor, chatId);
        String callbackId = callback.path("id").asText();
        String data = callback.path("data").asText("");

        if (data.startsWith("v|")) {
            View view = callbackView(data.substring(2));
            if (view != null) {
                telegram.answerCallback(callbackId, "Показываю задачи");
                showTasks(chatId, actor, view);
            }
            return;
        }

        String[] parts = data.split("\\|", -1);
        if (parts.length != 3 || !parts[0].equals("t") || parts[1].isBlank()) return;
        String pageId = parts[1];
        String action = parts[2];
        Task task = notion.get(pageId);
        if (!Actor.ANYA.notionName.equals(task.assignee()) && !Actor.ALEX.notionName.equals(task.assignee())) {
            telegram.answerCallback(callbackId, "Задача не назначена Ане или Алексу");
            return;
        }
        if (action.equals("i")) {
            telegram.answerCallback(callbackId, "Открываю детали");
            sendDetails(chatId, task, actor);
            return;
        }
        if (!canAct(actor, task)) {
            telegram.answerCallback(callbackId, "Действия доступны только текущему исполнителю");
            return;
        }
        if ("Done".equals(task.status())) {
            telegram.answerCallback(callbackId, "Задача уже завершена");
            return;
        }

        long messageId = message.path("message_id").asLong();
        switch (action) {
            case "d" -> {
                notion.setStatus(pageId, "Done");
                notifyOther(actor, "✅ Готово: " + task.title() + "\nИсполнитель: " + actor.notionName, pageId);
                telegram.answerCallback(callbackId, "Задача завершена ✅");
                telegram.editMessage(chatId, messageId, "✅ Готово: " + task.title());
            }
            case "p" -> {
                if ("In progress".equals(task.status())) {
                    telegram.answerCallback(callbackId, "Уже в работе");
                    return;
                }
                notion.setStatus(pageId, "In progress");
                notifyOther(actor, "🚧 В работе: " + task.title() + "\nИсполнитель: " + actor.notionName, pageId);
                telegram.answerCallback(callbackId, "Взято в работу 🚧");
                Task updated = withStatus(task, "In progress");
                telegram.editMessage(chatId, messageId, card(updated), buttons(updated, actor));
            }
            case "b" -> {
                telegram.answerCallback(callbackId, "Ответь на вопрос бота");
                telegram.askBlocker(chatId, task.title(), pageId);
            }
            case "l" -> {
                LocalDate tomorrow = today().plusDays(1);
                if (task.due() != null && !task.due().isBefore(tomorrow)) {
                    telegram.answerCallback(callbackId, "Срок уже завтра или позже");
                    return;
                }
                notion.setDue(pageId, tomorrow);
                notifyOther(actor, "⏰ Срок перенесён на завтра (" + DAY.format(tomorrow) + "): "
                        + task.title() + "\nИсполнитель: " + actor.notionName, pageId);
                telegram.answerCallback(callbackId, "Срок перенесён ⏰");
                Task updated = new Task(task.id(), task.title(), task.status(), task.assignee(), tomorrow,
                        task.doneWhen(), task.blocker(), task.lastEdited());
                telegram.editMessage(chatId, messageId, card(updated), buttons(updated, actor));
            }
            default -> telegram.answerCallback(callbackId, "Неизвестное действие");
        }
    }

    public void sendMorningDigest() throws Exception {
        for (Actor actor : Actor.values()) {
            Long chatId = chatIds.get(actor);
            if (chatId == null) continue;
            LocalDate horizon = today().plusDays(2);
            List<Task> tasks = notion.openTasksFor(actor.notionName).stream()
                    .filter(task -> task.due() == null || !task.due().isAfter(horizon)).toList();
            if (tasks.isEmpty()) continue;
            sendCards(chatId, actor, tasks, "Доброе утро, " + actor.notionName + "! ☀️ Твои задачи на ближайшие дни:");
        }
    }

    public void sendEveningOverdue() throws Exception {
        for (Actor actor : Actor.values()) {
            List<Task> overdue = notion.openTasksFor(actor.notionName).stream()
                    .filter(task -> task.due() != null && task.due().isBefore(today())).toList();
            if (overdue.isEmpty()) continue;
            Long chatId = chatIds.get(actor);
            if (chatId != null) sendCards(chatId, actor, overdue,
                    "⚠️ " + actor.notionName + ", эти задачи просрочены:");
            StringBuilder summary = new StringBuilder("⚠️ Просрочено у ").append(actor.genitive).append(":\n");
            for (Task task : overdue) summary.append("\n• ").append(task.title()).append(" (")
                    .append(dueLabel(task.due())).append(")").append(statusSuffix(task));
            notifyOther(actor, summary.toString(), detailButtons(overdue));
        }
    }

    public void sendWeeklySummary() throws Exception {
        Instant weekAgo = clock.instant().minus(7, ChronoUnit.DAYS);
        List<Task> anya = notion.allTasksFor(Actor.ANYA.notionName);
        List<Task> alex = notion.allTasksFor(Actor.ALEX.notionName);
        StringBuilder summary = new StringBuilder("📋 Задачи за неделю\n");
        weeklyFor(summary, Actor.ANYA, anya, weekAgo);
        weeklyFor(summary, Actor.ALEX, alex, weekAgo);
        List<Task> open = new ArrayList<>();
        open.addAll(anya.stream().filter(task -> !"Done".equals(task.status())).toList());
        open.addAll(alex.stream().filter(task -> !"Done".equals(task.status())).toList());
        for (Actor recipient : Actor.values()) {
            Long chatId = chatIds.get(recipient);
            if (chatId != null) telegram.sendMessage(chatId, summary.toString(), detailButtons(open));
        }
    }

    private void weeklyFor(StringBuilder summary, Actor actor, List<Task> all, Instant weekAgo) {
        summary.append("\n").append(actor.notionName).append(":\n");
        int start = summary.length();
        section(summary, "✅ Сделано", all.stream().filter(task -> "Done".equals(task.status())
                && task.lastEdited() != null && task.lastEdited().isAfter(weekAgo)).toList(), false);
        section(summary, "⛔ Препятствия", all.stream().filter(task -> "Blocked".equals(task.status())).toList(), true);
        section(summary, "⚠️ Просрочено", all.stream().filter(task -> !"Done".equals(task.status())
                && task.due() != null && task.due().isBefore(today())).toList(), false);
        section(summary, "🚧 В работе", all.stream().filter(task -> "In progress".equals(task.status())).toList(), false);
        section(summary, "📌 Впереди", all.stream().filter(task -> "To do".equals(task.status())
                && (task.due() == null || !task.due().isBefore(today()))).toList(), false);
        if (summary.length() == start) summary.append("Пока нет задач.\n");
    }

    private void showTasks(long chatId, Actor viewer, View view) throws Exception {
        List<Task> tasks = switch (view) {
            case MINE -> notion.openTasksFor(viewer.notionName);
            case ANYA -> notion.openTasksFor(Actor.ANYA.notionName);
            case ALEX -> notion.openTasksFor(Actor.ALEX.notionName);
            case ALL -> {
                List<Task> combined = new ArrayList<>(notion.openTasksFor(Actor.ANYA.notionName));
                combined.addAll(notion.openTasksFor(Actor.ALEX.notionName));
                combined.sort(Comparator.comparing(Task::due, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::title));
                yield combined;
            }
        };
        String heading = switch (view) {
            case MINE -> "Твои открытые задачи:";
            case ANYA -> "Открытые задачи Ани:";
            case ALEX -> "Открытые задачи Алекса:";
            case ALL -> "Все открытые задачи — Аня и Алекс:";
        };
        sendCards(chatId, viewer, tasks, heading);
    }

    private void sendCards(long chatId, Actor viewer, List<Task> tasks, String heading) throws Exception {
        telegram.sendMessage(chatId, tasks.isEmpty() ? heading + "\nПока нет открытых задач 🎉" : heading,
                viewButtons());
        for (Task task : tasks) telegram.sendMessage(chatId, card(task), buttons(task, viewer));
    }

    private static List<List<Button>> viewButtons() {
        return List.of(
                List.of(new Button("👤 Мои", "v|m"), new Button("👥 Все", "v|all")),
                List.of(new Button("👩 Аня", "v|a"), new Button("👨 Алекс", "v|x")));
    }

    private static View callbackView(String value) {
        return switch (value) {
            case "m" -> View.MINE;
            case "a" -> View.ANYA;
            case "x" -> View.ALEX;
            case "all" -> View.ALL;
            default -> null;
        };
    }

    private static View commandView(String command) {
        String argument = command.substring(6).trim().toLowerCase(Locale.ROOT);
        return switch (argument) {
            case "anna", "anya", "анна", "аня" -> View.ANYA;
            case "alex", "алекс" -> View.ALEX;
            case "all", "все" -> View.ALL;
            default -> View.MINE;
        };
    }

    String card(Task task) {
        StringBuilder text = new StringBuilder("📌 ").append(task.title())
                .append("\nИсполнитель: ").append(task.assignee());
        if (task.due() != null) text.append("\nСрок: ").append(DAY.format(task.due()))
                .append(" (").append(dueLabel(task.due())).append(")");
        if (!task.doneWhen().isBlank()) text.append("\nГотово, когда: ").append(task.doneWhen());
        if ("In progress".equals(task.status())) text.append("\nСейчас: в работе 🚧");
        if ("Blocked".equals(task.status())) text.append("\nСейчас: есть препятствие ⛔");
        return text.toString();
    }

    static List<List<Button>> buttons(Task task, Actor viewer) {
        List<List<Button>> rows = new ArrayList<>();
        if (canAct(viewer, task) && !"Done".equals(task.status())) rows.addAll(actionButtons(task.id()));
        rows.add(List.of(new Button("📄 Подробнее", "t|" + task.id() + "|i"),
                Button.link("↗️ Notion", NotionTasksClient.pageUrl(task.id()))));
        return rows;
    }

    private static List<List<Button>> actionButtons(String id) {
        return List.of(
                List.of(new Button("✅ Готово", "t|" + id + "|d"), new Button("🚧 В работу", "t|" + id + "|p")),
                List.of(new Button("⛔ Препятствие", "t|" + id + "|b"), new Button("⏰ На завтра", "t|" + id + "|l")));
    }

    static List<List<Button>> detailButtons(List<Task> tasks) {
        List<List<Button>> rows = new ArrayList<>();
        for (Task task : tasks.stream().limit(20).toList()) {
            String title = task.title().length() > 40 ? task.title().substring(0, 39) + "…" : task.title();
            rows.add(List.of(new Button("📄 " + title, "t|" + task.id() + "|i")));
        }
        return rows;
    }

    void sendDetails(long chatId, Task task, Actor viewer) throws Exception {
        StringBuilder html = new StringBuilder("<b>📌 ").append(NotionTasksClient.escapeHtml(task.title())).append("</b>\n");
        html.append("\n<b>Исполнитель:</b> ").append(NotionTasksClient.escapeHtml(task.assignee()));
        html.append("\n<b>Статус:</b> ").append(statusRu(task.status()));
        if (task.due() != null) html.append("\n<b>Срок:</b> ").append(DAY.format(task.due()))
                .append(" (").append(dueLabel(task.due())).append(")");
        if (!task.doneWhen().isBlank()) html.append("\n<b>Готово, когда:</b> ").append(NotionTasksClient.escapeHtml(task.doneWhen()));
        if (!task.blocker().isBlank()) html.append("\n<b>Что мешает:</b> ").append(NotionTasksClient.escapeHtml(task.blocker()));
        String body = notion.bodyHtml(task.id());
        if (!body.isBlank()) {
            if (html.length() + body.length() <= 3900) html.append("\n\n").append(body);
            else html.append("\n\nПодробные шаги — в Notion.");
        }
        String output = html.length() <= 3900 ? html.toString()
                : "<b>📌 " + NotionTasksClient.escapeHtml(task.title().substring(0, Math.min(task.title().length(), 500)))
                        + "</b>\nПодробности — в Notion.";
        List<List<Button>> keyboard = new ArrayList<>();
        if (canAct(viewer, task) && !"Done".equals(task.status())) keyboard.addAll(actionButtons(task.id()));
        keyboard.add(List.of(Button.link("↗️ Открыть в Notion", NotionTasksClient.pageUrl(task.id()))));
        telegram.sendHtml(chatId, output, keyboard);
    }

    private String dueLabel(LocalDate due) {
        long days = ChronoUnit.DAYS.between(today(), due);
        if (days == 0) return "сегодня";
        if (days == 1) return "завтра";
        if (days < 0) return "просрочено на " + (-days) + " дн.";
        return "через " + days + " дн.";
    }

    private static String statusRu(String status) {
        return switch (status) {
            case "Done" -> "сделано ✅";
            case "In progress" -> "в работе 🚧";
            case "Blocked" -> "есть препятствие ⛔";
            default -> "не начато";
        };
    }

    private static String statusSuffix(Task task) {
        return switch (task.status()) {
            case "In progress" -> " 🚧";
            case "Blocked" -> " ⛔" + (task.blocker().isBlank() ? "" : " " + task.blocker());
            default -> "";
        };
    }

    private void section(StringBuilder summary, String heading, List<Task> tasks, boolean withBlocker) {
        if (tasks.isEmpty()) return;
        summary.append("\n").append(heading).append(":\n");
        for (Task task : tasks) {
            summary.append("• ").append(task.title());
            if (task.due() != null && !"Done".equals(task.status())) summary.append(" (").append(dueLabel(task.due())).append(")");
            if (withBlocker && !task.blocker().isBlank()) summary.append(": ").append(task.blocker());
            summary.append("\n");
        }
    }

    private void notifyOther(Actor actor, String text, String pageId) {
        notifyOther(actor, text, List.of(List.of(new Button("📄 Подробнее", "t|" + pageId + "|i"))));
    }

    private void notifyOther(Actor actor, String text, List<List<Button>> keyboard) {
        Long chatId = chatIds.get(actor.other());
        if (chatId == null) {
            log.info("Task bot: {} has not started the bot; counterpart notice skipped", actor.other().notionName);
            return;
        }
        try {
            telegram.sendMessage(chatId, text, keyboard);
        } catch (Exception exception) {
            log.warn("Task bot: counterpart notice failed for {}: {}", actor.other().notionName, exception.getMessage());
        }
    }

    private static boolean canAct(Actor actor, Task task) {
        return actor.notionName.equals(task.assignee());
    }

    private Actor actor(JsonNode from, long chatId) {
        String username = normalize(from.path("username").asText(""));
        if (matchesActor(Actor.ANYA, username, chatId)) return Actor.ANYA;
        if (matchesActor(Actor.ALEX, username, chatId)) return Actor.ALEX;
        return null;
    }

    private boolean matchesActor(Actor actor, String username, long chatId) {
        Long knownChatId = chatIds.get(actor);
        if (knownChatId != null) return knownChatId == chatId;
        String configuredUsername = actor == Actor.ANYA ? assigneeUsername : ownerUsername;
        return !configuredUsername.isEmpty() && configuredUsername.equals(username);
    }

    private static boolean privateChat(JsonNode chat, JsonNode from) {
        return "private".equals(chat.path("type").asText())
                && chat.path("id").asLong() > 0
                && chat.path("id").asLong() == from.path("id").asLong();
    }

    private static String blockedReplyPageId(JsonNode message) {
        JsonNode replied = message.path("reply_to_message");
        if (!replied.path("from").path("is_bot").asBoolean()
                || !replied.path("text").asText("").startsWith("Что мешает с задачей")) return null;
        for (JsonNode entity : replied.path("entities")) {
            if (!"text_link".equals(entity.path("type").asText())) continue;
            Matcher match = NOTION_LINK.matcher(entity.path("url").asText(""));
            if (match.matches()) return match.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static Task withStatus(Task task, String status) {
        return new Task(task.id(), task.title(), status, task.assignee(), task.due(), task.doneWhen(), "", task.lastEdited());
    }

    private LocalDate today() { return clock.instant().atZone(ZONE).toLocalDate(); }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    }
}
