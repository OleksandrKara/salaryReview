package com.salonreview.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoIpServiceTest {

    @Test
    void flagFromCountryCode() {
        assertThat(GeoIpService.flag("FR")).isEqualTo("🇫🇷");
        assertThat(GeoIpService.flag(null)).isEmpty();
    }

    @Test
    void ignoresUnknownCloudflareCodes() {
        assertThat(GeoIpService.normalizeCode("xx")).isNull();
        assertThat(GeoIpService.normalizeCode("T1")).isNull();
        assertThat(GeoIpService.normalizeCode(" fr ")).isEqualTo("FR");
    }

    @Test
    void noIpAndNoCountryGivesNothing() {
        assertThat(new GeoIpService().describe(null, null)).isNull();
    }
}
