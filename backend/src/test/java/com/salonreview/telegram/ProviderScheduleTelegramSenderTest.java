package com.salonreview.telegram;

import com.salonreview.domain.Business;
import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static com.salonreview.sms.ProviderScheduleTestFixtures.BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderScheduleTelegramSenderTest {
    private final TelegramConfigService config = mock(TelegramConfigService.class);
    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final HttpClient http = mock(HttpClient.class);
    private ProviderScheduleTelegramSender sender;
    private TelegramNotificationConfig settings;

    @BeforeEach
    void setUp() {
        settings = TelegramNotificationConfig.builder().businessId(1L).botToken("FAKE-TOKEN").chatId("FAKE-CHAT").build();
        when(config.get(1L)).thenReturn(settings);
        when(businesses.findById(1L)).thenReturn(Optional.of(Business.builder().name("Test Business").build()));
        sender = new ProviderScheduleTelegramSender(config, businesses, http, "http://localhost");
    }

    @Test
    void neutralBilingualMessageUsesConfiguredNoticeAndBusinessTimezone() {
        String message = ProviderScheduleTelegramSender.format("Tatiana", BASE, BASE.plusSeconds(14400), 6, "America/New_York");
        assertThat(message).contains("Tatiana", "4:00 PM EDT", "less than 6 hours", "менее чем за 6 ч",
                "Previously available start times", "Ранее доступные начала записи", "Customer bookings do not explain");
        assertThat(message).doesNotContain("closed", "on their own", "закрыл", "less than a day", "PDT");
    }

    @Test
    void countsAsSentOnlyWhenTelegramConfirmsMessageId() throws Exception {
        respond(200, "{\"ok\":true,\"result\":{\"message_id\":12345}}");
        assertThat(send()).isEqualTo(new ProviderScheduleTelegramSender.Result("SENT", 12345L));
        verify(config).get(1L);
        verify(http).send(argThat(request -> request.method().equals("POST")
                && request.uri().getHost().equals("localhost") && request.timeout().orElseThrow().toSeconds() == 5), any());
    }

    @Test
    void explicitRejectionCanRetryButAmbiguousResponsesCannot() throws Exception {
        respond(429, "{\"ok\":false}");
        assertThat(send().status()).isEqualTo("FAILED");
        respond(200, "{\"ok\":false}");
        assertThat(send().status()).isEqualTo("FAILED");
        for (String body : new String[]{"{}", "{\"ok\":true}", "{\"ok\":\"true\"}", "invalid json"}) {
            respond(200, body);
            assertThat(send().status()).isEqualTo("UNKNOWN");
        }
        respond(500, "{}");
        assertThat(send().status()).isEqualTo("UNKNOWN");
    }

    @Test
    void missingCredentialsMakesNoNetworkRequest() {
        settings.setBotToken(null);
        assertThat(send().status()).isEqualTo("FAILED");
        verifyNoInteractions(http);
    }

    @Test
    void transportTimeoutIsAmbiguousAndInterruptionRestoresThreadFlag() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("timeout"));
        assertThat(send().status()).isEqualTo("UNKNOWN");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new InterruptedException());
        try {
            assertThat(send().status()).isEqualTo("UNKNOWN");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private ProviderScheduleTelegramSender.Result send() {
        return sender.send(1L, "Tatiana", BASE, BASE.plusSeconds(14400), 24, "America/Los_Angeles");
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
    }
}
