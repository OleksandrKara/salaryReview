package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Provider;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.web.dto.ProviderPatchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 2026-08-18 live vulnerability: patch/delete used to be business-unscoped (providers.findById/
 * existsById/deleteById directly) — any OWNER could PATCH or DELETE any other business's provider
 * by id. */
class ProviderControllerTest {

    private static final Long BUSINESS_A = 1L;

    private ProviderRepository providers;
    private CurrentBusinessContext currentBusinessContext;
    private ProviderController controller;

    @BeforeEach
    void setUp() {
        providers = mock(ProviderRepository.class);
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_A);
        controller = new ProviderController(providers, currentBusinessContext);
    }

    @Test
    @DisplayName("patch updates a provider that genuinely belongs to the caller's business")
    void patchUpdatesOwnBusinesssProvider() {
        Provider p = Provider.builder().id(5L).businessId(BUSINESS_A).name("n").displayName("d")
                .commissionRate(new BigDecimal("0.45")).cardTipFeeRate(new BigDecimal("0.035")).active(true).build();
        when(providers.findByIdAndBusinessId(5L, BUSINESS_A)).thenReturn(Optional.of(p));
        var req = new ProviderPatchRequest("New Name", null, null, null, null);

        var dto = controller.patch(5L, req);

        assertThat(dto.name()).isEqualTo("New Name");
    }

    @Test
    @DisplayName("patch cannot reach another business's provider by id")
    void patchRejectsAnotherBusinesssProviderId() {
        when(providers.findByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(Optional.empty());
        var req = new ProviderPatchRequest("Hijacked", null, null, null, null);

        assertThatThrownBy(() -> controller.patch(99L, req))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("delete cannot reach another business's provider by id")
    void deleteRejectsAnotherBusinesssProviderId() {
        when(providers.findByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.delete(99L))
                .isInstanceOf(NoSuchElementException.class);

        verify(providers, never()).delete(any());
        verify(providers, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete removes a provider that genuinely belongs to the caller's business")
    void deleteRemovesOwnBusinesssProvider() {
        Provider p = Provider.builder().id(5L).businessId(BUSINESS_A).name("n").displayName("d")
                .commissionRate(new BigDecimal("0.45")).cardTipFeeRate(new BigDecimal("0.035")).active(true).build();
        when(providers.findByIdAndBusinessId(5L, BUSINESS_A)).thenReturn(Optional.of(p));

        controller.delete(5L);

        verify(providers).delete(p);
    }
}
