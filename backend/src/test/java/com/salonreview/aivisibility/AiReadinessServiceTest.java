package com.salonreview.aivisibility;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiReadinessServiceTest {

    @Test
    void robotsBlocksAreFoundPerBotWithStarFallback() {
        assertThat(AiReadinessService.blockedBots("User-agent: *\nAllow: /\n")).isEmpty();
        assertThat(AiReadinessService.blockedBots("User-agent: GPTBot\nUser-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nAllow: /"))
                .containsExactly("GPTBot", "ClaudeBot");
        assertThat(AiReadinessService.blockedBots("User-agent: *\nDisallow: /\n\nUser-agent: Googlebot\nAllow: /"))
                .contains("GPTBot", "Bingbot").doesNotContain("Googlebot");
        assertThat(AiReadinessService.blockedBots("User-agent: *\nDisallow: /admin\n")).isEmpty();
    }

    @Test
    void htmlInsteadOfTextFilesIsAWarning() {
        var f = new AiReadinessService.Fetched("<!doctype html><html>app</html>", BigDecimal.ZERO, null);
        assertThat(AiReadinessService.robotsCheck("mani.example.com", f).status()).isEqualTo("warn");
        assertThat(AiReadinessService.llmsCheck("mani.example.com", f).status()).isEqualTo("warn");
        var small = new AiReadinessService.Fetched("# Shop\n> short", BigDecimal.ZERO, null);
        assertThat(AiReadinessService.llmsCheck("x.com", small).status()).isEqualTo("warn");
    }

    @Test
    void pageCheckFlagsJsOnlyTextAndMissingSchema() {
        var good = new PageSnapshot(List.of("{}"), List.of("H1|Title"), List.of(), List.of(), List.of(), "word ".repeat(400));
        var ok = AiReadinessService.pageCheck("https://x.com/a", new PageFetcher.Fetched(good, 200, "DIRECT", BigDecimal.ZERO, null), false);
        assertThat(ok.status()).isEqualTo("ok");
        var shell = new PageSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), "Loading");
        var bad = AiReadinessService.pageCheck("https://x.com/b", new PageFetcher.Fetched(shell, 200, "DIRECT", BigDecimal.ZERO, null), true);
        assertThat(bad.status()).isEqualTo("fail");
        assertThat(bad.detail()).contains("JavaScript", "no H1", "JSON-LD");
    }
}
