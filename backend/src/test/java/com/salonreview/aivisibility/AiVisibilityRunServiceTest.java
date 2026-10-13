package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AiVisibilityProperties;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilityRunRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiVisibilityRunServiceTest {

    private final AiAnswerProvider provider = mock(AiAnswerProvider.class);
    private final AiVisibilityRunRepository runs = mock(AiVisibilityRunRepository.class);
    private final AiVisibilityCheckRepository checks = mock(AiVisibilityCheckRepository.class);
    private final List<AiVisibilityCheck> saved = new ArrayList<>();
    private final AiVisibilityProperties props = new AiVisibilityProperties();
    private AiVisibilityRunService service;
    private AiVisibilitySettings settings;

    @BeforeEach
    void setUp() throws Exception {
        props.setDataforseoLogin("l");
        props.setDataforseoPassword("p");
        when(runs.save(any())).thenAnswer(inv -> {
            AiVisibilityRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(7L);
            return r;
        });
        when(runs.spentSince(eq(1L), any())).thenReturn(BigDecimal.ZERO);
        when(checks.save(any())).thenAnswer(inv -> { saved.add(inv.getArgument(0)); return inv.getArgument(0); });
        service = new AiVisibilityRunService(provider, props, mock(AiVisibilitySettingsRepository.class), mock(AiVisibilityQueryRepository.class),
                runs, checks, mock(CurrentBusinessContext.class), mock(org.springframework.beans.factory.ObjectProvider.class));
        settings = AiVisibilitySettings.builder().businessId(1L).brandAliases(List.of("AK.LUX.NAILS")).ownDomains(List.of("akluxnails.com"))
                .locationHint("San Diego,California,United States").monthlyBudgetUsd(new BigDecimal("8.00")).digestEnabled(true).build();
        var json = new ObjectMapper().readTree("""
                {"markdown": "Go to **AK.LUX.NAILS** or **Twinkle Nails**.",
                 "items": [{"type": "ai_overview", "markdown": "Go to **AK.LUX.NAILS** or **Twinkle Nails**.",
                            "sections": [{"text": "Go to **AK.LUX.NAILS** or **Twinkle Nails**.", "annotations": []}]}]}
                """);
        when(provider.ask(any(), anyString(), anyString(), any())).thenReturn(new AiAnswerProvider.RawAnswer(json, new BigDecimal("0.004"), "m"));
    }

    private static AiVisibilityQuery q(long id) {
        return AiVisibilityQuery.builder().id(id).businessId(1L).queryText("best nail salon " + id).status(AiVisibilityQuery.Status.ACTIVE).build();
    }

    @Test
    void writesOneCheckPerQuestionAndEngine() {
        AiVisibilityRun run = service.run(settings, List.of(q(1), q(2)), AiVisibilityRun.Trigger.SCHEDULED);
        assertThat(saved).hasSize(2 * Engine.values().length);
        assertThat(saved).allMatch(c -> c.getStatus() == AiVisibilityCheck.Status.OK && Boolean.TRUE.equals(c.getMentioned()));
        assertThat(saved.get(0).getPlacesNamed()).containsExactly("AK.LUX.NAILS", "Twinkle Nails");
        assertThat(run.getChecksOk()).isEqualTo(2 * Engine.values().length);
        assertThat(run.getCostUsd()).isEqualByComparingTo(new java.math.BigDecimal("0.004").multiply(java.math.BigDecimal.valueOf(2L * Engine.values().length)));
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getStoppedReason()).isNull();
    }

    @Test
    void budgetGuardStopsBeforeExceeding() {
        when(runs.spentSince(eq(1L), any())).thenReturn(new BigDecimal("7.98"));
        AiVisibilityRun run = service.run(settings, List.of(q(1)), AiVisibilityRun.Trigger.SCHEDULED);
        assertThat(run.getStoppedReason()).isEqualTo("BUDGET");
        assertThat(saved).hasSizeLessThan(Engine.values().length);
    }

    @Test
    void engineSkippedAfterThreeConsecutiveFailures() {
        when(provider.ask(eq(Engine.GEMINI), anyString(), anyString(), any())).thenThrow(new IllegalStateException("HTTP 500"));
        service.run(settings, List.of(q(1), q(2), q(3), q(4), q(5)), AiVisibilityRun.Trigger.SCHEDULED);
        long geminiCalls = saved.stream().filter(c -> c.getEngine() == Engine.GEMINI).count();
        assertThat(geminiCalls).isEqualTo(AiVisibilityRunService.ENGINE_FAILURE_LIMIT);
        assertThat(saved).filteredOn(c -> c.getEngine() == Engine.GEMINI).allMatch(c -> c.getStatus() == AiVisibilityCheck.Status.ERROR && c.getError().contains("500"));
        assertThat(saved).filteredOn(c -> c.getEngine() == Engine.CHATGPT).hasSize(5);
    }

    @Test
    void noCredentialsMeansNoCalls() {
        props.setDataforseoLogin("");
        AiVisibilityRun run = service.run(settings, List.of(q(1)), AiVisibilityRun.Trigger.SCHEDULED);
        assertThat(run.getStoppedReason()).isEqualTo("NO_CREDENTIALS");
        verifyNoInteractions(provider);
    }

    @Test
    void emptyAnswerIsNoAnswer() {
        when(provider.ask(eq(Engine.GOOGLE_AI_MODE), anyString(), anyString(), any())).thenReturn(new AiAnswerProvider.RawAnswer(null, new BigDecimal("0.004"), "g"));
        service.run(settings, List.of(q(1)), AiVisibilityRun.Trigger.SCHEDULED);
        assertThat(saved).filteredOn(c -> c.getEngine() == Engine.GOOGLE_AI_MODE).allMatch(c -> c.getStatus() == AiVisibilityCheck.Status.NO_ANSWER);
    }

    @Test
    void nearMeQuestionAsksOnlyGoogleFromEveryPointAndOrdinaryQuestionsUseTheFirstPoint() {
        settings.setSearchPoints(new ArrayList<>(List.of(
                new java.util.HashMap<>(java.util.Map.<String, Object>of("label", "Central", "coordinate", "32.748,-117.155,13")),
                new java.util.HashMap<>(java.util.Map.<String, Object>of("label", "North", "coordinate", "32.87,-117.21,13")),
                new java.util.HashMap<>(java.util.Map.<String, Object>of("label", "East", "coordinate", "32.77,-117.02,13")))));
        AiVisibilityQuery nearMe = q(9);
        nearMe.setEngines(List.of("GOOGLE_AI_OVERVIEW", "GOOGLE_AI_MODE"));
        nearMe.setAllPoints(true);
        service.run(settings, List.of(nearMe, q(1)), AiVisibilityRun.Trigger.SCHEDULED);

        var nearMeChecks = saved.stream().filter(c -> c.getQueryId() == 9L).toList();
        assertThat(nearMeChecks).hasSize(6).allMatch(c -> AiVisibilityRunService.isGoogle(c.getEngine()));
        assertThat(nearMeChecks).extracting(AiVisibilityCheck::getSearchPoint).containsOnly("Central", "North", "East");
        var ordinary = saved.stream().filter(c -> c.getQueryId() == 1L).toList();
        assertThat(ordinary).hasSize(Engine.values().length);
        assertThat(ordinary).filteredOn(c -> AiVisibilityRunService.isGoogle(c.getEngine())).extracting(AiVisibilityCheck::getSearchPoint).containsOnly("Central");
        assertThat(ordinary).filteredOn(c -> !AiVisibilityRunService.isGoogle(c.getEngine())).extracting(AiVisibilityCheck::getSearchPoint).containsOnlyNulls();
        verify(provider, atLeastOnce()).ask(eq(Engine.GOOGLE_AI_MODE), eq("best nail salon 9"), anyString(), eq("32.87,-117.21,13"));
    }

    @Test
    void staleRunIsNotActiveAndARunResumesWithoutRepeatingPaidChecks() {
        java.time.Instant now = java.time.Instant.now();
        AiVisibilityRun orphan = AiVisibilityRun.builder().id(27L).businessId(1L).trigger(AiVisibilityRun.Trigger.MANUAL_QUERY)
                .startedAt(now.minus(java.time.Duration.ofMinutes(20))).claimedAt(now.minus(java.time.Duration.ofMinutes(15))).build();
        AiVisibilityRun busy = AiVisibilityRun.builder().id(28L).businessId(1L).trigger(AiVisibilityRun.Trigger.SCHEDULED)
                .startedAt(now.minus(java.time.Duration.ofMinutes(20))).claimedAt(now.minusSeconds(20)).build();
        when(checks.findTop1ByRunIdOrderByCheckedAtDesc(any())).thenReturn(java.util.Optional.empty());
        assertThat(service.isActive(orphan)).isFalse();
        assertThat(service.isActive(busy)).isTrue();

        // Already paid before the restart: question 1 on ChatGPT. Resuming must not ask it again.
        AiVisibilityRun resumed = AiVisibilityRun.builder().id(30L).businessId(1L).trigger(AiVisibilityRun.Trigger.MANUAL_QUERY)
                .startedAt(now.minus(java.time.Duration.ofMinutes(10))).build();
        when(checks.findByRunId(30L)).thenReturn(List.of(AiVisibilityCheck.builder().runId(30L).queryId(1L).engine(Engine.CHATGPT).build()));
        service.execute(settings, List.of(q(1)), resumed);
        assertThat(saved).hasSize(Engine.values().length - 1).noneMatch(c -> c.getEngine() == Engine.CHATGPT);
        assertThat(resumed.getFinishedAt()).isNotNull();
    }

}
