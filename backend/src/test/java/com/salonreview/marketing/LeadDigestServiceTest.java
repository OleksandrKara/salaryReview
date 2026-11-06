package com.salonreview.marketing;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadDigestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T16:05:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    private static LeadView lead(long id, String channel, String status, String createdAt, String callbackAt, String consultState) {
        return new LeadView(id, channel, "Google", "+1555000" + id, null, "Lead " + id, null, status, callbackAt, createdAt, createdAt,
                1, null, null, null, null, false, null, null, null, createdAt, false, consultState, null);
    }

    @Test
    void morningIsSkippedWhenNothingToProcess() {
        List<LeadView> all = List.of(lead(1, "Call", "BOOKED", "2026-11-05T10:00:00Z", null, null),
                lead(2, "Form", "SPAM", "2026-11-05T10:00:00Z", null, null));
        assertNull(LeadDigestService.morning(all, DAY, NOW));
    }

    @Test
    void morningCountsOverdueNewAndStalledConsultations() {
        List<LeadView> all = List.of(
                lead(1, "Call", "NEW", "2026-11-06T14:00:00Z", null, null),
                lead(2, "Form", "NEW", "2026-11-01T14:00:00Z", null, null),
                lead(3, "Call", "CONTACTED", "2026-11-02T14:00:00Z", "2026-11-05T14:00:00Z", null),
                lead(4, "Form", "CONTACTED", "2026-11-02T14:00:00Z", null, "DONE"),
                lead(5, "Form", "SPAM", "2026-11-06T14:00:00Z", null, null));
        String text = LeadDigestService.morning(all, DAY, NOW);
        assertTrue(text.contains("К обработке: 3"), text);
        assertTrue(text.contains("просроченные перезвоны: 1"), text);
        assertTrue(text.contains("новые: 2"), text);
        assertTrue(text.contains("После консультации не записались: 1"), text);
        assertFalse(text.contains("Lead 5"), text);
        assertFalse(text.contains("—"), text);
    }

    @Test
    void eveningSummarisesTheDayAndSkipsAnEmptyOne() {
        assertNull(LeadDigestService.evening(List.of(), new LeadDigestService.Handled(Map.of(), 0, 0, Map.of()), DAY, NOW));
        List<LeadView> all = List.of(
                lead(1, "Call", "BOOKED", "2026-11-06T15:00:00Z", null, null),
                lead(2, "Form", "NEW", "2026-11-06T15:30:00Z", null, null));
        var handled = new LeadDigestService.Handled(Map.of("BOOKED", 1, "CONTACTED", 2), 1, 1, Map.of("Lena", Map.of("CONTACTED", 2, "BOOKED", 1)));
        String text = LeadDigestService.evening(all, handled, DAY, NOW);
        assertTrue(text.contains("Пришло лидов: 2"), text);
        assertTrue(text.contains("Обработано командой: 3"), text);
        assertTrue(text.contains("Записались сами (Square): 1"), text);
        assertTrue(text.contains("Сегодняшние без реакции: 1"), text);
        assertTrue(text.contains("Остаётся к обработке: 1"), text);
        assertTrue(text.contains("Работал: Lena"), text);

        var two = new LeadDigestService.Handled(Map.of("BOOKED", 1, "CONTACTED", 2), 0, 1,
                Map.of("Lena", Map.of("CONTACTED", 2), "Kate", Map.of("BOOKED", 1)), Map.of("Kate", 1));
        String multi = LeadDigestService.evening(all, two, DAY, NOW);
        assertTrue(multi.contains("• Lena: связались 2"), multi);
        assertTrue(multi.contains("• Kate: записались 1, перезвонов назначено 1"), multi);
    }
}
