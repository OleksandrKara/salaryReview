package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdLeadsServiceTest {

    @Test
    void searchQueryIsUrlDecoded() {
        assertEquals("nano brows san diego", AdLeadsService.term("nano%20brows%20san%20diego"));
        assertEquals("powder brows", AdLeadsService.term("powder+brows"));
    }

    @Test
    void missingOrUnfilledQueriesAreNull() {
        assertNull(AdLeadsService.term(null));
        assertNull(AdLeadsService.term("  "));
        assertNull(AdLeadsService.term("{keyword}"));
    }

    @Test
    void leadCampaignTextsResolveToGoogleAdsCampaignIds() {
        var ads = java.util.List.of(
                java.util.Map.entry(1L, "Inweb / Search /  Permanent Eyebrow / макс конв"),
                java.util.Map.entry(2L, "Inweb / Search / Permanent /брови  /  макс конв - 06.11"),
                java.util.Map.entry(2L, "Inweb / Search / Permanent /брови  /  tCPA 60 - 03.11"),
                java.util.Map.entry(3L, "Inweb / PMax /  Permanent  Ombré / Powder / макс конв"),
                java.util.Map.entry(4L, "Inweb / Search / Academy /  макс конв"),
                java.util.Map.entry(5L, "Inweb / Pmax / Permanent / tCPA 60  /15.09"));
        var got = AdLeadsService.resolveCampaignIds(java.util.List.of("Inweb_Search_Permanent_Eyebrow", "Inweb/Search/Permanent_брови",
                "Inweb_PMax_Permanent_Ombre", "Inweb_Search_Academy", "Inweb / Pmax / Permanent / макс конв / 22.01", "Other_Campaign"), ads);
        assertEquals(1L, got.get("Inweb_Search_Permanent_Eyebrow"));
        assertEquals(2L, got.get("Inweb/Search/Permanent_брови"));
        assertEquals(3L, got.get("Inweb_PMax_Permanent_Ombre"));
        assertEquals(4L, got.get("Inweb_Search_Academy"));
        assertNull(got.get("Inweb / Pmax / Permanent / макс конв / 22.01"));
        assertNull(got.get("Other_Campaign"));
    }

    @Test
    void firstLandingPathMapsToATrackedPage() {
        assertEquals("pmu-book", AdLeadsService.pageSlug("/book/"));
        assertEquals("pmu-book", AdLeadsService.pageSlug("https://pmu-annakara.com/book/?utm_term=x"));
        assertEquals("academy", AdLeadsService.pageSlug("/academy/courses/"));
        assertEquals("pmu-website", AdLeadsService.pageSlug("/"));
        assertEquals("pmu-website", AdLeadsService.pageSlug("/microblading-san-diego/"));
        assertEquals("", AdLeadsService.pageSlug(null));
        assertEquals("", AdLeadsService.pageSlug("  "));
    }
}
