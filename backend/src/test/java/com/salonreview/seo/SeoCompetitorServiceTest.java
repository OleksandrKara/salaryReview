package com.salonreview.seo;

import com.salonreview.domain.SeoCompetitor;
import com.salonreview.domain.SeoCompetitorPageSnapshot;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.repo.SeoCompetitorPageSnapshotRepository;
import com.salonreview.repo.SeoCompetitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SeoCompetitorServiceTest {

    private SeoCompetitorRepository competitors;
    private SeoCompetitorPageSnapshotRepository pageSnapshots;
    private SeoCompetitorService service;

    @BeforeEach
    void setUp() {
        competitors = mock(SeoCompetitorRepository.class);
        pageSnapshots = mock(SeoCompetitorPageSnapshotRepository.class);
        service = new SeoCompetitorService(competitors, pageSnapshots);
        when(competitors.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("competitors() maps each row's latest CWV, null when nothing synced yet")
    void competitorsMapsLatestVitalsOrNull() {
        SeoCompetitor competitor = SeoCompetitor.builder()
                .id(1L).businessId(1L).name("Competitor A").website("https://competitor-a.example")
                .active(true).build();
        when(competitors.findByBusinessIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(competitor));
        SeoCompetitorPageSnapshot mobileSnapshot = SeoCompetitorPageSnapshot.builder()
                .competitorId(1L).date(LocalDate.of(2026, 9, 1)).strategy(SeoPageSnapshot.Strategy.MOBILE)
                .performanceScore(80).lcpMs(2000).build();
        when(pageSnapshots.findFirstByCompetitorIdAndStrategyOrderByDateDesc(1L, SeoPageSnapshot.Strategy.MOBILE))
                .thenReturn(Optional.of(mobileSnapshot));
        when(pageSnapshots.findFirstByCompetitorIdAndStrategyOrderByDateDesc(1L, SeoPageSnapshot.Strategy.DESKTOP))
                .thenReturn(Optional.empty());

        List<SeoCompetitorService.CompetitorRow> rows = service.competitors(1L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).latestMobile().performanceScore()).isEqualTo(80);
        assertThat(rows.get(0).latestDesktop()).isNull();
    }

    @Test
    @DisplayName("addCompetitor() creates an active row with the given fields")
    void addCompetitorCreatesActiveRow() {
        service.addCompetitor(1L, "Competitor A", "https://competitor-a.example", "Downtown San Diego", "notes");

        verify(competitors).save(argThat(c -> c.getBusinessId().equals(1L) && c.getName().equals("Competitor A")
                && c.getWebsite().equals("https://competitor-a.example") && c.isActive()));
    }

    @Test
    @DisplayName("updateCompetitorGbp() is business-scoped and sets gbpUpdatedAt")
    void updateCompetitorGbpIsBusinessScoped() {
        SeoCompetitor competitor = SeoCompetitor.builder().id(1L).businessId(1L).active(true).build();
        when(competitors.findByIdAndBusinessId(1L, 1L)).thenReturn(Optional.of(competitor));

        service.updateCompetitorGbp(1L, 1L, BigDecimal.valueOf(4.5), 120);

        assertThat(competitor.getGbpRating()).isEqualByComparingTo("4.5");
        assertThat(competitor.getGbpReviewCount()).isEqualTo(120);
        assertThat(competitor.getGbpUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateCompetitorGbp() is a no-op when the id doesn't belong to the calling business")
    void updateCompetitorGbpNoOpForWrongBusiness() {
        when(competitors.findByIdAndBusinessId(1L, 2L)).thenReturn(Optional.empty());

        service.updateCompetitorGbp(2L, 1L, BigDecimal.valueOf(4.5), 120);

        verify(competitors, never()).save(any());
    }

    @Test
    @DisplayName("removeCompetitor() deletes only when the id belongs to the calling business")
    void removeCompetitorIsBusinessScoped() {
        SeoCompetitor competitor = SeoCompetitor.builder().id(1L).businessId(1L).build();
        when(competitors.findByIdAndBusinessId(1L, 1L)).thenReturn(Optional.of(competitor));

        service.removeCompetitor(1L, 1L);

        verify(competitors).delete(competitor);
    }
}
