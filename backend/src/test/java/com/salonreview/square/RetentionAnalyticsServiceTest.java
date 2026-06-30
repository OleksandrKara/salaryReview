package com.salonreview.square;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.web.dto.RetentionReport;
import com.salonreview.web.dto.RetentionReport.ProviderRetentionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetentionAnalyticsService}. Dates are relative to "now" so the cohort-maturity
 * gate (window elapsed) is deterministic regardless of when CI runs: the test month is 4 months ago,
 * comfortably past the 60-day window.
 */
class RetentionAnalyticsServiceTest {

    private ProviderVisitRepository repo;
    private RetentionAnalyticsService service;

    private final YearMonth testMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(4);
    private final YearMonth prev = testMonth.minusMonths(1);
    private final YearMonth next = testMonth.plusMonths(1);

    @BeforeEach
    void setUp() {
        repo = mock(ProviderVisitRepository.class);
        SquareClient square = mock(SquareClient.class);
        when(square.locationTimeZone()).thenReturn("UTC");
        service = new RetentionAnalyticsService(repo, square);
    }

    private static ProviderVisit visit(String cust, String prov, LocalDate date, boolean rebooked) {
        return ProviderVisit.builder().customerId(cust).providerRef(prov).providerName("Alice".equals(prov) ? prov : prov)
                .serviceDate(date).rebookedSameDay(rebooked).build();
    }

    @Test
    @DisplayName("new/returning, cohort retention (provider vs salon), rebook, and the acquisition-leak flag")
    void fullScenario() {
        List<ProviderVisit> rows = new ArrayList<>(List.of(
                visit("C3", "P1", prev.atDay(10), false),    // C3 is a prior client of P1
                visit("C3", "P1", testMonth.atDay(5), false),
                visit("C1", "P1", testMonth.atDay(8), true),  // new to P1, rebooked same day
                visit("C2", "P1", testMonth.atDay(9), false), // new to P1, never returns
                visit("C4", "P1", testMonth.atDay(15), false),// new to P1
                visit("C1", "P1", next.atDay(10), false),     // C1 returns to P1 (within 60d)
                visit("C4", "P2", next.atDay(12), false)));   // C4 returns, but to a DIFFERENT provider
        rows.sort(Comparator.comparing(ProviderVisit::getServiceDate));
        when(repo.findAllByOrderByServiceDateAsc()).thenReturn(rows);

        RetentionReport report = service.report(testMonth.getYear(), testMonth.getMonthValue());

        assertThat(report.providers()).hasSize(1); // only P1 active in the test month
        ProviderRetentionRow p1 = report.providers().get(0);
        assertThat(p1.clientsSeen()).isEqualTo(4);            // C1,C2,C3,C4
        assertThat(p1.newToProvider()).isEqualTo(3);          // C1,C2,C4 (C3 is prior)
        assertThat(p1.returningToProvider()).isEqualTo(1);    // C3
        assertThat(p1.newToSalonViaP()).isEqualTo(3);         // C1,C2,C4 first salon visit was with P1
        assertThat(p1.cohortMatured()).isTrue();
        assertThat(p1.providerRetention()).isEqualByComparingTo("0.3333"); // only C1 returned to P1
        assertThat(p1.salonRetention()).isEqualByComparingTo("0.6667");    // C1 + C4 returned to the salon
        assertThat(p1.sameDayRebookRate()).isEqualByComparingTo("0.2500"); // 1 of 4 visits
        assertThat(p1.leakRisk()).isTrue(); // 3 fresh clients, provider retention 33% < 40%
    }

    @Test
    @DisplayName("series: salon-level and provider-filtered new vs returning over a range")
    void series() {
        YearMonth m1 = YearMonth.now(ZoneOffset.UTC).minusMonths(6);
        YearMonth m2 = m1.plusMonths(1);
        when(repo.findAllByOrderByServiceDateAsc()).thenReturn(List.of(
                visit("C1", "P1", m1.atDay(5), false),
                visit("C1", "P1", m2.atDay(5), false),   // C1 returns
                visit("C2", "P2", m2.atDay(8), false)));  // C2 is new in m2

        var salon = service.series(m1.getYear(), m1.getMonthValue(), m2.getYear(), m2.getMonthValue(), null);
        assertThat(salon.points()).hasSize(2);
        assertThat(salon.points().get(0).newClients()).isEqualTo(1);       // m1: C1 new
        assertThat(salon.points().get(0).returningClients()).isEqualTo(0);
        assertThat(salon.points().get(1).clientsSeen()).isEqualTo(2);      // m2: C1 + C2
        assertThat(salon.points().get(1).newClients()).isEqualTo(1);       // C2 new to salon
        assertThat(salon.points().get(1).returningClients()).isEqualTo(1); // C1 returning
        assertThat(salon.providers()).extracting("ref").containsExactlyInAnyOrder("P1", "P2");

        var p1 = service.series(m1.getYear(), m1.getMonthValue(), m2.getYear(), m2.getMonthValue(), "P1");
        assertThat(p1.points().get(1).clientsSeen()).isEqualTo(1);         // only C1 with P1 in m2
        assertThat(p1.points().get(1).newClients()).isEqualTo(0);         // C1 first-with-P1 was m1
        assertThat(p1.points().get(1).returningClients()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty ledger → no providers")
    void emptyLedger() {
        when(repo.findAllByOrderByServiceDateAsc()).thenReturn(List.of());
        RetentionReport report = service.report(testMonth.getYear(), testMonth.getMonthValue());
        assertThat(report.providers()).isEmpty();
        assertThat(report.retentionWindowDays()).isEqualTo(60);
    }

    @Test
    @DisplayName("a recent (in-flight) cohort is not judged — retention is null, not 0%")
    void immatureCohortNotJudged() {
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
        when(repo.findAllByOrderByServiceDateAsc()).thenReturn(List.of(
                visit("C1", "P1", thisMonth.atDay(1), false)));

        ProviderRetentionRow p1 = service.report(thisMonth.getYear(), thisMonth.getMonthValue())
                .providers().get(0);

        assertThat(p1.cohortMatured()).isFalse();
        assertThat(p1.providerRetention()).isNull();
        assertThat(p1.salonRetention()).isNull();
        assertThat(p1.leakRisk()).isFalse();
    }
}
