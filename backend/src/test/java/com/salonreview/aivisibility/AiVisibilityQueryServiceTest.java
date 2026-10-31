package com.salonreview.aivisibility;

import com.salonreview.domain.AiVisibilityCheck;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiVisibilityQueryServiceTest {

    private static AiVisibilityCheck check(Instant at, boolean named) {
        return AiVisibilityCheck.builder().engine(Engine.GEMINI).queryId(1L).checkedAt(at).status(AiVisibilityCheck.Status.OK).mentioned(named).build();
    }

    @Test
    void percentHiddenBelowThreeChecks() {
        Instant now = Instant.now();
        var cell = AiVisibilityQueryService.cell(List.of(check(now, true), check(now.minus(Duration.ofDays(7)), false)), now);
        assertThat(cell.latest()).isEqualTo("NAMED");
        assertThat(cell.allTime().named()).isEqualTo(1);
        assertThat(cell.allTime().total()).isEqualTo(2);
        assertThat(cell.allTime().percent()).isNull();
    }

    @Test
    void allTimeAndRecentWindows() {
        Instant now = Instant.now();
        List<AiVisibilityCheck> newestFirst = new ArrayList<>();
        for (int week = 0; week < 12; week++) newestFirst.add(check(now.minus(Duration.ofDays(7L * week)), week != 10));
        var cell = AiVisibilityQueryService.cell(newestFirst, now);
        assertThat(cell.allTime().percent()).isEqualTo(92);
        assertThat(cell.recent().total()).isEqualTo(8);
        assertThat(cell.recent().percent()).isEqualTo(100);
    }

    @Test
    void errorsDoNotCountButShowAsLatest() {
        Instant now = Instant.now();
        AiVisibilityCheck err = AiVisibilityCheck.builder().engine(Engine.GEMINI).checkedAt(now).status(AiVisibilityCheck.Status.ERROR).build();
        var cell = AiVisibilityQueryService.cell(List.of(err, check(now.minus(Duration.ofDays(7)), true)), now);
        assertThat(cell.latest()).isEqualTo("ERROR");
        assertThat(cell.allTime().total()).isEqualTo(1);
    }

    @Test
    void rankingExcludesOwnDomainsAndCountsOncePerAnswer() {
        AiVisibilityCheck a = AiVisibilityCheck.builder().queryId(1L).placesNamed(List.of("The Beau", "Avo Brows"))
                .sourcesCited(List.of(Map.of("domain", "yelp.com"), Map.of("domain", "yelp.com", "title", "x"), Map.of("domain", "pmu-annakara.com"))).build();
        AiVisibilityCheck b = AiVisibilityCheck.builder().queryId(2L).placesNamed(List.of("the beau")).sourcesCited(List.of(Map.of("domain", "yelp.com"))).build();
        var places = AiVisibilityQueryService.rankPlaces(List.of(a, b), Map.of(1L, "q1", 2L, "q2"), 3);
        assertThat(places.get(0).name()).isEqualTo("The Beau");
        assertThat(places.get(0).count()).isEqualTo(2);
        assertThat(places.get(0).queries()).containsExactly("q1", "q2");
        var sources = AiVisibilityQueryService.rankSources(List.of(a, b), Map.of(1L, "q1", 2L, "q2"), List.of("pmu-annakara.com"), 3);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).count()).isEqualTo(2);
    }

    @Test
    void nextRunIsMondaySixPacific() {
        Instant sundayNoon = ZonedDateTime.parse("2026-10-18T12:00:00-07:00[America/Los_Angeles]").toInstant();
        assertThat(AiVisibilityQueryService.nextScheduledRun(sundayNoon)).isEqualTo(ZonedDateTime.parse("2026-10-19T06:00:00-07:00[America/Los_Angeles]").toInstant());
        Instant mondayLate = ZonedDateTime.parse("2026-10-19T07:00:00-07:00[America/Los_Angeles]").toInstant();
        assertThat(AiVisibilityQueryService.nextScheduledRun(mondayLate)).isEqualTo(ZonedDateTime.parse("2026-10-26T06:00:00-07:00[America/Los_Angeles]").toInstant());
    }

    @Test
    void placeKeyNormalizesEntitiesAndCase() {
        assertThat(AiVisibilityQueryService.placeKey("Nail Care &amp; Spa")).isEqualTo(AiVisibilityQueryService.placeKey("nail care & spa"));
    }

    @Test
    void multiPointLatestRunIsSummarized() {
        Instant now = Instant.now();
        var a = AiVisibilityCheck.builder().engine(Engine.GOOGLE_AI_MODE).runId(5L).checkedAt(now).status(AiVisibilityCheck.Status.OK).mentioned(true).mentionRank((short) 3).build();
        var b = AiVisibilityCheck.builder().engine(Engine.GOOGLE_AI_MODE).runId(5L).checkedAt(now).status(AiVisibilityCheck.Status.OK).mentioned(false).build();
        var c = AiVisibilityCheck.builder().engine(Engine.GOOGLE_AI_MODE).runId(5L).checkedAt(now).status(AiVisibilityCheck.Status.NO_ANSWER).build();
        var cell = AiVisibilityQueryService.cell(List.of(a, b, c), now);
        assertThat(cell.latest()).isEqualTo("NAMED");
        assertThat(cell.pointsNamed()).isEqualTo(1);
        assertThat(cell.pointsTotal()).isEqualTo(3);
        assertThat(cell.rank()).isEqualTo((short) 3);
    }

    private static AiVisibilityCheck at(String iso, long q, Engine e, boolean named, boolean cited) {
        return AiVisibilityCheck.builder().engine(e).queryId(q).checkedAt(ZonedDateTime.parse(iso + "[America/Los_Angeles]").toInstant())
                .status(AiVisibilityCheck.Status.OK).mentioned(named).ownSiteCited(cited).mentionRank(named ? (short) 2 : null).build();
    }

    @Test
    void progressDeltaIsLikeForLikeAndPerWeek() {
        String w1 = "2026-10-19T06:30:00-07:00", w2 = "2026-10-26T06:30:00-07:00";
        List<AiVisibilityCheck> ok = List.of(
                at(w1, 1, Engine.GEMINI, false, false), at(w1, 2, Engine.GEMINI, true, true), at(w1, 3, Engine.CHATGPT, true, false),
                at(w2, 1, Engine.GEMINI, true, true), at(w2, 2, Engine.GEMINI, true, true));
        var p = AiVisibilityQueryService.buildProgress(Map.of(1L, "a", 2L, "b", 3L, "c"), ok);
        assertThat(p.weeksAvailable()).isEqualTo(2);
        assertThat(p.weeks().get(0).mentionedPercent()).isEqualTo(67);
        assertThat(p.weeks().get(1).mentionedPercent()).isEqualTo(100);
        assertThat(p.weeks().get(1).avgRank()).isEqualTo(2.0);
        // only pairs (1,GEMINI) and (2,GEMINI) exist in both weeks: 0->1 and 1->1 = +50 points
        assertThat(p.delta().comparedPairs()).isEqualTo(2);
        assertThat(p.delta().mentionedPoints()).isEqualTo(50);
        assertThat(p.delta().direction()).isEqualTo("up");
        assertThat(p.queries().stream().filter(q -> q.queryId() == 1L).findFirst().orElseThrow().mentionedPercent()).containsExactly(0, 100);
    }

    @Test
    void progressFlatWithinThresholdAndSingleWeekHasNoDelta() {
        var one = AiVisibilityQueryService.buildProgress(Map.of(1L, "a"), List.of(at("2026-10-26T06:30:00-07:00", 1, Engine.GEMINI, true, false)));
        assertThat(one.delta()).isNull();
        var flat = AiVisibilityQueryService.buildProgress(Map.of(1L, "a"), List.of(
                at("2026-10-19T06:30:00-07:00", 1, Engine.GEMINI, true, false), at("2026-10-26T06:30:00-07:00", 1, Engine.GEMINI, true, false)));
        assertThat(flat.delta().direction()).isEqualTo("flat");
    }
}
