package com.salonreview.seo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoPageUrlsTest {

    @Test
    void stripsFragment() {
        assertThat(SeoPageUrls.withoutFragment("https://pmu.com/a/#faq")).isEqualTo("https://pmu.com/a/");
        assertThat(SeoPageUrls.withoutFragment("https://pmu.com/a/")).isEqualTo("https://pmu.com/a/");
        assertThat(SeoPageUrls.withoutFragment(null)).isNull();
    }

    @Test
    void detectsRetiredEduHost() {
        assertThat(SeoPageUrls.isRetiredHost("https://edu.pmu-annakara.com/x/")).isTrue();
        assertThat(SeoPageUrls.isRetiredHost("https://pmu-annakara.com/academy/")).isFalse();
        assertThat(SeoPageUrls.isRetiredHost(null)).isFalse();
    }

    @Test
    void mergesFragmentRowsIntoBasePage() {
        var d = java.time.LocalDate.of(2026, 10, 1);
        var merged = SeoSyncService.mergeFragments(java.util.List.of(
                new SearchConsoleClient.QueryRow(d, "q", "https://pmu.com/a/", 1, 10,
                        java.math.BigDecimal.valueOf(0.1), java.math.BigDecimal.valueOf(2)),
                new SearchConsoleClient.QueryRow(d, "q", "https://pmu.com/a/#faq", 1, 30,
                        java.math.BigDecimal.valueOf(0.03), java.math.BigDecimal.valueOf(6))));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).page()).isEqualTo("https://pmu.com/a/");
        assertThat(merged.get(0).clicks()).isEqualTo(2);
        assertThat(merged.get(0).impressions()).isEqualTo(40);
        assertThat(merged.get(0).position().doubleValue()).isEqualTo(5.0);
    }
}
