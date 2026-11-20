package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.web.dto.MarketingConversionsDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingConversionsServiceTest {

    @Test
    void splitsResultsBySourceAndTotalsOnlyTheSelectedOnes() {
        UUID page = UUID.randomUUID();
        MarketingDashboardRepository dash = mock(MarketingDashboardRepository.class);
        MarketingConversionsRepository repo = mock(MarketingConversionsRepository.class);
        SquareClientProvider squares = mock(SquareClientProvider.class);
        SquareClient square = mock(SquareClient.class);
        when(square.locationTimeZone()).thenReturn("America/Los_Angeles");
        when(squares.forBusiness(anyLong())).thenReturn(square);
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(2L);
        when(dash.findLandingPageId("pmu", 2L)).thenReturn(Optional.of(page));
        when(dash.findStatsSince(page)).thenReturn(Optional.empty());

        when(repo.countVisitorsByChannel(any(), any(), any())).thenReturn(Map.of(
                "google_ads", Map.of("views", 100L, "sms", 4L, "call", 2L),
                "direct", Map.of("views", 50L, "sms", 1L)));
        Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
        when(repo.findContacts(any(), any(), any(), any())).thenReturn(List.of(
                new MarketingConversionsRepository.ContactRow("c1", "b1", "x", t0, "google_ads"),
                new MarketingConversionsRepository.ContactRow("c2", "b2", "x", t0, "direct")));
        when(repo.findPayments(any(), any())).thenReturn(List.of(
                new MarketingConversionsRepository.PaymentRow("c1", new BigDecimal("100"), t0.plusSeconds(60)),
                new MarketingConversionsRepository.PaymentRow("c1", new BigDecimal("250"), t0.plusSeconds(120)),
                new MarketingConversionsRepository.PaymentRow("c2", new BigDecimal("100"), t0.plusSeconds(60))));
        when(repo.findGoogleAdsCampaigns(any(), any(), any())).thenReturn(List.of(
                new MarketingConversionsRepository.AdsCampaignRow(1L, "Brows", "ENABLED", 25_500_000L, 900, 30, 2.0)));

        var service = new MarketingConversionsService(dash, repo, squares, ctx);
        MarketingConversionsDto dto = service.conversions("pmu", null, null, Set.of("google_ads"));

        assertThat(dto.pageViews()).isEqualTo(100);
        assertThat(dto.payingCustomers()).isEqualTo(1);
        assertThat(dto.revenue()).isEqualByComparingTo("350");
        assertThat(dto.bySource()).hasSize(7);
        var direct = dto.bySource().stream().filter(r -> r.key().equals("direct")).findFirst().orElseThrow();
        assertThat(direct.visitors()).isEqualTo(50);
        assertThat(direct.deposits()).isEqualTo(1);
        var google = dto.bySource().stream().filter(r -> r.key().equals("google_ads")).findFirst().orElseThrow();
        assertThat(google.fullPayments()).isEqualTo(1);
        assertThat(dto.googleAds().spend()).isEqualByComparingTo("25.50");
        assertThat(dto.googleAds().clicks()).isEqualTo(30);
    }
}
