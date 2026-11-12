package com.salonreview.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.metrics.ResponseTimes.Shift;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadFirstTouchTest {

    private static final Instant NOW = Instant.parse("2026-11-11T20:00:00Z");
    private static final List<Shift> DAY = List.of(new Shift(1, Instant.parse("2026-11-11T16:00:00Z"), Instant.parse("2026-11-11T23:00:00Z")));

    private static LeadFirstTouch.Lead lead(long id, String created, String status, String touch) {
        return new LeadFirstTouch.Lead(id, "+15550001111", Instant.parse(created), status, touch == null ? null : Instant.parse(touch), 1L);
    }

    @Test
    void measuresOnlyShiftTimeUntilFirstTouch() {
        var r = LeadFirstTouch.compute(List.of(lead(1, "2026-11-11T15:00:00Z", "CONTACTED", "2026-11-11T16:04:00Z")), DAY, NOW);
        assertThat(r.touched()).singleElement().satisfies(t -> {
            assertThat(t.effectiveSeconds()).isEqualTo(240);
            assertThat(t.onShift()).isTrue();
        });
    }

    @Test
    void touchOutsideShiftIsFlagged() {
        var r = LeadFirstTouch.compute(List.of(lead(1, "2026-11-11T10:00:00Z", "CONTACTED", "2026-11-11T12:00:00Z")), DAY, NOW);
        assertThat(r.touched()).singleElement().satisfies(t -> {
            assertThat(t.onShift()).isFalse();
            assertThat(t.effectiveSeconds()).isZero();
        });
    }

    @Test
    void untouchedNewLeadIsPendingAndClosedOneIsNot() {
        var r = LeadFirstTouch.compute(List.of(
                lead(1, "2026-11-11T19:00:00Z", "NEW", null),
                lead(2, "2026-11-11T19:00:00Z", "SPAM", null)), DAY, NOW);
        assertThat(r.pending()).singleElement().satisfies(p -> assertThat(p.effectiveSeconds()).isEqualTo(3600));
        assertThat(r.closedWithoutContact()).isEqualTo(1);
        assertThat(r.touched()).isEmpty();
    }
}
