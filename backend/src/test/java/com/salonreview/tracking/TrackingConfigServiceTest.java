package com.salonreview.tracking;

import com.salonreview.domain.TrackingConfig;
import com.salonreview.repo.TrackingConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackingConfigServiceTest {

    private final TrackingConfigRepository repo = mock(TrackingConfigRepository.class);
    private final TrackingConfigService service = new TrackingConfigService(repo);

    @Test
    @DisplayName("list() maps every row for the business, filling in the known human label for a "
            + "recognized hostname")
    void listMapsKnownLabel() {
        when(repo.findAllByBusinessIdOrderByHostname(1L)).thenReturn(List.of(
                TrackingConfig.builder().businessId(1L).hostname("akluxnails.com").clarityProjectId("abc123")
                        .updatedAt(Instant.parse("2026-09-01T00:00:00Z")).build()));

        List<TrackingConfigService.Site> sites = service.list(1L);

        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).hostname()).isEqualTo("akluxnails.com");
        assertThat(sites.get(0).siteLabel()).isEqualTo("AK.LUX.NAILS — marketing site");
        assertThat(sites.get(0).clarityProjectId()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("list() falls back to the raw hostname as the label for anything not in the "
            + "hand-maintained registry — never throws for an unrecognized site")
    void listFallsBackToRawHostnameForUnknownSite() {
        when(repo.findAllByBusinessIdOrderByHostname(3L)).thenReturn(List.of(
                TrackingConfig.builder().businessId(3L).hostname("some-new-site.com").build()));

        assertThat(service.list(3L).get(0).siteLabel()).isEqualTo("some-new-site.com");
    }

    @Test
    @DisplayName("update() saves the new id and clears it on blank input")
    void updateSavesOrClears() {
        TrackingConfig row = TrackingConfig.builder().businessId(1L).hostname("akluxnails.com").build();
        when(repo.findByHostname("akluxnails.com")).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TrackingConfigService.Site updated = service.update(1L, "akluxnails.com", "  new-id  ", "owner");

        assertThat(updated.clarityProjectId()).isEqualTo("new-id");
        assertThat(row.getClarityProjectId()).isEqualTo("new-id");
        assertThat(row.getUpdatedBy()).isEqualTo("owner");

        service.update(1L, "akluxnails.com", "   ", "owner");
        assertThat(row.getClarityProjectId()).isNull();
    }

    @Test
    @DisplayName("update() 404s for a hostname belonging to a different business — never lets one "
            + "business edit another's tracking config just by knowing its hostname")
    void updateRejectsCrossTenantHostname() {
        when(repo.findByHostname("book.pmu-annakara.com")).thenReturn(Optional.of(
                TrackingConfig.builder().businessId(2L).hostname("book.pmu-annakara.com").build()));

        assertThatThrownBy(() -> service.update(1L, "book.pmu-annakara.com", "abc", "owner"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such site");
    }

    @Test
    @DisplayName("update() 404s for a hostname with no row at all")
    void updateRejectsUnknownHostname() {
        when(repo.findByHostname("nope.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(1L, "nope.com", "abc", "owner"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("clarityProjectIdFor(): the id if configured, null if the row exists but is "
            + "unset, and null if there's no row at all — the internal endpoint treats all three "
            + "\"nothing to inject\" cases identically")
    void clarityProjectIdForCoversAllThreeCases() {
        when(repo.findByHostname("configured.com")).thenReturn(Optional.of(
                TrackingConfig.builder().hostname("configured.com").clarityProjectId("xyz").build()));
        when(repo.findByHostname("unset.com")).thenReturn(Optional.of(
                TrackingConfig.builder().hostname("unset.com").clarityProjectId(null).build()));
        when(repo.findByHostname("unknown.com")).thenReturn(Optional.empty());

        assertThat(service.clarityProjectIdFor("configured.com")).isEqualTo("xyz");
        assertThat(service.clarityProjectIdFor("unset.com")).isNull();
        assertThat(service.clarityProjectIdFor("unknown.com")).isNull();
    }
}
