package com.salonreview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessPublicSiteUrlTest {

    @Test
    void maniHostMapsToSlashPath() {
        assertThat(Business.builder().publicDomain("mani.akluxnails.com").build().publicSiteUrl())
                .isEqualTo("https://akluxnails.com/mani");
    }

    @Test
    void otherDomainsKeepTheirHost() {
        assertThat(Business.builder().publicDomain("book.pmu-annakara.com").build().publicSiteUrl())
                .isEqualTo("https://book.pmu-annakara.com");
    }

    @Test
    void missingDomainIsNull() {
        assertThat(Business.builder().build().publicSiteUrl()).isNull();
        assertThat(Business.builder().publicDomain(" ").build().publicSiteUrl()).isNull();
    }
}
