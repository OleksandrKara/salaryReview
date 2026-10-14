package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiVisibilitySiteTest {

    private static AiVisibilityQuery q(String topic, String target) {
        AiVisibilityQuery q = new AiVisibilityQuery();
        q.setTopic(topic);
        q.setTargetUrl(target);
        return q;
    }

    @Test
    void academyQuestionsByTopicOrTargetPage() {
        assertThat(AiVisibilityQueryService.inSite("academy", q("academy", "https://edu.pmu-annakara.com/"))).isTrue();
        assertThat(AiVisibilityQueryService.inSite("academy", q("training", "https://pmu-annakara.com/academy/courses/"))).isTrue();
        assertThat(AiVisibilityQueryService.inSite("academy", q("brows", "https://pmu-annakara.com/microblading-san-diego/"))).isFalse();
        assertThat(AiVisibilityQueryService.inSite("studio", q("brows", "https://pmu-annakara.com/microblading-san-diego/"))).isTrue();
        assertThat(AiVisibilityQueryService.inSite("studio", q("academy", null))).isFalse();
        assertThat(AiVisibilityQueryService.inSite(null, q("academy", null))).isTrue();
    }
}
