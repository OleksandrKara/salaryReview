package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoogleAdsReportFormatTest {

    private static GoogleAdsWeeklyReportService.Period p(String spend, String revenue) {
        return new GoogleAdsWeeklyReportService.Period(new BigDecimal(spend), 10, 100, new BigDecimal(revenue), 1, 1);
    }

    @Test
    void romiIsProfitOverSpend() {
        assertEquals("100%", GoogleAdsWeeklyReportService.romi(p("500", "1000")));
        assertEquals("-100%", GoogleAdsWeeklyReportService.romi(p("500", "0")));
        assertEquals("нет расхода", GoogleAdsWeeklyReportService.romi(p("0", "300")));
    }

    @Test
    void moneyHasThousandsSeparator() {
        assertEquals("$1,470", GoogleAdsWeeklyReportService.money(new BigDecimal("1470.4")));
    }

    @Test
    void parsesAccountMapAndSkipsGarbage() {
        assertEquals(Map.of(2L, "7340887092"), GoogleAdsSyncService.parseAccounts("2=734-088-7092, x, y=1"));
    }

    @Test
    void parsesChangeTimeInAccountZone() {
        assertNotNull(GoogleAdsSyncService.parseChangeTime("2026-11-03 10:11:22.123456"));
        assertNotNull(GoogleAdsSyncService.parseChangeTime("2026-11-03 10:11:22"));
        assertNull(GoogleAdsSyncService.parseChangeTime("bad"));
    }
}
