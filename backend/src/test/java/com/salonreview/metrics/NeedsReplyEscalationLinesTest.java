package com.salonreview.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salonreview.telegram.TelegramNotificationService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class NeedsReplyEscalationLinesTest {

    @Test
    void waitingClientNameLinksToTheLead() {
        Instant now = Instant.parse("2026-11-12T20:00:00Z");
        NeedsReplyService queue = mock(NeedsReplyService.class);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT id FROM business WHERE active", Long.class)).thenReturn(List.of(1L));
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(Timestamp.from(now.minusSeconds(86400)));
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(1);
        NeedsReplyService.Item named = new NeedsReplyService.Item("+16195554998", "4998", now.minusSeconds(1200), 1200, false,
                "Ivonna <Client>", 25129L, "NEW", "hi", null, null);
        NeedsReplyService.Item anon = new NeedsReplyService.Item("+16195551022", "1022", now.minusSeconds(1200), 1200, true,
                null, null, null, "hi", null, null);
        when(queue.queue(1L)).thenReturn(new NeedsReplyService.Queue(2, 1200L, List.of(named, anon)));
        when(telegram.sendHtmlAlert(anyLong(), anyString(), anyString(), anyString())).thenReturn(true);

        new NeedsReplyEscalation(queue, telegram, jdbc, "https://salon.example", Clock.fixed(now, java.time.ZoneOffset.UTC)).process(1L);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram, org.mockito.Mockito.atLeastOnce()).sendHtmlAlert(eq(1L), text.capture(), anyString(), anyString());
        String sent = text.getAllValues().get(0);
        assertThat(sent).contains("<a href=\"https://salon.example/admin/leads/25129?business=1\">Ivonna &lt;Client&gt;</a> ···4998 · 20 мин");
        assertThat(sent).contains("···1022 · 20 мин · вопрос");
        assertThat(sent).doesNotContain("+1619");
    }
}
