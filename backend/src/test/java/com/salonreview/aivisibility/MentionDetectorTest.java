package com.salonreview.aivisibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MentionDetectorTest {

    private static ParsedAnswer answer(String text, List<String> places, List<ParsedAnswer.Citation> citations) {
        return new ParsedAnswer(text, citations, places, List.of());
    }

    @Test
    void punctuationVariantsMatch() {
        List<String> aliases = List.of("AK.LUX.NAILS");
        assertThat(MentionDetector.detect(answer("Try Ak Lux Nails downtown.", List.of(), List.of()), aliases, List.of()).mentioned()).isTrue();
        assertThat(MentionDetector.detect(answer("Try AKLuxNails downtown.", List.of(), List.of()), aliases, List.of()).mentioned()).isTrue();
    }

    @Test
    void similarNamesAreNotMentions() {
        List<String> aliases = List.of("Anna Kara", "PMU Anna Kara");
        ParsedAnswer a = answer("Anna's Nails and Kara Beauty Bar are popular.", List.of("Anna's Nails", "Kara Beauty Bar"), List.of());
        assertThat(MentionDetector.detect(a, aliases, List.of("pmu-annakara.com")).mentioned()).isFalse();
    }

    @Test
    void possessiveCountsAsMention() {
        ParsedAnswer a = answer("Anna Kara's studio downtown is known for brows.", List.of("Anna Kara's Permanent Makeup Studio"), List.of());
        MentionDetector.Result r = MentionDetector.detect(a, List.of("Anna Kara"), List.of());
        assertThat(r.mentioned()).isTrue();
        assertThat(r.rank()).isEqualTo((short) 1);
    }

    @Test
    void citedButNotNamed() {
        ParsedAnswer a = answer("Here are good options for scar camouflage.", List.of("Restorative PMU"),
                List.of(new ParsedAnswer.Citation("pmu-annakara.com", "Scar camouflage", "https://pmu-annakara.com/scar-camouflage/")));
        MentionDetector.Result r = MentionDetector.detect(a, List.of("Anna Kara"), List.of("pmu-annakara.com"));
        assertThat(r.mentioned()).isTrue();
        assertThat(r.ownSiteCited()).isTrue();
        assertThat(r.rank()).isNull();
    }

    @Test
    void subdomainCountsAsOwn() {
        assertThat(MentionDetector.isOwnDomain("edu.pmu-annakara.com", List.of("pmu-annakara.com"))).isTrue();
        assertThat(MentionDetector.isOwnDomain("notpmu-annakara.com", List.of("pmu-annakara.com"))).isFalse();
    }

    @Test
    void excerptShowsLateMention() {
        String text = "x ".repeat(2000) + "Then AK.LUX.NAILS is great." + " y".repeat(500);
        MentionDetector.Result r = MentionDetector.detect(answer(text, List.of(), List.of()), List.of("AK.LUX.NAILS"), List.of());
        assertThat(r.excerpt()).contains("AK.LUX.NAILS");
    }

    @Test
    void serviceNamesAndRatingSummariesAreNotPlaces() {
        var places = AiAnswerParser.boldPlaces("**Russian manicures** at **Svitnail Studio**, **One question**, **0/5**, **Highest Rated with Perfect Scores**, **Polinails**");
        assertThat(places).containsExactly("Svitnail Studio", "Polinails");
    }
}
