package com.salonreview.telegram;

import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.repo.TelegramNotificationConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramConfigServiceTest {

    private TelegramNotificationConfigRepository repo;
    private TelegramConfigService service;

    @BeforeEach
    void setUp() {
        repo = mock(TelegramNotificationConfigRepository.class);
        service = new TelegramConfigService(repo);
    }

    private TelegramNotificationConfig configWith(String botToken, String chatId) {
        return TelegramNotificationConfig.builder().botToken(botToken).chatId(chatId).build();
    }

    @Test
    @DisplayName("null botToken/chatId leaves existing values unchanged")
    void nullFieldsLeaveUnchanged() {
        when(repo.getSingleton()).thenReturn(configWith("existing-token", "111"));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TelegramNotificationConfig updated = service.update(null, null, "owner");

        assertThat(updated.getBotToken()).isEqualTo("existing-token");
        assertThat(updated.getChatId()).isEqualTo("111");
        assertThat(updated.getUpdatedBy()).isEqualTo("owner");
    }

    @Test
    @DisplayName("empty string clears the field")
    void emptyStringClears() {
        when(repo.getSingleton()).thenReturn(configWith("existing-token", "111"));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TelegramNotificationConfig updated = service.update("", "", "owner");

        assertThat(updated.getBotToken()).isNull();
        assertThat(updated.getChatId()).isNull();
    }

    @Test
    @DisplayName("non-blank value sets the field, trimmed")
    void nonBlankSets() {
        when(repo.getSingleton()).thenReturn(configWith(null, null));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TelegramNotificationConfig updated = service.update("  new-token  ", "  222  ", "owner");

        assertThat(updated.getBotToken()).isEqualTo("new-token");
        assertThat(updated.getChatId()).isEqualTo("222");
    }
}
