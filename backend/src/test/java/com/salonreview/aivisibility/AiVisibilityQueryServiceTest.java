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
}
