package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.domain.AiVisibilityRun;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.domain.Business;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import com.salonreview.repo.AiVisibilityRunRepository;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V167 + JSONB mappings + business scoping against a real Postgres (rolled back per test). */
@SpringBootTest
@Transactional
class AiVisibilityPersistenceTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private AiVisibilitySettingsRepository settings;
    @Autowired private AiVisibilityQueryRepository queries;
    @Autowired private AiVisibilityRunRepository runs;
    @Autowired private AiVisibilityCheckRepository checks;
    @Autowired private AiVisibilityAdminService admin;
    @Autowired private AiVisibilityQueryService readModel;

    private Long a;
    private Long b;

    @BeforeEach
    void setUp() {
        a = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        b = businesses.save(Business.builder().name("AI Visibility Test B").shortCode("aiv-test-b-" + System.nanoTime())
                .timezone("UTC").active(true).build()).getId();
        settings.save(AiVisibilitySettings.builder().businessId(b).brandAliases(List.of("Test B Studio")).ownDomains(List.of("testb.example"))
                .locationHint("San Diego,California,United States").monthlyBudgetUsd(new BigDecimal("8.00")).digestEnabled(true).build());
    }

    @Test
    void seededSettingsAndProposedQuestionsForAkLuxNails() {
        AiVisibilitySettings s = settings.findById(a).orElseThrow();
        assertThat(s.getBrandAliases()).contains("AK.LUX.NAILS");
        assertThat(s.getOwnDomains()).containsExactly("akluxnails.com");
        List<AiVisibilityQuery> proposed = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(a, List.of(AiVisibilityQuery.Status.PROPOSED));
        assertThat(proposed).hasSize(20);
        assertThat(proposed.get(0).getQueryText()).isEqualTo("recommend me the best place to do Russian manicure in San Diego");
        assertThat(proposed.get(0).getTargetUrl()).isEqualTo("https://akluxnails.com/");
    }

    @Test
    void checkJsonColumnsRoundTripAndHistoryIsScoped() {
        AiVisibilityQuery q = admin.add(b, "best test studio in San Diego", "general", null);
        AiVisibilityRun run = runs.save(AiVisibilityRun.builder().businessId(b).trigger(AiVisibilityRun.Trigger.SCHEDULED).build());
        checks.save(AiVisibilityCheck.builder().businessId(b).runId(run.getId()).queryId(q.getId()).engine(AiVisibilityCheck.Engine.PERPLEXITY)
                .status(AiVisibilityCheck.Status.OK).mentioned(true).mentionRank((short) 2).ownSiteCited(false)
                .placesNamed(List.of("Rival", "Test B Studio")).sourcesCited(List.of(Map.of("domain", "yelp.com", "title", "Top 10")))
                .subQueries(List.of("best studio san diego")).answerExcerpt("…").costUsd(new BigDecimal("0.0040")).build());
        checks.flush();

        var history = readModel.history(b, q.getId(), 10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).placesNamed()).containsExactly("Rival", "Test B Studio");
        assertThat(history.get(0).sourcesCited().get(0)).containsEntry("domain", "yelp.com");
        assertThat(readModel.history(a, q.getId(), 10)).isEmpty();

        var overview = readModel.overview(b);
        assertThat(overview.rows()).extracting(AiVisibilityQueryService.Row::query).contains("best test studio in San Diego");
    }

    @Test
    void otherBusinessIdsAre404AndLimitIsEnforced() {
        AiVisibilityQuery aQuestion = queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(a, List.of(AiVisibilityQuery.Status.PROPOSED)).get(0);
        assertThatThrownBy(() -> admin.update(b, aQuestion.getId(), null, null, null, "REMOVED", null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> admin.recheck(b, aQuestion.getId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> admin.add(a, "one more question about nails", null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(admin.approveAll(a)).isEqualTo(20);
    }
}
