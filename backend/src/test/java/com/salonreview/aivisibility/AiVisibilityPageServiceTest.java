package com.salonreview.aivisibility;

import com.salonreview.aivisibility.AiVisibilityPageService.PageView;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiVisibilityPageServiceTest {

    private static PageView page(boolean own, boolean readable, boolean business, String... presentComponents) {
        Map<String, Map<String, Object>> comps = new LinkedHashMap<>();
        if (readable) {
            for (PageComponentExtractor.Component c : PageComponentExtractor.Component.values()) comps.put(c.name(), Map.of("present", false));
            for (String c : presentComponents) comps.put(c, Map.of("present", true, "evidence", "x"));
        }
        return new PageView("https://x/" + own, own, null, 200, "DIRECT", readable, business, comps, Map.of(), null, 1);
    }

    @Test
    void gapNeedsTwoCompetitorPages() {
        PageView ours = page(true, true, true, "PRICES");
        var gaps = AiVisibilityPageService.gaps(ours, List.of(
                page(false, true, true, "FAQ", "BEFORE_AFTER"), page(false, true, true, "FAQ"), page(false, true, true, "PRICES")));
        assertThat(gaps).extracting(AiVisibilityPageService.Gap::component).containsExactly("FAQ");
        assertThat(gaps.get(0).competitorsWith()).isEqualTo(2);
        assertThat(gaps.get(0).competitorsCompared()).isEqualTo(3);
        assertThat(gaps.get(0).weak()).isFalse();
    }

    @Test
    void singleCompetitorGapIsWeak() {
        var gaps = AiVisibilityPageService.gaps(page(true, true, true), List.of(page(false, true, true, "VIDEO")));
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).weak()).isTrue();
    }

    @Test
    void unreadableAndNonBusinessPagesDoNotCount() {
        var gaps = AiVisibilityPageService.gaps(page(true, true, true), List.of(
                page(false, false, false), page(false, true, false, "FAQ"), page(false, true, false, "FAQ")));
        assertThat(gaps).isEmpty();
        assertThat(AiVisibilityPageService.gaps(page(true, false, false), List.of(page(false, true, true, "FAQ"), page(false, true, true, "FAQ")))).isEmpty();
    }

    @Test
    void directoriesAndTrackingParams() {
        assertThat(AiVisibilityPageService.isDirectory("m.yelp.com")).isTrue();
        assertThat(AiVisibilityPageService.isDirectory("www.facebook.com")).isTrue();
        assertThat(AiVisibilityPageService.isDirectory("thebeaupmu.com")).isFalse();
        assertThat(AiVisibilityPageService.normalizeUrl("https://thebeaupmu.com/?utm_source=openai")).isEqualTo("https://thebeaupmu.com/");
        assertThat(AiVisibilityPageService.normalizeUrl("https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc")).isNull();
    }

    @Test
    void competitorDomainMustMatchANamedPlace() {
        var places = java.util.Set.of("AVO Brows & Beauty", "Polinails", "Svitnail Studio", "The Beau");
        assertThat(AiVisibilityPageService.domainMatchesPlace("www.avobrowsandbeauty.com", places)).isTrue();
        assertThat(AiVisibilityPageService.domainMatchesPlace("polinailssd.com", places)).isTrue();
        assertThat(AiVisibilityPageService.domainMatchesPlace("svitnail.com", places)).isTrue();
        assertThat(AiVisibilityPageService.domainMatchesPlace("thebeaupmu.com", places)).isTrue();
        assertThat(AiVisibilityPageService.domainMatchesPlace("www.atly.com", places)).isFalse();
        assertThat(AiVisibilityPageService.domainMatchesPlace("findaloco.com", places)).isFalse();
        assertThat(AiVisibilityPageService.domainMatchesPlace("totallysandiego.com", places)).isFalse();
    }

    @Test
    void robotsDisallowForStarGroupOnly() {
        String robots = """
                User-agent: Googlebot
                Disallow: /private-google

                User-agent: *
                Disallow: /admin
                Disallow: /*?filter
                Allow: /
                """;
        assertThat(PageFetcher.parseDisallow(robots)).containsExactly("/admin");
    }
}
