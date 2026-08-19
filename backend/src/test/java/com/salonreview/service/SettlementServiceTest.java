package com.salonreview.service;

import com.salonreview.domain.PayPeriod;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-19 cross-tenant fix (security-review pass): {@code settlementsFor(payPeriodId)} used a
 * bare, business-unscoped {@code periods.findById} — the same {@code pay_periods} table
 * {@code PayPeriodController} was already fixed for, but this sibling read path (a legacy route
 * under {@code /api/pay-periods/{id}/settlements}) was missed. Any business's OWNER could read
 * another business's real pay-period label/date range and its providers' settlement figures.
 */
class SettlementServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private PayPeriodRepository periods;
    private PeriodEntryRepository entries;
    private SettlementService service;

    @BeforeEach
    void setUp() {
        periods = mock(PayPeriodRepository.class);
        entries = mock(PeriodEntryRepository.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        service = new SettlementService(periods, entries, providers, salonConfig, currentBusinessContext,
                mock(CommissionCalculator.class), mock(MessageFormatter.class));
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: settlementsFor() 404s (via NoSuchElementException) for "
            + "a pay period belonging to another business, instead of reading it by bare id")
    void settlementsForRejectsAnotherBusinessesPayPeriod() {
        // The OLD unscoped findById would have found this period (it genuinely exists — just under
        // business 2) — stubbing it as found is what makes this a real proof rather than a
        // coincidental pass against Mockito's unstubbed-empty-Optional default.
        PayPeriod foreign = PayPeriod.builder().id(42L).businessId(2L)
                .year(2026).month(8).half(com.salonreview.domain.Half.FIRST).label("Business 2's period").build();
        when(periods.findById(42L)).thenReturn(Optional.of(foreign));
        when(periods.findByIdAndBusinessId(42L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settlementsFor(42L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("42");

        verify(entries, never()).findAllByPayPeriodId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("settlementsFor() still resolves a pay period belonging to the caller's own business")
    void settlementsForFindsOwnPayPeriod() {
        PayPeriod period = PayPeriod.builder().id(42L).businessId(BUSINESS_ID)
                .year(2026).month(8).half(com.salonreview.domain.Half.FIRST).label("Aug 1-15").build();
        when(periods.findByIdAndBusinessId(42L, BUSINESS_ID)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.settlementsFor(42L))
                .isInstanceOf(IllegalStateException.class); // salon config lookup not stubbed — proves we got past the period check

        verify(periods).findByIdAndBusinessId(42L, BUSINESS_ID);
    }
}
