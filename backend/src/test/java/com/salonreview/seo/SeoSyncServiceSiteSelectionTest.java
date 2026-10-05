package com.salonreview.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeoSyncServiceSiteSelectionTest {

    private static SearchConsoleClient.Site site(String url) {
        return new SearchConsoleClient.Site(url, "siteRestrictedUser");
    }

    @Test
    @DisplayName("one shared service account: each business gets its own property, Domain property preferred")
    void picksTheBusinessesOwnProperty() {
        List<SearchConsoleClient.Site> sites = List.of(
                site("https://pmu-annakara.com/"), site("sc-domain:akluxnails.com"), site("sc-domain:pmu-annakara.com"));

        assertThat(SeoSyncService.selectSite(sites, "mani.akluxnails.com").siteUrl()).isEqualTo("sc-domain:akluxnails.com");
        assertThat(SeoSyncService.selectSite(sites, "book.pmu-annakara.com").siteUrl()).isEqualTo("sc-domain:pmu-annakara.com");
        assertThat(SeoSyncService.selectSite(List.of(site("https://www.pmu-annakara.com/")), "book.pmu-annakara.com").siteUrl())
                .isEqualTo("https://www.pmu-annakara.com/");
    }

    @Test
    @DisplayName("no match: the only site when there is one (old behavior), an error instead of a guess when several")
    void noMatch() {
        assertThat(SeoSyncService.selectSite(List.of(site("sc-domain:akluxnails.com")), null).siteUrl())
                .isEqualTo("sc-domain:akluxnails.com");
        assertThatThrownBy(() -> SeoSyncService.selectSite(
                List.of(site("sc-domain:akluxnails.com"), site("sc-domain:example.com")), "book.pmu-annakara.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("book.pmu-annakara.com");
    }

    @Test
    @DisplayName("registrableDomain: last two labels")
    void registrableDomain() {
        assertThat(SeoSyncService.registrableDomain("book.pmu-annakara.com")).isEqualTo("pmu-annakara.com");
        assertThat(SeoSyncService.registrableDomain("akluxnails.com")).isEqualTo("akluxnails.com");
        assertThat(SeoSyncService.registrableDomain("localhost")).isNull();
        assertThat(SeoSyncService.registrableDomain(null)).isNull();
    }
}
