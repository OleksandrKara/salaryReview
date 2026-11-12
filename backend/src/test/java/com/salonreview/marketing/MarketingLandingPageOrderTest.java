package com.salonreview.marketing;

import com.salonreview.marketing.MarketingDashboardRepository.LandingPageSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

class MarketingLandingPageOrderTest {

    private MarketingDashboardRepository repoWith(List<String> slugsOldestFirst) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.query(anyString(), any(RowMapper.class), anyLong())).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            return slugsOldestFirst.stream().map(slug -> {
                try {
                    ResultSet rs = Mockito.mock(ResultSet.class);
                    Mockito.when(rs.getString("slug")).thenReturn(slug);
                    Mockito.when(rs.getString("name")).thenReturn("Name " + slug);
                    return mapper.mapRow(rs, 0);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        });
        return new MarketingDashboardRepository(jdbc);
    }

    @Test
    @DisplayName("business 1: inactive mani goes last and home becomes the default")
    void business1() {
        MarketingDashboardRepository repo = repoWith(List.of("mani", "home"));
        assertThat(repo.listLandingPages(1L)).extracting(LandingPageSummary::slug).containsExactly("home", "mani");
        assertThat(repo.listLandingPages(1L)).extracting(LandingPageSummary::active).containsExactly(true, false);
        assertThat(repo.findDefaultSlugForBusiness(1L)).isEqualTo(Optional.of("home"));
    }

    @Test
    @DisplayName("business 2: inactive AK PMU Landing goes last, others keep oldest-first order")
    void business2() {
        MarketingDashboardRepository repo = repoWith(List.of("pmu", "pmu-website", "academy", "pmu-book"));
        assertThat(repo.listLandingPages(2L)).extracting(LandingPageSummary::slug)
                .containsExactly("pmu-website", "academy", "pmu-book", "pmu");
        assertThat(repo.findDefaultSlugForBusiness(2L)).isEqualTo(Optional.of("pmu-website"));
    }

    @Test
    @DisplayName("a business with only inactive pages still gets a default and a selectable list")
    void onlyInactive() {
        MarketingDashboardRepository repo = repoWith(List.of("mani"));
        assertThat(repo.listLandingPages(1L)).extracting(LandingPageSummary::slug).containsExactly("mani");
        assertThat(repo.findDefaultSlugForBusiness(1L)).isEqualTo(Optional.of("mani"));
    }
}
