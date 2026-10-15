package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityQuery;
import com.salonreview.repo.AiVisibilityCheckRepository;
import com.salonreview.repo.AiVisibilityQueryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiQuestionSuggestionServiceTest {

    private static AiVisibilityQuery q(long id, String text, String topic) {
        AiVisibilityQuery q = new AiVisibilityQuery();
        q.setId(id);
        q.setQueryText(text);
        q.setTopic(topic);
        return q;
    }

    private static AiVisibilityCheck c(long queryId, String... subs) {
        AiVisibilityCheck c = new AiVisibilityCheck();
        c.setQueryId(queryId);
        c.setSubQueries(List.of(subs));
        return c;
    }

    @Test
    void dropsRephrasingsOfTrackedQuestionsAndRanksByHowManyQuestionsLedThere() {
        var checks = mock(AiVisibilityCheckRepository.class);
        var queries = mock(AiVisibilityQueryRepository.class);
        when(queries.findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(eq(2L), any())).thenReturn(List.of(
                q(1, "recommend the best microblading artist in San Diego", "brows"),
                q(2, "best lip blush tattoo artist in San Diego", "lips")));
        when(checks.findByBusinessIdAndCheckedAtAfterOrderByCheckedAtDesc(eq(2L), any())).thenReturn(List.of(
                c(1, "best microblading artist san diego", "bad microblading cover up san diego"),
                c(2, "bad microblading cover up san diego", "lip blush healing time"),
                c(2, "lip blush healing time")));

        var out = new AiQuestionSuggestionService(checks, queries).suggestions(2L, null);

        assertThat(out).extracting(AiQuestionSuggestionService.Suggestion::text)
                .containsExactly("bad microblading cover up san diego", "lip blush healing time");
        assertThat(out.get(0).fromQuestions()).isEqualTo(2);
        assertThat(out.get(1).timesAsked()).isEqualTo(2);
    }
}
