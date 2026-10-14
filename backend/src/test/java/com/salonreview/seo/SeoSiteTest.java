package com.salonreview.seo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoSiteTest {

    @Test
    void academyPagesAreTheAcademyAndOldEduSubdomain() {
        assertThat(SeoSite.matches("academy", "https://pmu-annakara.com/academy/courses/lips/")).isTrue();
        assertThat(SeoSite.matches("academy", "https://edu.pmu-annakara.com/microblading-course/")).isTrue();
        assertThat(SeoSite.matches("academy", "https://pmu-annakara.com/academy")).isTrue();
        assertThat(SeoSite.matches("academy", "https://pmu-annakara.com/lip-blush/")).isFalse();
        assertThat(SeoSite.matches("academy", "https://pmu-annakara.com/academyx/")).isFalse();
        assertThat(SeoSite.matches("academy", null)).isFalse();
    }

    @Test
    void studioIsEverythingElseAndAllIsEverything() {
        assertThat(SeoSite.matches("studio", "https://pmu-annakara.com/lip-blush/")).isTrue();
        assertThat(SeoSite.matches("studio", null)).isTrue();
        assertThat(SeoSite.matches("studio", "https://pmu-annakara.com/academy/")).isFalse();
        assertThat(SeoSite.matches(null, "https://pmu-annakara.com/academy/")).isTrue();
        assertThat(SeoSite.matches("all", "https://x/")).isTrue();
    }
}
