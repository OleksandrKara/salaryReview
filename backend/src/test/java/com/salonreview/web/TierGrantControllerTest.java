package com.salonreview.web;

import com.salonreview.domain.TierGrant;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.TierGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-19 cross-tenant fix (security-review pass): {@code grant()}/{@code revoke()} used to
 * pass the caller-supplied {@code providerId} straight to {@link TierGrantRepository}'s
 * business-unscoped {@code findByProviderIdAndYearAndMonth}/{@code deleteByProviderIdAndYearAndMonth}
 * — any business's OWNER could force or revoke another business's provider's 50/50 tier for a real
 * month, corrupting that business's payroll. {@code list()} was already correctly scoped.
 */
class TierGrantControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private TierGrantRepository grants;
    private ProviderRepository providers;
    private TierGrantController controller;

    @BeforeEach
    void setUp() {
        grants = mock(TierGrantRepository.class);
        providers = mock(ProviderRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        controller = new TierGrantController(grants, providers, currentBusinessContext);
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: grant() 404s for a provider belonging to another business")
    void grantRejectsAnotherBusinessesProvider() {
        when(providers.existsByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(false);

        assertThatThrownBy(() -> controller.grant(5L, 2026, 8))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such provider");

        verify(grants, never()).save(org.mockito.ArgumentMatchers.any());
        verify(grants, never()).findByProviderIdAndYearAndMonth(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: revoke() 404s for a provider belonging to another business")
    void revokeRejectsAnotherBusinessesProvider() {
        when(providers.existsByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(false);

        assertThatThrownBy(() -> controller.revoke(5L, 2026, 8))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such provider");

        verify(grants, never()).deleteByProviderIdAndYearAndMonth(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("grant()/revoke() succeed for a provider of the caller's own business")
    void grantAndRevokeSucceedForOwnProvider() {
        when(providers.existsByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(true);
        when(grants.findByProviderIdAndYearAndMonth(5L, 2026, 8)).thenReturn(Optional.empty());
        when(grants.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            TierGrant g = inv.getArgument(0);
            g.setId(9L);
            return g;
        });

        controller.grant(5L, 2026, 8);
        controller.revoke(5L, 2026, 8);

        verify(grants).save(org.mockito.ArgumentMatchers.any());
        verify(grants).deleteByProviderIdAndYearAndMonth(5L, 2026, 8);
    }
}
