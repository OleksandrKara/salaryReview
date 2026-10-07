package com.salonreview.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortLinkHostsTest {

    @Test
    void pmuLinksUseTheStudioDomainAndEveryoneElseKeepsTheDefault() {
        ShortLinkHosts hosts = new ShortLinkHosts("https://salon.akluxnails.com", "2=https://go.pmu-annakara.com/");
        assertThat(hosts.shortLink(2L, "abc")).isEqualTo("https://go.pmu-annakara.com/r/abc");
        assertThat(hosts.shortLink(1L, "abc")).isEqualTo("https://salon.akluxnails.com/r/abc");
        assertThat(hosts.shortLink(99L, "abc")).isEqualTo("https://salon.akluxnails.com/r/abc");
    }

    @Test
    void anEmptyOrBrokenSpecFallsBackToTheDefault() {
        assertThat(new ShortLinkHosts("https://salon.akluxnails.com", "").shortLink(2L, "x")).isEqualTo("https://salon.akluxnails.com/r/x");
        assertThat(new ShortLinkHosts("https://salon.akluxnails.com", "garbage, 3=").shortLink(3L, "x")).isEqualTo("https://salon.akluxnails.com/r/x");
        ShortLinkHosts two = new ShortLinkHosts("https://d", " 1 = https://a , 2=https://b");
        assertThat(two.shortLink(1L, "t")).isEqualTo("https://a/r/t");
        assertThat(two.shortLink(2L, "t")).isEqualTo("https://b/r/t");
    }
}
