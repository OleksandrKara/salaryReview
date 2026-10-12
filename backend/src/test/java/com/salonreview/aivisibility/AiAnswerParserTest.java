package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real DataForSEO responses captured 2026-10-12 (src/test/resources/aivisibility). */
class AiAnswerParserTest {

    private static final List<String> NAILS_ALIASES = List.of("AK.LUX.NAILS", "AK Lux Nails", "AKLuxNails", "AK Lux");
    private static final List<String> NAILS_DOMAINS = List.of("akluxnails.com");
    private static final List<String> PMU_ALIASES = List.of("Anna Kara", "PMU Anna Kara", "Anna Kara's Permanent Makeup Studio");
    private static final List<String> PMU_DOMAINS = List.of("pmu-annakara.com");

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in = AiAnswerParserTest.class.getResourceAsStream("/aivisibility/" + name + ".json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void chatGptScraperNamesUsFirstAndCitesOurSite() throws Exception {
        ParsedAnswer a = AiAnswerParser.parse(Engine.CHATGPT, fixture("chatgpt"));
        assertThat(a.text()).contains("AK.LUX.NAILS");
        assertThat(a.places()).startsWith("AK.LUX.NAILS");
        assertThat(a.citations()).extracting(ParsedAnswer.Citation::domain).contains("akluxnails.com");
        assertThat(a.subQueries()).isNotEmpty();
        MentionDetector.Result m = MentionDetector.detect(a, NAILS_ALIASES, NAILS_DOMAINS);
        assertThat(m.mentioned()).isTrue();
        assertThat(m.rank()).isEqualTo((short) 1);
        assertThat(m.ownSiteCited()).isTrue();
        assertThat(m.excerpt().length()).isLessThanOrEqualTo(MentionDetector.EXCERPT_CHARS + 2);
    }

    @Test
    void googleAiModeListsPlacesAndReferencesWithoutUs() throws Exception {
        ParsedAnswer a = AiAnswerParser.parse(Engine.GOOGLE_AI_MODE, fixture("google_ai_mode"));
        assertThat(a.text()).doesNotContain("![](");
        assertThat(a.places()).contains("Easy Beauty Space", "Mamasita Beauty Room");
        assertThat(a.citations()).extracting(ParsedAnswer.Citation::domain).contains("yelp.com");
        assertThat(MentionDetector.detect(a, NAILS_ALIASES, NAILS_DOMAINS).mentioned()).isFalse();
    }

    @Test
    void geminiRedirectUrlsUseTitleAsDomainAndFindsPmu() throws Exception {
        ParsedAnswer a = AiAnswerParser.parse(Engine.GEMINI, fixture("gemini"));
        assertThat(a.citations()).noneMatch(c -> c.domain().contains("vertexaisearch"));
        assertThat(a.places()).contains("Brows Made Studio & Academy").doesNotContain("Location", "Highlights", "powder brows (ombré powder brows)");
        assertThat(MentionDetector.detect(a, PMU_ALIASES, PMU_DOMAINS).mentioned()).isTrue();
    }

    @Test
    void perplexityAndClaudeParsePlaces() throws Exception {
        ParsedAnswer p = AiAnswerParser.parse(Engine.PERPLEXITY, fixture("perplexity"));
        assertThat(p.places()).contains("Skin by Sarah", "AVO Brows & Beauty").doesNotContain("ombré/powder brows");
        assertThat(MentionDetector.detect(p, PMU_ALIASES, PMU_DOMAINS).mentioned()).isFalse();
        ParsedAnswer c = AiAnswerParser.parse(Engine.CLAUDE, fixture("claude"));
        assertThat(c.places()).contains("Nailoscope", "Easy Beauty Space");
    }

    @Test
    void nullResultIsEmpty() {
        assertThat(AiAnswerParser.parse(Engine.GEMINI, null).isEmpty()).isTrue();
    }
}
