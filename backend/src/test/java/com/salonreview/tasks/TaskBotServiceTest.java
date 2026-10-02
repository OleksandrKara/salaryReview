package com.salonreview.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.tasks.NotionTasksClient.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskBotServiceTest {

    private static final long ANYA = 111L;
    private static final long ALEX = 222L;
    // Friday 2026-10-02, 10:00 PT
    private static final Instant NOW = Instant.parse("2026-10-02T17:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private NotionTasksClient notion;
    private TaskBotTelegram telegram;
    private TaskBotService bot;

    private static Task task(String id, String title, String status, LocalDate due) {
        return new Task(id, title, status, "Аня", due, "Готово, когда X", "", NOW);
    }

    @BeforeEach
    void setUp() {
        notion = mock(NotionTasksClient.class);
        telegram = mock(TaskBotTelegram.class);
        bot = new TaskBotService(notion, telegram, "@AnnaKara87", "alexkara", "", String.valueOf(ALEX),
                Clock.fixed(NOW, TaskBotService.ZONE));
    }

    private JsonNode message(long chatId, String username, String text) throws Exception {
        return JSON.readTree("""
                {"update_id":1,"message":{"message_id":5,"date":%d,"chat":{"id":%d,"type":"private"},
                 "from":{"id":%d,"username":"%s"},"text":"%s"}}""".formatted(NOW.getEpochSecond(), chatId, chatId, username, text));
    }

    private JsonNode press(long chatId, String username, String data) throws Exception {
        return JSON.readTree("""
                {"update_id":2,"callback_query":{"id":"cb1","data":"%s","from":{"id":%d,"username":"%s"},
                 "message":{"message_id":9,"chat":{"id":%d,"type":"private"}}}}""".formatted(data, chatId, username, chatId));
    }

    @Test
    @DisplayName("/start from Anya (username matched case-insensitively, @ ignored): welcome + her tasks due within 2 days")
    void startSendsDueTasks() throws Exception {
        when(notion.openTasksFor("Аня")).thenReturn(List.of(
                task("a1", "Facebook часы", "To do", LocalDate.of(2026, 10, 3)),
                task("a2", "Студентки PMU", "To do", LocalDate.of(2026, 10, 9))));

        bot.handleUpdate(message(ANYA, "annakara87", "/start"));

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(3)).sendMessage(eq(ANYA), texts.capture(), any());
        assertThat(texts.getAllValues().get(0)).contains("Привет, Аня");
        assertThat(texts.getAllValues().get(2)).contains("Facebook часы").contains("завтра");
        assertThat(String.join("\n", texts.getAllValues())).doesNotContain("Студентки PMU");
    }

    @Test
    @DisplayName("strangers are ignored")
    void strangerIgnored() throws Exception {
        bot.handleUpdate(message(999L, "someone", "/start"));
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("✅ Done: Notion status Done, message edited, owner told")
    void doneButton() throws Exception {
        bot.handleUpdate(message(ANYA, "annakara87", "hi"));
        when(notion.get("abc")).thenReturn(task("abc", "Визитки", "To do", LocalDate.of(2026, 10, 5)));

        bot.handleUpdate(press(ANYA, "annakara87", "t|abc|d"));

        verify(notion).setStatus("abc", "Done");
        verify(telegram).editMessage(ANYA, 9, "✅ Сделано: Визитки");
        verify(telegram).sendMessage(eq(ALEX), eq("✅ Аня сделала: Визитки"), isNull());
    }

    @Test
    @DisplayName("⛔ Stuck: asks why, saves her next message to Notion and forwards it to the owner")
    void blockedFlow() throws Exception {
        when(notion.get("abc")).thenReturn(task("abc", "Визитки", "To do", LocalDate.of(2026, 10, 5)));

        bot.handleUpdate(press(ANYA, "annakara87", "t|abc|b"));
        bot.handleUpdate(message(ANYA, "annakara87", "Нет доступа к Vistaprint"));

        verify(notion).setBlocker("abc", "Нет доступа к Vistaprint");
        verify(telegram).sendMessage(eq(ALEX), eq("⛔ Аня застряла: Визитки\nЧто мешает: Нет доступа к Vistaprint"), isNull());
    }

    @Test
    @DisplayName("⏰ Tomorrow: due date moves to tomorrow in Pacific time")
    void laterButton() throws Exception {
        when(notion.get("abc")).thenReturn(task("abc", "Визитки", "To do", LocalDate.of(2026, 10, 2)));
        bot.handleUpdate(press(ANYA, "annakara87", "t|abc|l"));
        verify(notion).setDue("abc", LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("buttons from the owner don't change anything")
    void ownerCannotPressAnyasButtons() throws Exception {
        bot.handleUpdate(press(ALEX, "alexkara", "t|abc|d"));
        verify(notion, never()).setStatus(anyString(), anyString());
        verify(telegram).answerCallback("cb1", "Эти кнопки только для Ани");
    }

    @Test
    @DisplayName("evening: only overdue tasks, to the owner and back to Anya as cards")
    void eveningOverdue() throws Exception {
        bot.handleUpdate(message(ANYA, "annakara87", "hi"));
        when(notion.openTasksFor("Аня")).thenReturn(List.of(
                task("a1", "Отзывы", "To do", LocalDate.of(2026, 9, 30)),
                task("a2", "Визитки", "To do", LocalDate.of(2026, 10, 5))));

        bot.sendEveningOverdue();

        verify(telegram).sendMessage(eq(ALEX), eq("⚠️ Просрочено у Ани:\n\n• Отзывы (просрочено на 2 дн.)"), isNull());
        verify(telegram).sendMessage(eq(ANYA), any(), eq(TaskBotService.buttons(task("a1", "Отзывы", "To do", null))));
    }

    @Test
    @DisplayName("morning digest waits until Anya has pressed Start")
    void digestNeedsStart() throws Exception {
        bot.sendMorningDigest();
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("old messages queued before the bot started are not replayed")
    void oldMessagesIgnored() throws Exception {
        JsonNode old = JSON.readTree("""
                {"update_id":1,"message":{"message_id":5,"date":%d,"chat":{"id":%d,"type":"private"},
                 "from":{"id":%d,"username":"annakara87"},"text":"/start"}}""".formatted(NOW.getEpochSecond() - 7200, ANYA, ANYA));
        bot.handleUpdate(old);
        verify(telegram, never()).sendMessage(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("poll acknowledges processed updates right away")
    void pollAcks() throws Exception {
        JsonNode updates = JSON.readTree("[{\"update_id\":41,\"message\":{\"chat\":{\"type\":\"group\"}}}]");
        when(telegram.getUpdates(0)).thenReturn(updates);
        when(telegram.getUpdates(42)).thenReturn(JSON.readTree("[]"));
        bot.poll();
        verify(telegram).getUpdates(42);
    }

    @Test
    @DisplayName("Notion page JSON parses into a task")
    void parseNotionPage() throws Exception {
        JsonNode page = JSON.readTree("""
                {"id":"3ed7aea2-6d5c-8126-9c31-dfa5b6e54571","last_edited_time":"2026-10-02T20:00:00.000Z","properties":{
                 "Задача":{"title":[{"plain_text":"VIP-визитки"}]},
                 "Статус":{"select":{"name":"To do"}},
                 "Исполнитель":{"select":{"name":"Аня"}},
                 "Срок":{"date":{"start":"2026-10-05"}},
                 "Готово, когда":{"rich_text":[{"plain_text":"В салоне"}]},
                 "Где застряла":{"rich_text":[]}}}""");
        Task t = NotionTasksClient.parse(page);
        assertThat(t.id()).isEqualTo("3ed7aea26d5c81269c31dfa5b6e54571");
        assertThat(t.title()).isEqualTo("VIP-визитки");
        assertThat(t.due()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(t.doneWhen()).isEqualTo("В салоне");
        assertThat(("t|" + t.id() + "|d").getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
    }
}
