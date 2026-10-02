package com.salonreview.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The owner's Notion task database ("Задачи AK.LUX.NAILS", 2026-10-02) as the source of truth for
 * tasks he hands to Anya. The Telegram task bot ({@link TaskBotService}) reads open tasks from here
 * and writes status changes back, so the board in Notion and the buttons in Telegram never drift.
 *
 * <p>Property names are the database's own (Russian) column names. Uses Notion's data-source API
 * (version 2025-09-03). The token is a Notion internal integration that the owner connected to
 * that one database only.
 */
@Component
public class NotionTasksClient {

    static final String STATUS = "Статус";
    static final String ASSIGNEE = "Исполнитель";
    static final String DUE = "Срок";
    static final String TITLE = "Задача";
    static final String DONE_WHEN = "Готово, когда";
    static final String BLOCKER = "Где застряла";

    public record Task(String id, String title, String status, String assignee, LocalDate due, String doneWhen,
                       String blocker, Instant lastEdited) {
    }

    private final String token;
    private final String dataSourceId;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NotionTasksClient(@Value("${tasks.notion-token:}") String token,
                             @Value("${tasks.notion-data-source-id:}") String dataSourceId) {
        this.token = token;
        this.dataSourceId = dataSourceId;
    }

    /** Link that opens the task in Notion (app or browser) for anyone the database is shared with. */
    public static String pageUrl(String pageId) {
        return "https://www.notion.so/" + pageId.replace("-", "");
    }

    public boolean configured() {
        return token != null && !token.isBlank() && dataSourceId != null && !dataSourceId.isBlank();
    }

    /** Every task for this assignee that isn't Done, soonest due date first. */
    public List<Task> openTasksFor(String assignee) throws IOException, InterruptedException {
        return query(Map.of(
                "filter", Map.of("and", List.of(
                        Map.of("property", ASSIGNEE, "select", Map.of("equals", assignee)),
                        Map.of("property", STATUS, "select", Map.of("does_not_equal", "Done")))),
                "sorts", List.of(Map.of("property", DUE, "direction", "ascending"))));
    }

    /** All of this assignee's tasks (any status), for the weekly summary. */
    public List<Task> allTasksFor(String assignee) throws IOException, InterruptedException {
        return query(Map.of("filter", Map.of("property", ASSIGNEE, "select", Map.of("equals", assignee))));
    }

    public Task get(String pageId) throws IOException, InterruptedException {
        return parse(send("GET", "/v1/pages/" + pageId, null));
    }

    public void setStatus(String pageId, String status) throws IOException, InterruptedException {
        update(pageId, Map.of(STATUS, Map.of("select", Map.of("name", status))));
    }

    public void setDue(String pageId, LocalDate due) throws IOException, InterruptedException {
        update(pageId, Map.of(DUE, Map.of("date", Map.of("start", due.toString()))));
    }

    public void setBlocker(String pageId, String text) throws IOException, InterruptedException {
        String clipped = text.length() > 1900 ? text.substring(0, 1900) : text;
        update(pageId, Map.of(
                STATUS, Map.of("select", Map.of("name", "Blocked")),
                BLOCKER, Map.of("rich_text", List.of(Map.of("text", Map.of("content", clipped))))));
    }

    private void update(String pageId, Map<String, Object> properties) throws IOException, InterruptedException {
        send("PATCH", "/v1/pages/" + pageId, Map.of("properties", properties));
    }

    private List<Task> query(Map<String, Object> body) throws IOException, InterruptedException {
        List<Task> tasks = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> page = new java.util.HashMap<>(body);
            page.put("page_size", 100);
            if (cursor != null) page.put("start_cursor", cursor);
            JsonNode res = send("POST", "/v1/data_sources/" + dataSourceId + "/query", page);
            for (JsonNode r : res.path("results")) tasks.add(parse(r));
            cursor = res.path("has_more").asBoolean() ? res.path("next_cursor").asText(null) : null;
        } while (cursor != null);
        return tasks;
    }

    private JsonNode send(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("https://api.notion.com" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2025-09-03")
                .header("Content-Type", "application/json");
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Notion " + method + " " + path + " -> HTTP " + res.statusCode() + " " + res.body());
        }
        return json.readTree(res.body());
    }

    static Task parse(JsonNode page) {
        JsonNode p = page.path("properties");
        String due = p.path(DUE).path("date").path("start").asText("");
        String edited = page.path("last_edited_time").asText("");
        return new Task(
                page.path("id").asText().replace("-", ""),
                plain(p.path(TITLE).path("title")),
                p.path(STATUS).path("select").path("name").asText(""),
                p.path(ASSIGNEE).path("select").path("name").asText(""),
                due.isBlank() ? null : LocalDate.parse(due.substring(0, 10)),
                plain(p.path(DONE_WHEN).path("rich_text")),
                plain(p.path(BLOCKER).path("rich_text")),
                edited.isBlank() ? null : Instant.parse(edited));
    }

    private static String plain(JsonNode richText) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : richText) sb.append(t.path("plain_text").asText(""));
        return sb.toString();
    }
}
