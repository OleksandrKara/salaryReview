package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.aivisibility.PageComponentExtractor.Component;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Saved real pages (2026-10-12, scripts/styles stripped): our own /russian-pedicure and PMU
 * /microblading-san-diego/, pmusandiego.com, and thebeaupmu.com through the On-Page custom_js
 * fallback (its server answered our direct request with 429). */
class PageComponentExtractorTest {

    static String read(String name) throws Exception {
        try (InputStream in = PageComponentExtractorTest.class.getResourceAsStream("/aivisibility/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static PageComponentExtractor.Result html(String name, String url) throws Exception {
        return PageComponentExtractor.extract(PageSnapshot.fromHtml(read(name), url));
    }

    static PageComponentExtractor.Result theBeau() throws Exception {
        var n = new ObjectMapper().readTree(read("onpage_custom_js.json"));
        return PageComponentExtractor.extract(PageSnapshot.fromCustomJs(new ObjectMapper().readTree(n.path("custom_js_response").asText())));
    }

    @Test
    void ownRussianPedicurePage() throws Exception {
        var r = html("own_russian_pedicure.html", "https://akluxnails.com/russian-pedicure");
        assertThat(r.businessPage()).isTrue();
        assertThat(r.components().get(Component.PRICES).present()).isTrue();
        assertThat(r.components().get(Component.BOOKING_CTA).present()).isTrue();
        assertThat(r.components().get(Component.MAP_NAP).evidence()).contains("1357 Seventh Ave");
        assertThat(r.components().get(Component.BUSINESS_SCHEMA).evidence()).contains("nailsalon");
        assertThat(r.components().get(Component.FAQ).present()).isFalse();
        assertThat(r.components().get(Component.BEFORE_AFTER).present()).isFalse();
    }

    @Test
    void ownPmuMicrobladingPage() throws Exception {
        var r = html("own_pmu_microblading.html", "https://pmu-annakara.com/microblading-san-diego/");
        assertThat(r.components().get(Component.FAQ).evidence()).isEqualTo("FAQPage schema");
        assertThat(r.components().get(Component.RATING_STATED).present()).isTrue();
        assertThat(r.components().get(Component.ARTIST_BIO).present()).isTrue();
        assertThat(r.components().get(Component.AFTERCARE).present()).isTrue();
        assertThat(r.components().get(Component.FINANCING).present()).isTrue();
        assertThat(r.components().get(Component.MAP_NAP).evidence()).isEqualTo("Google Maps embed");
        @SuppressWarnings("unchecked") List<String> h2 = (List<String>) r.metrics().get("h2Texts");
        assertThat(h2).contains("What Is Microblading?");
    }

    @Test
    void competitorPages() throws Exception {
        var lena = html("competitor_pmusandiego.html", "https://pmusandiego.com/");
        assertThat(lena.components().get(Component.ARTIST_BIO).evidence()).contains("years of experience");
        assertThat(lena.components().get(Component.BOOKING_CTA).present()).isTrue();
        assertThat(lena.components().get(Component.PRICES).present()).isFalse();

        var beau = theBeau();
        assertThat(beau.businessPage()).isTrue();
        assertThat(beau.components().get(Component.BEFORE_AFTER).present()).isTrue();
        assertThat(beau.components().get(Component.FAQ).present()).isTrue();
        assertThat(beau.components().get(Component.VIDEO).present()).isTrue();
        assertThat(beau.components().get(Component.REVIEWS_ON_PAGE).evidence()).contains("What Clients Say");
        assertThat(beau.components().get(Component.PROCESS_STEPS).present()).isFalse();
    }

    @Test
    void faqFromQuestionHeadingsAndSchemaTypes() {
        var p = new PageSnapshot(List.of("{\"@type\": [\"BeautySalon\", \"LocalBusiness\"]}"),
                List.of("H2|Does it hurt?", "H2|How long does it last?", "H2|Can I swim after?"), List.of(), List.of(), List.of(), "text");
        var r = PageComponentExtractor.extract(p);
        assertThat(r.components().get(Component.FAQ).evidence()).isEqualTo("3 question headings");
        assertThat(PageComponentExtractor.schemaTypes(p.jsonLd().get(0))).containsExactly("beautysalon", "localbusiness");
    }

    @Test
    void addressWithMapsLinkCountsWithoutPhone() {
        var p = new PageSnapshot(List.of(), List.of(), List.of(), List.of(), List.of("Get directions|https://maps.app.goo.gl/abc"),
                "Visit us at 1357 Seventh Ave, Ste C, San Diego, CA 92101");
        assertThat(PageComponentExtractor.extract(p).components().get(Component.MAP_NAP).present()).isTrue();
        var noLink = new PageSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), "Visit us at 1357 Seventh Ave, San Diego");
        assertThat(PageComponentExtractor.extract(noLink).components().get(Component.MAP_NAP).present()).isFalse();
    }
}
