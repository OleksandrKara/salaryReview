package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.web.dto.PeriodEntryUpsertRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 2026-08-18 live vulnerability: get/upsertEntry/delete used to be business-unscoped
 * (periods/providers.findById/existsById/deleteById directly) — any OWNER could read, write, or
 * delete another business's real payroll data (procedures, card/cash totals, commission) by id. */
class PayPeriodControllerTest {

    private static final Long BUSINESS_A = 1L;

    private PayPeriodRepository periods;
    private PeriodEntryRepository entries;
    private ProviderRepository providers;
    private CurrentBusinessContext currentBusinessContext;
    private PayPeriodController controller;

    @BeforeEach
    void setUp() {
        periods = mock(PayPeriodRepository.class);
        entries = mock(PeriodEntryRepository.class);
        providers = mock(ProviderRepository.class);
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_A);
        controller = new PayPeriodController(periods, entries, providers, currentBusinessContext);
    }

    private static PayPeriod period(Long id) {
        return PayPeriod.builder().id(id).businessId(BUSINESS_A).year(2026).month(8).half(Half.FIRST)
                .label("1-15 August 2026").build();
    }

    @Test
    @DisplayName("GET returns detail for a pay period that genuinely belongs to the caller's business")
    void getReturnsOwnBusinesssPeriod() {
        PayPeriod p = period(10L);
        when(periods.findByIdAndBusinessId(10L, BUSINESS_A)).thenReturn(Optional.of(p));
        when(entries.findAllByPayPeriodId(10L)).thenReturn(List.of());

        var response = controller.get(10L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET cannot reach another business's pay period by id — 404, not another business's real payroll data")
    void getRejectsAnotherBusinesssPeriodId() {
        when(periods.findByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(Optional.empty());

        var response = controller.get(99L);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(entries, never()).findAllByPayPeriodId(any());
    }

    @Test
    @DisplayName("upsertEntry cannot write payroll data onto another business's period")
    void upsertEntryRejectsAnotherBusinesssPeriodId() {
        when(periods.findByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(Optional.empty());
        var req = new PeriodEntryUpsertRequest(1, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, null, null);

        assertThatThrownBy(() -> controller.upsertEntry(99L, 5L, req))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Pay period");

        verify(entries, never()).save(any());
    }

    @Test
    @DisplayName("upsertEntry cannot attribute payroll data to another business's provider, even under the caller's own period")
    void upsertEntryRejectsAnotherBusinesssProviderId() {
        PayPeriod p = period(10L);
        when(periods.findByIdAndBusinessId(10L, BUSINESS_A)).thenReturn(Optional.of(p));
        when(providers.findByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(Optional.empty());
        var req = new PeriodEntryUpsertRequest(1, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, null, null);

        assertThatThrownBy(() -> controller.upsertEntry(10L, 99L, req))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Provider");

        verify(entries, never()).save(any());
    }

    @Test
    @DisplayName("upsertEntry writes correctly when both period and provider genuinely belong to the caller's business")
    void upsertEntrySucceedsForOwnBusiness() {
        PayPeriod p = period(10L);
        Provider provider = Provider.builder().id(5L).businessId(BUSINESS_A).name("n").displayName("d")
                .commissionRate(new BigDecimal("0.45")).cardTipFeeRate(new BigDecimal("0.035")).active(true).build();
        when(periods.findByIdAndBusinessId(10L, BUSINESS_A)).thenReturn(Optional.of(p));
        when(providers.findByIdAndBusinessId(5L, BUSINESS_A)).thenReturn(Optional.of(provider));
        when(entries.findByPayPeriodIdAndProviderId(10L, 5L)).thenReturn(Optional.empty());
        when(entries.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new PeriodEntryUpsertRequest(3, new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("10.00"), BigDecimal.ZERO, null, null);

        var dto = controller.upsertEntry(10L, 5L, req);

        assertThat(dto.procedures()).isEqualTo(3);
    }

    @Test
    @DisplayName("DELETE cannot reach another business's pay period by id")
    void deleteRejectsAnotherBusinesssPeriodId() {
        when(periods.findByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.delete(99L))
                .isInstanceOf(NoSuchElementException.class);

        verify(periods, never()).delete(any());
        verify(periods, never()).deleteById(any());
    }

    @Test
    @DisplayName("DELETE removes a pay period that genuinely belongs to the caller's business")
    void deleteRemovesOwnBusinesssPeriod() {
        PayPeriod p = period(10L);
        when(periods.findByIdAndBusinessId(10L, BUSINESS_A)).thenReturn(Optional.of(p));

        controller.delete(10L);

        verify(periods).delete(p);
    }
}
