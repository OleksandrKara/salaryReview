package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.marketing.LeadFollowUpService.DigestItem;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadReminderDigestTextTest {

    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 11, 12, hour, minute, 0, 0, PT);
    }

    @Test
    void digestHoursAreEightElevenFourteenSeventeenOnly() {
        for (int h : new int[] {8, 11, 14, 17}) assertThat(LeadFollowUpService.isDigestHour(at(h, 0))).isTrue();
        for (int h : new int[] {0, 7, 9, 10, 12, 13, 15, 16, 18, 19, 20, 23}) assertThat(LeadFollowUpService.isDigestHour(at(h, 0))).isFalse();
        assertThat(LeadFollowUpService.isDigestHour(at(11, 30))).isFalse();
    }

    @Test
    void textCapsListAndReportsCounts() {
        List<DigestItem> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(new DigestItem(i, "Lead" + i, "Google", i == 0 ? "call back" : null, 5 + i * 60L));
        String t = LeadFollowUpService.digestText(items, 4, 7, "https://x/admin/leads?business=1");
        assertThat(t).contains("20 overdue").contains("New leads waiting: 4").contains("no next step: 7")
                .contains("Lead14").doesNotContain("Lead15").contains("+5 more").contains("Note: call back").contains("5 min overdue").contains("1 h overdue")
                .endsWith("https://x/admin/leads?business=1");
        assertThat(t.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(15);
    }
}
