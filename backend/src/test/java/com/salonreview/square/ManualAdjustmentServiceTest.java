package com.salonreview.square;

import com.salonreview.domain.ManualAdjustment;
import com.salonreview.repo.ManualAdjustmentRepository;
import com.salonreview.repo.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-19 cross-tenant fix (security-review pass): {@code create()}'s provider check and
 * {@code delete()} both used business-unscoped lookups ({@code providers.existsById},
 * {@code adjustments.existsById}/{@code deleteById}) — any business's OWNER could credit or
 * deduct commission on another business's provider, or delete another business's manual
 * adjustment, by guessing/supplying a foreign id. {@code list()}/the totals methods were already
 * correctly scoped.
 */
class ManualAdjustmentServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private ManualAdjustmentRepository adjustments;
    private ProviderRepository providers;
    private ManualAdjustmentService service;

    @BeforeEach
    void setUp() {
        adjustments = mock(ManualAdjustmentRepository.class);
        providers = mock(ProviderRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        service = new ManualAdjustmentService(adjustments, providers, currentBusinessContext);
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: create() rejects a provider belonging to another business")
    void createRejectsAnotherBusinessesProvider() {
        // The OLD unscoped lookup would have found this provider (it genuinely exists — just under
        // a different business) — stubbing it as found is what makes this a real proof rather than
        // a coincidental pass against Mockito's unstubbed-false default (confirmed by the revert
        // test below, which fails without this stub in place on the pre-fix code).
        when(providers.existsById(5L)).thenReturn(true);
        when(providers.existsByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(false);
        var req = new ManualAdjustmentService.CreateRequest(5L, LocalDate.of(2026, 5, 1),
                new BigDecimal("100.00"), null, null, null);

        assertThatThrownBy(() -> service.create(req, "manager"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such provider");

        verify(adjustments, never()).save(any());
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: delete() 404s for an adjustment belonging to another business")
    void deleteRejectsAnotherBusinessesAdjustment() {
        // Same reasoning as above: the OLD existsById lookup would have found this row.
        when(adjustments.existsById(9L)).thenReturn(true);
        when(adjustments.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such adjustment");

        verify(adjustments, never()).delete(any());
        verify(adjustments, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete() succeeds for an adjustment of the caller's own business")
    void deleteSucceedsForOwnBusiness() {
        ManualAdjustment adj = ManualAdjustment.builder().id(9L).providerId(5L)
                .serviceDate(LocalDate.of(2026, 5, 1)).gross(new BigDecimal("100.00"))
                .discount(BigDecimal.ZERO).tip(BigDecimal.ZERO).build();
        when(adjustments.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(adj));

        service.delete(9L);

        verify(adjustments).delete(adj);
    }
}
