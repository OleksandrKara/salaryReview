package com.salonreview.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salonreview.tasks.NotionTasksClient.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskBotServiceTest {
    private static final long ANYA = 111L;
    private static final long ALEX = 222L;
    private static final String ANYA_ID = "3ed7aea26d5c81269c31dfa5b6e54571";
    private static final String ALEX_ID = "3ed7aea26d5c81269c31dfa5b6e54572";
    private static final String SOURCE_ID = "3ed7aea26d5c81269c31dfa5b6e54573";
    private static final Instant NOW = Instant.parse("2026-10-02T17:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private NotionTasksClient notion;
    private TaskBotTelegram telegram;
    private TaskBotService bot;

    private static Task task(String id, String title, String assignee, String status, LocalDate due) {
        return new Task(id, title, status, assignee, due, "Готово, когда X", "", NOW);
    }

    private static Task anyaTask() {
        return task(ANYA_ID, "Визитки", "Аня", "To do", LocalDate.of(2026, 10, 3));
    }

    private static Task alexTask() {
        return task(ALEX_ID, "Проверить сайт", "Алекс", "To do", LocalDate.of(2026, 10, 3));
    }

    @BeforeEach
    void setUp() {
        notion = mock(NotionTasksClient.class);
        telegram = mock(TaskBotTelegram.class);
        bot = new TaskBotService(notion, telegram, "@AnnaKara87", "alexkara", String.valueOf(ANYA),
                String.valueOf(ALEX), Clock.fixed(NOW, TaskBotService.ZONE));
    }

    private static JsonNode message(long chatId, String username, String text) {
        ObjectNode root = JSON.createObjectNode();
        root.put("update_id", 1);
        ObjectNode msg = root.putObject("message");
        msg.put("message_id", 5).put("date", NOW.getEpochSecond()).put("text", text);
        msg.putObject("chat").put("id", chatId).put("type", "private");
        msg.putObject("from").put("id", chatId).put("username", username);
        return root;
    }

    private static JsonNode press(long chatId, String username, String data) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode cb = root.putObject("callback_query");
        cb.put("id", "cb1").put("data", data);
        cb.putObject("from").put("id", chatId).put("username", username);
        cb.putObject("message").put("message_id", 9).putObject("chat").put("id", chatId).put("type", "private");
        return root;
    }

    private static JsonNode blockerReply(long chatId, String username, String pageId, String reason) {
        ObjectNode root = (ObjectNode) message(chatId, username, reason);
        ObjectNode replied = ((ObjectNode) root.path("message")).putObject("reply_to_message");
        replied.put("text", "Что мешает с задачей «Задача»? Ответь на это сообщение одним текстом.");
        replied.putObject("from").put("is_bot", true);
        replied.putArray("entities").addObject().put("type", "text_link")
                .put("url", NotionTasksClient.pageUrl(pageId));
        return root;
    }

    @Test
    void personalAndCombinedViewsShowExactAssignees() throws Exception {
        when(notion.openTasksFor("Аня")).thenReturn(List.of(anyaTask()));
        when(notion.openTasksFor("Алекс")).thenReturn(List.of(alexTask()));
        bot.handleUpdate(message(ALEX, "alexkara", "/start"));
        bot.handleUpdate(message(ANYA, "annakara87", "/tasks"));
        bot.handleUpdate(message(ALEX, "alexkara", "/tasks all"));

        verify(telegram).sendMessage(eq(ALEX), eq("Твои открытые задачи:"), any());
        verify(telegram).sendMessage(eq(ANYA), eq("Твои открытые задачи:"), any());
        verify(telegram).sendMessage(eq(ALEX), eq("Все открытые задачи — Аня и Алекс:"), any());
        verify(telegram, times(2)).sendMessage(eq(ALEX), eq(bot.card(alexTask())), any());
        verify(telegram).sendMessage(eq(ALEX), eq(bot.card(anyaTask())), any());
        assertThat(TaskBotService.buttons(anyaTask(), TaskBotService.Actor.ALEX)).hasSize(1);
        assertThat(TaskBotService.buttons(alexTask(), TaskBotService.Actor.ALEX)).hasSize(3);
    }

    @Test
    void filterButtonShowsCounterpartTasks() throws Exception {
        when(notion.openTasksFor("Алекс")).thenReturn(List.of(alexTask()));
        bot.handleUpdate(press(ANYA, "annakara87", "v|x"));
        verify(telegram).sendMessage(eq(ANYA), eq("Открытые задачи Алекса:"), any());
        verify(telegram).sendMessage(eq(ANYA), eq(bot.card(alexTask())), any());
    }

    @Test
    void bothExecutorsCanFinishOwnTasksAndCounterpartGetsNotice() throws Exception {
        when(notion.get(ALEX_ID)).thenReturn(alexTask());
        when(notion.get(ANYA_ID)).thenReturn(anyaTask());
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|d"));
        bot.handleUpdate(press(ANYA, "annakara87", "t|" + ANYA_ID + "|d"));

        verify(notion).setStatus(ALEX_ID, "Done");
        verify(notion).setStatus(ANYA_ID, "Done");
        verify(telegram).sendMessage(eq(ANYA), eq("✅ Готово: Проверить сайт\nИсполнитель: Алекс"), any());
        verify(telegram).sendMessage(eq(ALEX), eq("✅ Готово: Визитки\nИсполнитель: Аня"), any());
    }

    @Test
    void pickupIsIdempotentAndNotifiesAnya() throws Exception {
        when(notion.get(ALEX_ID)).thenReturn(alexTask(),
                task(ALEX_ID, "Проверить сайт", "Алекс", "In progress", LocalDate.of(2026, 10, 3)));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|p"));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|p"));

        verify(notion).setStatus(ALEX_ID, "In progress");
        verify(telegram).sendMessage(eq(ANYA), eq("🚧 В работе: Проверить сайт\nИсполнитель: Алекс"), any());
        verify(telegram).editMessage(eq(ALEX), eq(9L), anyString(), any());
        verify(telegram).answerCallback("cb1", "Уже в работе");
    }

    @Test
    void staleAndOtherPersonsButtonsCannotMutate() throws Exception {
        when(notion.get(ANYA_ID)).thenReturn(anyaTask());
        when(notion.get(ALEX_ID)).thenReturn(task(ALEX_ID, "Проверить сайт", "Аня", "To do", null));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ANYA_ID + "|d"));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|d"));
        verify(notion, never()).setStatus(anyString(), anyString());
        verify(telegram, times(2)).answerCallback("cb1", "Действия доступны только текущему исполнителю");
    }

    @Test
    void claudeTasksCannotBeOpenedOrChangedThroughCallbacks() throws Exception {
        when(notion.get(ALEX_ID)).thenReturn(task(ALEX_ID, "Private", "Claude", "To do", null));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|i"));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|d"));
        verify(telegram, times(2)).answerCallback("cb1", "Задача не назначена Ане или Алексу");
        verify(telegram, never()).sendHtml(anyLong(), anyString(), any());
        verify(notion, never()).setStatus(anyString(), anyString());
    }

    @Test
    void blockedReasonReplyWorksAcrossReplicas() throws Exception {
        when(notion.get(ALEX_ID)).thenReturn(alexTask());
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|b"));
        verify(telegram).askBlocker(ALEX, "Проверить сайт", ALEX_ID);
        TaskBotService otherReplica = new TaskBotService(notion, telegram, "@AnnaKara87", "alexkara",
                String.valueOf(ANYA), String.valueOf(ALEX), Clock.fixed(NOW, TaskBotService.ZONE));
        otherReplica.handleUpdate(blockerReply(ALEX, "alexkara", ALEX_ID, "Нет доступа"));
        verify(notion).setBlocker(ALEX_ID, "Нет доступа");
        verify(telegram).sendMessage(eq(ANYA),
                eq("⛔ Препятствие: Проверить сайт\nИсполнитель: Алекс\nЧто мешает: Нет доступа"), any());
    }

    @Test
    void anyaCanReportBlockerAndAlexGetsNotice() throws Exception {
        when(notion.get(ANYA_ID)).thenReturn(anyaTask());
        bot.handleUpdate(blockerReply(ANYA, "annakara87", ANYA_ID, "Жду материалы"));
        verify(notion).setBlocker(ANYA_ID, "Жду материалы");
        verify(telegram).sendMessage(eq(ALEX),
                eq("⛔ Препятствие: Визитки\nИсполнитель: Аня\nЧто мешает: Жду материалы"), any());
    }

    @Test
    void blockerPromptEscapesTitleAndRequestsTelegramReply() {
        var prompt = TaskBotTelegram.blockerPrompt(ALEX, "Сайт <новый>", ALEX_ID);
        assertThat(prompt.get("text")).asString().contains("Сайт &lt;новый&gt;")
                .contains(NotionTasksClient.pageUrl(ALEX_ID));
        assertThat(prompt.get("parse_mode")).isEqualTo("HTML");
        assertThat(prompt.get("reply_markup")).isEqualTo(java.util.Map.of(
                "force_reply", true, "input_field_placeholder", "Что мешает?"));
    }

    @Test
    void unrelatedOrReassignedBlockedReplyCannotMutate() throws Exception {
        bot.handleUpdate(message(ALEX, "alexkara", "Нет доступа"));
        when(notion.get(ALEX_ID)).thenReturn(task(ALEX_ID, "Проверить сайт", "Аня", "To do", null));
        bot.handleUpdate(blockerReply(ALEX, "alexkara", ALEX_ID, "Нет доступа"));
        verify(notion, never()).setBlocker(anyString(), anyString());
        verify(telegram).sendMessage(eq(ALEX),
                eq("Эта задача уже завершена или назначена другому человеку. Открой /tasks для свежего списка."), any());
    }

    @Test
    void repeatedBlockedReplyDoesNotNotifyAgain() throws Exception {
        when(notion.get(ANYA_ID)).thenReturn(new Task(ANYA_ID, "Визитки", "Blocked", "Аня", null,
                "Готово, когда X", "Нет доступа", NOW));
        bot.handleUpdate(blockerReply(ANYA, "annakara87", ANYA_ID, "Нет доступа"));
        verify(notion, never()).setBlocker(anyString(), anyString());
        verify(telegram, never()).sendMessage(eq(ALEX), anyString(), any());
    }

    @Test
    void alexCanMoveHisDeadlineToTomorrow() throws Exception {
        when(notion.get(ALEX_ID)).thenReturn(task(ALEX_ID, "Проверить сайт", "Алекс", "To do",
                LocalDate.of(2026, 9, 30)));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|l"));
        verify(notion).setDue(ALEX_ID, LocalDate.of(2026, 10, 3));
        verify(telegram).sendMessage(eq(ANYA), org.mockito.ArgumentMatchers.startsWith("⏰ Срок перенесён на завтра"), any());
    }

    @Test
    void detailsShowAssigneeAndActionsOnlyToExecutor() throws Exception {
        when(notion.get(ALEX_ID)).thenReturn(alexTask());
        when(notion.bodyHtml(ALEX_ID)).thenReturn("<b>Шаги</b>\n1. Цены");
        bot.handleUpdate(press(ANYA, "annakara87", "t|" + ALEX_ID + "|i"));
        bot.handleUpdate(press(ALEX, "alexkara", "t|" + ALEX_ID + "|i"));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> keyboards = ArgumentCaptor.forClass(List.class);
        verify(telegram, times(2)).sendHtml(anyLong(), html.capture(), keyboards.capture());
        assertThat(html.getAllValues().get(0)).contains("<b>Исполнитель:</b> Алекс", "1. Цены");
        assertThat(keyboards.getAllValues().get(0)).hasSize(1);
        assertThat(keyboards.getAllValues().get(1)).hasSize(3);
    }

    @Test
    void digestsAndWeeklySummaryCoverBothPeople() throws Exception {
        when(notion.openTasksFor("Аня")).thenReturn(List.of(task(ANYA_ID, "Визитки", "Аня", "To do",
                LocalDate.of(2026, 9, 30))));
        when(notion.openTasksFor("Алекс")).thenReturn(List.of(task(ALEX_ID, "Сайт", "Алекс", "Blocked",
                LocalDate.of(2026, 9, 29))));
        when(notion.allTasksFor("Аня")).thenReturn(List.of(anyaTask()));
        when(notion.allTasksFor("Алекс")).thenReturn(List.of(alexTask()));
        bot.sendMorningDigest();
        bot.sendEveningOverdue();
        bot.sendWeeklySummary();

        verify(telegram).sendMessage(eq(ANYA), eq("Доброе утро, Аня! ☀️ Твои задачи на ближайшие дни:"), any());
        verify(telegram).sendMessage(eq(ALEX), eq("Доброе утро, Алекс! ☀️ Твои задачи на ближайшие дни:"), any());
        verify(telegram).sendMessage(eq(ANYA), org.mockito.ArgumentMatchers.startsWith("⚠️ Просрочено у Алекса"), any());
        verify(telegram).sendMessage(eq(ALEX), org.mockito.ArgumentMatchers.startsWith("⚠️ Просрочено у Ани"), any());
        verify(telegram).sendMessage(eq(ANYA), org.mockito.ArgumentMatchers.contains("Алекс:\n"), any());
        verify(telegram).sendMessage(eq(ALEX), org.mockito.ArgumentMatchers.contains("Аня:\n"), any());
    }

    @Test
    void acknowledgementResponseIsAlsoProcessed() throws Exception {
        ObjectNode first = (ObjectNode) message(ALEX, "alexkara", "/tasks");
        first.put("update_id", 41);
        ObjectNode second = (ObjectNode) message(ANYA, "annakara87", "/tasks");
        second.put("update_id", 42);
        when(telegram.getUpdates(0)).thenReturn(JSON.createArrayNode().add(first));
        when(telegram.getUpdates(42)).thenReturn(JSON.createArrayNode().add(second));
        when(telegram.getUpdates(43)).thenReturn(JSON.createArrayNode());
        when(notion.openTasksFor("Аня")).thenReturn(List.of());
        when(notion.openTasksFor("Алекс")).thenReturn(List.of());
        bot.poll();
        verify(telegram).getUpdates(43);
        verify(telegram).sendMessage(eq(ALEX), org.mockito.ArgumentMatchers.startsWith("Твои открытые задачи:"), any());
        verify(telegram).sendMessage(eq(ANYA), org.mockito.ArgumentMatchers.startsWith("Твои открытые задачи:"), any());
    }

    @Test
    void strangersAndGroupMessagesAreIgnored() throws Exception {
        bot.handleUpdate(message(999L, "someone", "/tasks"));
        bot.handleUpdate(message(999L, "alexkara", "/tasks"));
        ObjectNode group = (ObjectNode) message(ALEX, "alexkara", "/tasks");
        ((ObjectNode) group.path("message").path("chat")).put("type", "group");
        bot.handleUpdate(group);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any());
    }

    @Test
    void oldQueuedMessagesAreIgnored() throws Exception {
        ObjectNode old = (ObjectNode) message(ANYA, "annakara87", "/start");
        ((ObjectNode) old.path("message")).put("date", NOW.minusSeconds(7200).getEpochSecond());
        bot.handleUpdate(old);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any());
    }

    @Test
    void directPageMustBelongToConfiguredNotionSource() throws Exception {
        NotionTasksClient client = new NotionTasksClient("dummy", SOURCE_ID);
        JsonNode page = JSON.readTree("""
                {"id":"3ed7aea2-6d5c-8126-9c31-dfa5b6e54571","parent":{"type":"data_source_id",
                 "data_source_id":"3ed7aea2-6d5c-8126-9c31-dfa5b6e54573"},
                 "last_edited_time":"2026-10-02T20:00:00.000Z","properties":{
                 "Задача":{"title":[{"plain_text":"VIP-визитки"}]},
                 "Статус":{"select":{"name":"To do"}},
                 "Исполнитель":{"select":{"name":"Аня"}},
                 "Срок":{"date":{"start":"2026-10-05"}},
                 "Готово, когда":{"rich_text":[{"plain_text":"В салоне"}]},
                 "Где застряла":{"rich_text":[]}}}""");
        assertThat(client.parseScoped(page).title()).isEqualTo("VIP-визитки");
        ((ObjectNode) page.path("parent")).put("data_source_id", "00000000000000000000000000000000");
        assertThatThrownBy(() -> client.parseScoped(page)).isInstanceOf(IOException.class)
                .hasMessageContaining("outside the configured data source");
    }

    @Test
    void notionBlocksBecomeSafeTelegramHtml() throws Exception {
        JsonNode blocks = JSON.readTree("""
                [{"type":"heading_2","heading_2":{"rich_text":[{"plain_text":"Шаги","annotations":{}}]}},
                 {"type":"numbered_list_item","numbered_list_item":{"rich_text":[{"plain_text":"Цены","annotations":{"bold":true}}]}},
                 {"type":"bulleted_list_item","bulleted_list_item":{"rich_text":[{"plain_text":"21+ & ID","annotations":{}}]}}]""");
        assertThat(NotionTasksClient.blocksToHtml(blocks)).isEqualTo(
                "<b>Шаги</b>\n1. <b>Цены</b>\n• 21+ &amp; ID");
    }
}
