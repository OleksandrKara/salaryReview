package com.salonreview.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.telegram.TelegramConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bot API calls for the task bot. Same bot as the staff alerts (@akluxnails_bot, business 1's
 * Telegram config), used here in private chats with Anya and the owner. Nothing else in this app
 * reads the bot's incoming updates, so polling them here can't steal anyone else's.
 */
@Component
public class TaskBotTelegram {

    /** One inline button: label + callback data (max 64 bytes). */
    public record Button(String text, String data) {
    }

    private final TelegramConfigService configService;
    private final Long businessId;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TaskBotTelegram(TelegramConfigService configService, @Value("${tasks.bot-business-id:1}") Long businessId) {
        this.configService = configService;
        this.businessId = businessId;
    }

    public boolean configured() {
        String token = configService.get(businessId).getBotToken();
        return token != null && !token.isBlank();
    }

    /** Short poll (no long-poll wait), so the ShedLock-held poll job stays brief. */
    public JsonNode getUpdates(long offset) throws IOException, InterruptedException {
        return call("getUpdates", Map.of("offset", offset, "timeout", 0, "allowed_updates", List.of("message", "callback_query")))
                .path("result");
    }

    public void sendMessage(long chatId, String text, List<List<Button>> keyboard) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("disable_web_page_preview", true);
        if (keyboard != null && !keyboard.isEmpty()) body.put("reply_markup", markup(keyboard));
        call("sendMessage", body);
    }

    public void editMessage(long chatId, long messageId, String text) throws IOException, InterruptedException {
        call("editMessageText", Map.of("chat_id", chatId, "message_id", messageId, "text", text));
    }

    public void answerCallback(String callbackId, String text) throws IOException, InterruptedException {
        call("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text));
    }

    private static Map<String, Object> markup(List<List<Button>> keyboard) {
        return Map.of("inline_keyboard", keyboard.stream()
                .map(row -> row.stream().map(b -> Map.of("text", b.text(), "callback_data", b.data())).toList())
                .toList());
    }

    private JsonNode call(String method, Map<String, Object> body) throws IOException, InterruptedException {
        String token = configService.get(businessId).getBotToken();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = json.readTree(res.body());
        if (!node.path("ok").asBoolean()) {
            // Never log the token-bearing URL, only the method and Telegram's own description.
            throw new IOException("Telegram " + method + " failed: " + node.path("description").asText(res.body()));
        }
        return node;
    }
}
