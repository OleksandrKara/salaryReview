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

    @Test
    void googleAiOverviewParsesLikeAiModeAndFindsPmuCited() throws Exception {
        ParsedAnswer a = AiAnswerParser.parse(Engine.GOOGLE_AI_OVERVIEW, fixture("google_ai_overview"));
        assertThat(a.text()).contains("microblading");
        assertThat(a.citations()).extracting(ParsedAnswer.Citation::domain).contains("pmu-annakara.com");
        MentionDetector.Result m = MentionDetector.detect(a, PMU_ALIASES, PMU_DOMAINS);
        assertThat(m.mentioned()).isTrue();
        assertThat(m.ownSiteCited()).isTrue();
    }

    @Test
    void htmlEntityInPlaceNameIsDecoded() {
        assertThat(AiAnswerParser.boldPlaces("Try **Nail Care &amp; Spa** downtown")).containsExactly("Nail Care & Spa");
    }

    @Test
    void googleLocalResultLinesAreReadAsPlaces() {
        String answer = """
                Here are top-rated permanent makeup studios right in and around La Mesa, CA:

                ---

                Bespoke Brows 5.0 (124)Permanent make-up clinic
                Open 8055 La Mesa Blvd Ste 103 0.3 mi
                Call Directions Website Bespoke Brows (8055 La Mesa Blvd Ste 103, La Mesa) – A specialized PMU clinic.

                ---

                Wizard of Brows Microblading & Eyelash Lift San Diego 5.0 (189)Permanent make-up clinic
                Open 3691 Via Mercado #11 4.1 mi

                ---

                Luxe Ink Aesthetics Beauty salon
                Closed 3969 Fourth Ave Ste 302E 0.4 mi
                Call Directions Website Luxe Ink Aesthetics — lip blushing, priced around $500.
                """;
        assertThat(AiAnswerParser.localResultPlaces(answer))
                .containsExactly("Bespoke Brows", "Wizard of Brows Microblading & Eyelash Lift San Diego", "Luxe Ink Aesthetics");
    }
}
