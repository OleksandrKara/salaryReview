package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketingCacServiceTest {

    @Test
    void cacIsSpendPerPayingCustomerAndCplIsSpendPerLead() {
        var c = MarketingCacService.channel("google_ads", "Google Ads", new BigDecimal("300.00"), false,
                new AdLeadsService.Cohort(10, 3, new BigDecimal("900")));
        assertThat(c.cac()).isEqualByComparingTo("100.00");
        assertThat(c.cpl()).isEqualByComparingTo("30.00");
        assertThat(c.roas()).isEqualByComparingTo("3.00");
        assertThat(c.payers()).isEqualTo(3);
    }

    @Test
    void nothingIsDividedByZeroOrBySpendThatIsUnknown() {
        var noPayers = MarketingCacService.channel("meta_ads", "Meta Ads", new BigDecimal("500"), true,
                new AdLeadsService.Cohort(5, 0, BigDecimal.ZERO));
        assertThat(noPayers.cac()).isNull();
        assertThat(noPayers.cpl()).isEqualByComparingTo("100.00");
        assertThat(noPayers.spendEstimated()).isTrue();
        var noSpend = MarketingCacService.channel("meta_ads", "Meta Ads", BigDecimal.ZERO, false,
                new AdLeadsService.Cohort(4, 2, new BigDecimal("200")));
        assertThat(noSpend.cac()).isNull();
        assertThat(noSpend.roas()).isNull();
    }
}
