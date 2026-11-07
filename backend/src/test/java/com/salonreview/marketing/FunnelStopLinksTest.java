package com.salonreview.marketing;

import com.salonreview.sms.ConsultationFollowUpLinks;
import com.salonreview.sms.RebookingPromoSigner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class FunnelStopLinksTest {

    private final RebookingPromoSigner signer = Mockito.mock(RebookingPromoSigner.class);

    @Test
    void academyStopLinkNamesThePmuBusiness() {
        assertThat(new AcademyLeadLinks(signer, "https://salon.test").stopUrl(7))
                .isEqualTo("https://salon.test/admin/leads/open?academy=7&business=2");
    }

    @Test
    void consultationStopLinkNamesThePmuBusiness() {
        assertThat(new ConsultationFollowUpLinks(signer, "https://salon.test").stopUrl(9))
                .isEqualTo("https://salon.test/admin/leads/open?consult=9&business=2");
    }
}
