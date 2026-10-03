package com.salonreview.square;

import com.salonreview.domain.Redo;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.RedoRepository;
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
 * 2026-08-19 cross-tenant fix (security-review pass): {@code create()}'s two provider checks and
 * {@code delete()} all used business-unscoped lookups — any business's OWNER could move
 * commission between another business's providers, or delete another business's redo record, by
 * guessing/supplying a foreign id. {@code list()} was already correctly scoped.
 */
class RedoServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private RedoRepository redos;
    private ProviderRepository providers;
    private RedoService service;

    @BeforeEach
    void setUp() {
        redos = mock(RedoRepository.class);
        providers = mock(ProviderRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        service = new RedoService(redos, providers, currentBusinessContext, mock(SettlementPreviewService.class));
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: create() rejects when the redo provider belongs to another business")
    void createRejectsAnotherBusinessesRedoProvider() {
        // The OLD unscoped lookups would have found both providers (they genuinely exist — provider
        // 2 just belongs to a different business) — stubbing them as found is what makes this a
        // real proof rather than a coincidental pass against Mockito's unstubbed-false default.
        when(providers.existsById(1L)).thenReturn(true);
        when(providers.existsById(2L)).thenReturn(true);
        when(providers.existsByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(true);
        when(providers.existsByIdAndBusinessId(2L, BUSINESS_ID)).thenReturn(false); // redo provider — foreign
        var req = new RedoService.CreateRequest(1L, 2L, LocalDate.of(2026, 5, 10),
                LocalDate.of(2026, 5, 20), new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> service.create(req, "manager"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such provider");

        verify(redos, never()).save(any());
    }

    @Test
    @DisplayName("2026-08-19 cross-tenant fix: delete() 404s for a redo belonging to another business")
    void deleteRejectsAnotherBusinessesRedo() {
        when(redos.existsById(9L)).thenReturn(true); // the OLD lookup would have found it
        when(redos.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such redo");

        verify(redos, never()).delete(any());
        verify(redos, never()).deleteById(any());
    }

    @Test
    @DisplayName("create()/delete() succeed for the caller's own business's providers")
    void createAndDeleteSucceedForOwnBusiness() {
        when(providers.existsByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(true);
        when(providers.existsByIdAndBusinessId(2L, BUSINESS_ID)).thenReturn(true);
        when(redos.save(any())).thenAnswer(inv -> {
            Redo r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });
        var req = new RedoService.CreateRequest(1L, 2L, LocalDate.of(2026, 5, 10),
                LocalDate.of(2026, 5, 20), new BigDecimal("100.00"), null);

        service.create(req, "manager");

        Redo redo = Redo.builder().id(9L).originalProviderId(1L).redoProviderId(2L)
                .originalDate(LocalDate.of(2026, 5, 10)).redoDate(LocalDate.of(2026, 5, 20))
                .amount(new BigDecimal("100.00")).build();
        when(redos.findByIdAndBusinessId(9L, BUSINESS_ID)).thenReturn(Optional.of(redo));

        service.delete(9L);

        verify(redos).delete(redo);
    }

    @Test
    @DisplayName("update() edits every field of this business's redo and keeps who created it")
    void updateEditsOwnRedo() {
        Redo existing = Redo.builder().id(7L).originalProviderId(1L).redoProviderId(2L)
                .originalDate(LocalDate.of(2026, 9, 1)).redoDate(LocalDate.of(2026, 9, 5))
                .amount(new BigDecimal("90.00")).serviceName("Gel").createdBy("owner").build();
        when(redos.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(existing));
        when(providers.existsByIdAndBusinessId(1L, BUSINESS_ID)).thenReturn(true);
        when(providers.existsByIdAndBusinessId(3L, BUSINESS_ID)).thenReturn(true);
        when(redos.save(any(Redo.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(7L, new RedoService.CreateRequest(1L, 3L, LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 6), new BigDecimal("120.00"), "  Pedicure "));

        org.assertj.core.api.Assertions.assertThat(existing.getRedoProviderId()).isEqualTo(3L);
        org.assertj.core.api.Assertions.assertThat(existing.getOriginalDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        org.assertj.core.api.Assertions.assertThat(existing.getRedoDate()).isEqualTo(LocalDate.of(2026, 9, 6));
        org.assertj.core.api.Assertions.assertThat(existing.getAmount()).isEqualByComparingTo("120.00");
        org.assertj.core.api.Assertions.assertThat(existing.getServiceName()).isEqualTo("Pedicure");
        org.assertj.core.api.Assertions.assertThat(existing.getCreatedBy()).isEqualTo("owner");
        org.assertj.core.api.Assertions.assertThat(view.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("update() of another business's redo is a 404 and saves nothing")
    void updateRejectsForeignRedo() {
        when(redos.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(7L, new RedoService.CreateRequest(1L, 3L, LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 6), new BigDecimal("120.00"), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such redo");
        verify(redos, never()).save(any());
    }

    @Test
    @DisplayName("update() applies the same validation as create(): same provider twice is rejected, nothing saved")
    void updateValidates() {
        Redo existing = Redo.builder().id(7L).originalProviderId(1L).redoProviderId(2L)
                .originalDate(LocalDate.of(2026, 9, 1)).redoDate(LocalDate.of(2026, 9, 5))
                .amount(new BigDecimal("90.00")).build();
        when(redos.findByIdAndBusinessId(7L, BUSINESS_ID)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.update(7L, new RedoService.CreateRequest(1L, 1L, LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 6), new BigDecimal("120.00"), null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(redos, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(existing.getRedoProviderId()).isEqualTo(2L);
    }
}
