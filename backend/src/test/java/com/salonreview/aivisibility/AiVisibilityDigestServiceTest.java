package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.domain.Business;
import com.salonreview.repo.*;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiVisibilityDigestServiceTest {

    @Test
    void digestShowsChangesRivalsSourcesAndNoEmDash() {
        AiVisibilityCheckRepository checks = mock(AiVisibilityCheckRepository.class);
        AiVisibilityQueryRepository queries = mock(AiVisibilityQueryRepository.class);
        AiVisibilityRunService runService = mock(AiVisibilityRunService.class);
        BusinessRepository businesses = mock(BusinessRepository.class);
        Business b = new Business();
        b.setName("AK.LUX.NAILS");
        when(businesses.findById(1L)).thenReturn(Optional.of(b));
        when(runService.monthSpend(1L)).thenReturn(new BigDecimal("1.31"));
        when(queries.findAllById(anyList())).thenReturn(List.of(
                AiVisibilityQuery.builder().id(1L).businessId(1L).queryText("best russian pedicure san diego").status(AiVisibilityQuery.Status.ACTIVE).build(),
                AiVisibilityQuery.builder().id(2L).businessId(1L).queryText("best nail salon downtown").status(AiVisibilityQuery.Status.ACTIVE).build()));
        when(checks.findByBusinessIdAndRunId(1L, 20L)).thenReturn(List.of(
                ok(1L, Engine.CHATGPT, true, List.of("AK.LUX.NAILS")), ok(2L, Engine.GEMINI, false, List.of("Twinkle Nails", "LV Nails"))));
        when(checks.findByBusinessIdAndRunId(1L, 10L)).thenReturn(List.of(
                ok(1L, Engine.CHATGPT, false, List.of("Twinkle Nails")), ok(2L, Engine.GEMINI, true, List.of("AK.LUX.NAILS"))));
        AiVisibilityDigestService svc = new AiVisibilityDigestService(mock(AiVisibilitySettingsRepository.class), queries,
                mock(AiVisibilityRunRepository.class), checks, runService, businesses, mock(TelegramNotificationService.class), "https://salon.example");
        AiVisibilitySettings s = AiVisibilitySettings.builder().businessId(1L).brandAliases(List.of("AK.LUX.NAILS")).ownDomains(List.of("akluxnails.com"))
                .locationHint("x").monthlyBudgetUsd(new BigDecimal("8")).digestEnabled(true).nextActions("FAQ на /russian-pedicure — пункт").build();
        AiVisibilityRun cur = AiVisibilityRun.builder().id(20L).businessId(1L).startedAt(Instant.parse("2026-10-19T13:00:00Z")).checksOk(2).build();
        AiVisibilityRun prev = AiVisibilityRun.builder().id(10L).businessId(1L).startedAt(Instant.parse("2026-10-12T13:00:00Z")).checksOk(2).build();

        String text = svc.build(s, cur, prev);

        assertThat(text).contains("AK.LUX.NAILS, 19.10");
        assertThat(text).contains("ChatGPT 1/1 (+1)").contains("Gemini 0/1 (-1)");
        assertThat(text).contains("Появились: «best russian pedicure san diego» в ChatGPT");
        assertThat(text).contains("Пропали: «best nail salon downtown» в Gemini");
        assertThat(text).contains("Вместо нас чаще всего: ").contains("Twinkle Nails (1)");
        assertThat(text).contains("В работе у Claude:").contains("$1.31 из $8").endsWith("https://salon.example/owner/marketing/ai");
        assertThat(text).doesNotContain("—");
    }

    @Test
    void notSentWithoutOkChecks() {
        AiVisibilitySettingsRepository settings = mock(AiVisibilitySettingsRepository.class);
        when(settings.findById(1L)).thenReturn(Optional.of(AiVisibilitySettings.builder().businessId(1L).digestEnabled(true).build()));
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        AiVisibilityDigestService svc = new AiVisibilityDigestService(settings, mock(AiVisibilityQueryRepository.class), mock(AiVisibilityRunRepository.class),
                mock(AiVisibilityCheckRepository.class), mock(AiVisibilityRunService.class), mock(BusinessRepository.class), telegram, "x");
        assertThat(svc.sendFor(AiVisibilityRun.builder().id(1L).businessId(1L).checksOk(0).build())).isFalse();
        verify(telegram, never()).sendPlainText(any(), any());
    }

    private static AiVisibilityCheck ok(Long q, Engine e, boolean named, List<String> places) {
        return AiVisibilityCheck.builder().businessId(1L).queryId(q).engine(e).status(AiVisibilityCheck.Status.OK).mentioned(named)
                .placesNamed(places).sourcesCited(List.of(Map.of("domain", "yelp.com"))).build();
    }
}
