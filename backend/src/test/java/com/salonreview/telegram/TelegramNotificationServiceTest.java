package com.salonreview.telegram;

import com.salonreview.domain.TelegramNotificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The blank-config never-throws path is fully exercised here without any network call. The
 * Telegram-unreachable path isn't separately unit-tested — same as {@code VoyageClient}/
 * {@code LangSmithClient}, this repo doesn't mock raw outbound HTTP calls; the try/catch around
 * {@code http.send(...)} in {@link TelegramNotificationService} is the same fail-safe shape
 * verified manually against the real Telegram API during this feature's E2E check.
 */
class TelegramNotificationServiceTest {

    private static final String BASE_URL = "https://salon.akluxnails.com";

    private static final FourHandRequestNotification NOTIFICATION = new FourHandRequestNotification(
            "mani", "Jane Doe", "+15551234567", "manicure + pedicure", "2026-08-01T18:00:00Z", null, 254.0);

    private static TelegramNotificationService service(TelegramConfigService configService) {
        return new TelegramNotificationService(configService, BASE_URL);
    }

    @Test
    @DisplayName("blank bot token → false, no exception")
    void blankBotTokenSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.getForAutomation()).thenReturn(
                TelegramNotificationConfig.builder().botToken(null).chatId("999888777").build());

        assertThat(service(configService).sendFourHandRequestAlert(NOTIFICATION)).isFalse();
    }

    @Test
    @DisplayName("blank chat id → false, no exception")
    void blankChatIdSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.getForAutomation()).thenReturn(
                TelegramNotificationConfig.builder().botToken("some-token").chatId("").build());

        assertThat(service(configService).sendFourHandRequestAlert(NOTIFICATION)).isFalse();
    }

    @Test
    @DisplayName("preferred time is converted from UTC to Pacific Time")
    void formatsPreferredTimeInPacificTime() {
        // 2026-08-01T18:00:00Z is 11:00 AM Pacific (PDT, UTC-7) in August.
        assertThat(TelegramNotificationService.formatPreferredTime("2026-08-01T18:00:00Z"))
                .isEqualTo("Sat, Aug 1, 2026 at 11:00 AM PDT");
    }

    @Test
    @DisplayName("malformed timestamp falls back to the raw value rather than throwing")
    void malformedTimestampFallsBackToRawValue() {
        assertThat(TelegramNotificationService.formatPreferredTime("not-a-timestamp"))
                .isEqualTo("not-a-timestamp");
    }

    @Test
    @DisplayName("message includes the estimated price when present")
    void formatMessageIncludesEstimatedPrice() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        assertThat(service.formatMessage(NOTIFICATION)).contains("Estimated price: $254");
    }

    @Test
    @DisplayName("message omits the estimated price line when null")
    void formatMessageOmitsEstimatedPriceWhenAbsent() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));
        FourHandRequestNotification withoutPrice = new FourHandRequestNotification(
                "mani", "Jane Doe", "+15551234567", "manicure + pedicure", "2026-08-01T18:00:00Z", null, null);

        assertThat(service.formatMessage(withoutPrice)).doesNotContain("Estimated price");
    }

    @Test
    @DisplayName("inbound-SMS alert: blank bot token → false, no exception")
    void inboundSmsAlertBlankBotTokenSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.getForAutomation()).thenReturn(
                TelegramNotificationConfig.builder().botToken(null).chatId("999888777").build());

        assertThat(service(configService).sendInboundSmsAlert("+15551234567", "Jane Doe", "hi", null)).isFalse();
    }

    @Test
    @DisplayName("inbound-SMS alert: blank chat id → false, no exception")
    void inboundSmsAlertBlankChatIdSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.getForAutomation()).thenReturn(
                TelegramNotificationConfig.builder().botToken("some-token").chatId("").build());

        assertThat(service(configService).sendInboundSmsAlert("+15551234567", "Jane Doe", "hi", "checkout_review_request"))
                .isFalse();
    }

    @Test
    @DisplayName("inbound alert header uses the customer name, bold, when known")
    void inboundAlertUsesNameWhenKnown() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        String text = service.formatInboundSmsAlert("+18585550100", "Jane Doe", "Thanks!", null);

        assertThat(text).contains("<b>New message from Jane Doe</b>");
        assertThat(text).contains("📱 (858) 555-0100");
    }

    @Test
    @DisplayName("inbound alert falls back to the formatted phone number when no name is known")
    void inboundAlertFallsBackToPhoneWhenNoName() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        String text = service.formatInboundSmsAlert("+18585550100", null, "Thanks!", null);

        assertThat(text).contains("<b>New message from (858) 555-0100</b>");
        // No separate phone line — it's already the header, showing it twice would be noise.
        assertThat(text).doesNotContain("📱");
    }

    @Test
    @DisplayName("inbound alert includes a tappable deep link straight to that customer's thread")
    void inboundAlertIncludesChatLink() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        String text = service.formatInboundSmsAlert("+18585550100", "Jane Doe", "Thanks!", null);

        assertThat(text).contains("<a href=\"https://salon.akluxnails.com/admin/messages?phone=%2B18585550100\">💬 Open chat</a>");
    }

    @Test
    @DisplayName("inbound alert shows a friendly automation label when the reply matched one")
    void inboundAlertShowsAutomationLabel() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        String text = service.formatInboundSmsAlert("+18585550100", "Jane Doe", "5", "checkout_review_request");

        assertThat(text).contains("↩️ Reply to: checkout review request");
    }

    @Test
    @DisplayName("inbound alert HTML-escapes a customer's own message body so it can't break formatting")
    void inboundAlertEscapesMessageBody() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        String text = service.formatInboundSmsAlert("+18585550100", "Jane Doe", "5 < 10 & I'm happy", null);

        assertThat(text).contains("5 &lt; 10 &amp; I'm happy"); // apostrophe isn't special in Telegram's HTML mode, left as-is
        assertThat(text).doesNotContain("5 < 10 & I'm happy");
    }

    @Test
    @DisplayName("formatPhoneDisplay renders a plain 10/11-digit US number, falls back otherwise")
    void formatPhoneDisplayFormatsUsNumbers() {
        assertThat(TelegramNotificationService.formatPhoneDisplay("+18585550100")).isEqualTo("(858) 555-0100");
        assertThat(TelegramNotificationService.formatPhoneDisplay("8585550100")).isEqualTo("(858) 555-0100");
        assertThat(TelegramNotificationService.formatPhoneDisplay("12345")).isEqualTo("12345");
    }

    @Test
    @DisplayName("chatLink URL-encodes the phone number into the deep link")
    void chatLinkEncodesPhoneNumber() {
        TelegramNotificationService service = service(mock(TelegramConfigService.class));

        assertThat(service.chatLink("+18585550100")).isEqualTo("https://salon.akluxnails.com/admin/messages?phone=%2B18585550100");
    }

    // ---------------------------------------------------------------- same-day booking alert

    @Test
    @DisplayName("same-day alert is business-scoped — resolves via configService.get(businessId), "
            + "NOT the always-legacy-business getForAutomation() every other alert here uses, since "
            + "it fires from a real per-business webhook")
    void sameDayAlertResolvesConfigByBusinessId() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.get(2L)).thenReturn(
                TelegramNotificationConfig.builder().businessId(2L).botToken(null).chatId("999888777").build());

        boolean sent = service(configService).sendSameDayBookingAlert(2L, "Susan Alieva", "Tara Lumley",
                "2026-09-01T18:00:00Z", Duration.ofMinutes(45));

        assertThat(sent).isFalse(); // blank token → skipped, but proves it read business 2's own config
    }

    @Test
    @DisplayName("same-day alert: blank chat id → false, no exception")
    void sameDayAlertBlankChatIdSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.get(1L)).thenReturn(
                TelegramNotificationConfig.builder().businessId(1L).botToken("some-token").chatId("").build());

        assertThat(service(configService).sendSameDayBookingAlert(1L, "Susan Alieva", "Tara Lumley",
                "2026-09-01T18:00:00Z", Duration.ofMinutes(45))).isFalse();
    }

    @Test
    @DisplayName("formatLeadTime: under an hour shows minutes, at/past an hour shows h/hm")
    void formatLeadTimeShapesTheDuration() {
        assertThat(TelegramNotificationService.formatLeadTime(Duration.ofMinutes(45))).isEqualTo("45 min");
        assertThat(TelegramNotificationService.formatLeadTime(Duration.ofMinutes(0))).isEqualTo("0 min");
        assertThat(TelegramNotificationService.formatLeadTime(Duration.ofHours(3))).isEqualTo("3h");
        assertThat(TelegramNotificationService.formatLeadTime(Duration.ofMinutes(200))).isEqualTo("3h 20m");
    }
}
