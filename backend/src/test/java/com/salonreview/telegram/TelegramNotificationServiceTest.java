package com.salonreview.telegram;

import com.salonreview.domain.TelegramNotificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static final FourHandRequestNotification NOTIFICATION = new FourHandRequestNotification(
            "mani", "Jane Doe", "+15551234567", "manicure + pedicure", "2026-08-01T18:00:00Z", null);

    @Test
    @DisplayName("blank bot token → false, no exception")
    void blankBotTokenSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.get()).thenReturn(
                TelegramNotificationConfig.builder().botToken(null).chatId("999888777").build());
        TelegramNotificationService service = new TelegramNotificationService(configService);

        assertThat(service.sendFourHandRequestAlert(NOTIFICATION)).isFalse();
    }

    @Test
    @DisplayName("blank chat id → false, no exception")
    void blankChatIdSkips() {
        TelegramConfigService configService = mock(TelegramConfigService.class);
        when(configService.get()).thenReturn(
                TelegramNotificationConfig.builder().botToken("some-token").chatId("").build());
        TelegramNotificationService service = new TelegramNotificationService(configService);

        assertThat(service.sendFourHandRequestAlert(NOTIFICATION)).isFalse();
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
}
