package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.CancellationClearance;
import com.salonreview.domain.Half;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.CancellationClearanceRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareMonthAggregator.CancelledCandidate;
import com.salonreview.square.SquareMonthAggregator.Diag;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the cancellation warning summary: provider cancellations are counted per half, cleared
 * ones drop off, and cancellations assigned to owner/manager app accounts are excluded.
 */
class CancelledAppointmentServiceTest {

    private SquareMonthAggregator aggregator;
    private AppUserRepository users;
    private ProviderRepository providerRepo;
    private ProviderDirectory providers;
    private CancellationClearanceRepository clearances;
    private CancelledAppointmentService service;

    @BeforeEach
    void setUp() {
        aggregator = mock(SquareMonthAggregator.class);
        SquareClient square = mock(SquareClient.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        providers = mock(ProviderDirectory.class);
        providerRepo = mock(ProviderRepository.class);
        users = mock(AppUserRepository.class);
        clearances = mock(CancellationClearanceRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        service = new CancelledAppointmentService(aggregator, square, salonConfig,
                currentBusinessContext, providers,
                providerRepo, users, clearances);

        // Default: no owner/manager accounts, no clearances.
        when(users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(eq(1L), any())).thenReturn(List.of());
        when(clearances.findAllBySquareBookingIdIn(any())).thenReturn(List.of());
    }

    private static CancelledCandidate candidate(String bookingId, String teamId, Half half) {
        return new CancelledCandidate(bookingId, "CUST", teamId, "Tech " + teamId, "VAR",
                LocalDate.of(2026, 5, half == Half.FIRST ? 3 : 20),
                Instant.now(), half, new BigDecimal("80.00"), null, null);
    }

    private static MonthAggregation aggWith(CancelledCandidate... cs) {
        return new MonthAggregation(2026, 5, "UTC", List.of(), new Diag(), List.of(), List.of(),
                List.of(), List.of(cs));
    }

    private void resolveTeam(String teamId, long providerId) {
        Provider p = mock(Provider.class);
        when(p.getId()).thenReturn(providerId);
        when(providers.resolveOrCreate(eq(teamId), anyString())).thenReturn(p);
    }

    @Test
    void providerCancellationIsCountedPerHalf() {
        resolveTeam("TM_PROV", 10L);
        MonthAggregation agg = aggWith(candidate("bk-1", "TM_PROV", Half.FIRST));

        Map<Long, int[]> summary = service.summaryFor(agg);

        assertThat(summary).containsKey(10L);
        assertThat(summary.get(10L)).containsExactly(1, 0);
    }

    @Test
    void ownerOrManagerCancellationIsExcluded() {
        AppUser owner = AppUser.builder().id(1L).username("owner").role(Role.OWNER)
                .squareTeamMemberId("TM_OWNER").active(true).build();
        when(users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(eq(1L), any())).thenReturn(List.of(owner));
        // Even if resolvable, it must not be counted.
        resolveTeam("TM_OWNER", 99L);
        MonthAggregation agg = aggWith(candidate("bk-owner", "TM_OWNER", Half.FIRST));

        assertThat(service.summaryFor(agg)).isEmpty();
    }

    @Test
    void clearedCancellationDropsOff() {
        resolveTeam("TM_PROV", 10L);
        when(clearances.findAllBySquareBookingIdIn(any())).thenReturn(List.of(
                CancellationClearance.builder().squareBookingId("bk-1").clearedByUsername("owner")
                        .clearedAt(Instant.now()).build()));
        MonthAggregation agg = aggWith(candidate("bk-1", "TM_PROV", Half.FIRST));

        assertThat(service.summaryFor(agg)).isEmpty();
    }

    @Test
    void multiSegmentBookingCountsOnce() {
        resolveTeam("TM_PROV", 10L);
        // Two segments of the same booking (e.g. mani + pedi) must count as one cancelled appointment.
        MonthAggregation agg = aggWith(
                candidate("bk-1", "TM_PROV", Half.SECOND),
                candidate("bk-1", "TM_PROV", Half.SECOND));

        assertThat(service.summaryFor(agg).get(10L)).containsExactly(0, 1);
    }
}
