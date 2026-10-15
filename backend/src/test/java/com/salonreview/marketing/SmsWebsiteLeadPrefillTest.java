package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsWebsiteLeadPrefillTest {

    @Test
    void recognizesBothSitesPrefilledFirstMessages() {
        for (String body : new String[]{
                "Hi! I have a question about Lip Blush Tattoo.",
                "Hi! I have a question for AK.LUX.NAILS. ",
                "Hi! I'd like to book for a group at AK.LUX.NAILS. We are 4 people",
                "Hi! I'd like to request a gift card for $100 at AK.LUX.NAILS.",
                "Hi! I'd like to prepay for 3+ visits and save 10% at AK.LUX.NAILS."}) {
            assertThat(SmsWebsiteLeadService.PREFILL.matcher(body).find()).as(body).isTrue();
        }
        assertThat(SmsWebsiteLeadService.PREFILL.matcher("5").find()).isFalse();
        assertThat(SmsWebsiteLeadService.PREFILL.matcher("Can I come at 3?").find()).isFalse();
    }
}
