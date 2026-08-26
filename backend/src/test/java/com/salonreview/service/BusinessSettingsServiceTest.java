package com.salonreview.service;

import com.salonreview.domain.Business;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SalonConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessSettingsServiceTest {

    private BusinessRepository businesses;
    private SalonConfigRepository salonConfig;
    private BusinessSettingsService service;

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessRepository.class);
        salonConfig = mock(SalonConfigRepository.class);
        service = new BusinessSettingsService(businesses, salonConfig);
        when(businesses.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(salonConfig.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("get() for a business with no salon_config row yet reports configured=false")
    void getReportsUnconfiguredWhenNoRow() {
        when(businesses.findById(2L)).thenReturn(Optional.of(
                Business.builder().id(2L).name("AK PMU").shortCode("annakarapmu").timezone("UTC").active(true).build()));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.empty());

        BusinessSettingsService.View view = service.get(2L);

        assertThat(view.configured()).isFalse();
        assertThat(view.business().getName()).isEqualTo("AK PMU");
    }

    @Test
    @DisplayName("first-time setup requires ownerShortName, baseCommissionRate, and cardTipFeeRate explicitly")
    void firstTimeSetupRequiresCoreMoneyFields() {
        when(businesses.findById(2L)).thenReturn(Optional.of(Business.builder().id(2L).build()));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(2L, null, null, null,
                new BigDecimal("0.45"), false, null, null, new BigDecimal("0.035"), null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ownerShortName");

        assertThatThrownBy(() -> service.update(2L, null, null, "AK",
                null, false, null, null, new BigDecimal("0.035"), null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("baseCommissionRate");

        assertThatThrownBy(() -> service.update(2L, null, null, "AK",
                new BigDecimal("0.45"), false, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cardTipFeeRate");

        verify(salonConfig, never()).save(any());
    }

    @Test
    @DisplayName("first-time setup with tier disabled: threshold/cutoff default to 0 rather than being required")
    void firstTimeSetupTierDisabledDefaultsThresholdAndCutoff() {
        when(businesses.findById(2L)).thenReturn(Optional.of(Business.builder().id(2L).build()));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.empty());

        BusinessSettingsService.View view = service.update(2L, "AK PMU", "America/Los_Angeles", "AK",
                new BigDecimal("0.45"), false, null, null, new BigDecimal("0.035"), null, null, null, null, null, null);

        assertThat(view.config().isTierEnabled()).isFalse();
        assertThat(view.config().getTierServiceThreshold()).isEqualTo(0);
        assertThat(view.config().getServicePriceCutoff()).isEqualByComparingTo("0.00");
        assertThat(view.config().getBaseCommissionRate()).isEqualByComparingTo("0.45");
        assertThat(view.business().getName()).isEqualTo("AK PMU");
        assertThat(view.business().getTimezone()).isEqualTo("America/Los_Angeles");
    }

    @Test
    @DisplayName("first-time setup omitting tierEnabled defaults it to false (opt-in, not opt-out)")
    void firstTimeSetupDefaultsTierDisabled() {
        when(businesses.findById(2L)).thenReturn(Optional.of(Business.builder().id(2L).build()));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.empty());

        BusinessSettingsService.View view = service.update(2L, null, null, "AK",
                new BigDecimal("0.45"), null, null, null, new BigDecimal("0.035"), null, null, null, null, null, null);

        assertThat(view.config().isTierEnabled()).isFalse();
    }

    @Test
    @DisplayName("updating an existing config only touches the fields provided, leaves the rest alone")
    void updateOnlyTouchesProvidedFields() {
        SalonConfig existing = SalonConfig.builder().id(9).businessId(1L).ownerShortName("AK")
                .baseCommissionRate(new BigDecimal("0.45")).tierCommissionRate(new BigDecimal("0.50"))
                .tierServiceThreshold(60).servicePriceCutoff(new BigDecimal("25.00"))
                .cardTipFeeRate(new BigDecimal("0.035")).tierEnabled(true).build();
        when(businesses.findById(1L)).thenReturn(Optional.of(Business.builder().id(1L).name("AK.LUX.NAILS").build()));
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(existing));

        // Only bump the base commission rate — everything else should survive untouched.
        BusinessSettingsService.View view = service.update(1L, null, null, null,
                new BigDecimal("0.48"), null, null, null, null, null, null, null, null, null, null);

        assertThat(view.config().getBaseCommissionRate()).isEqualByComparingTo("0.48");
        assertThat(view.config().getOwnerShortName()).isEqualTo("AK");
        assertThat(view.config().getTierServiceThreshold()).isEqualTo(60);
        assertThat(view.config().getServicePriceCutoff()).isEqualByComparingTo("25.00");
        assertThat(view.config().isTierEnabled()).isTrue();
    }

    @Test
    @DisplayName("Phase 4.4: no-show fee amount starts null on a brand-new business (feature off by "
            + "default), and can be set explicitly without any of the other required-on-creation fields blocking it")
    void noShowFeeAmountDefaultsNullAndIsSettable() {
        when(businesses.findById(2L)).thenReturn(Optional.of(Business.builder().id(2L).build()));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.empty());

        BusinessSettingsService.View created = service.update(2L, "AK PMU", "America/Los_Angeles", "AK",
                new BigDecimal("0.45"), false, null, null, new BigDecimal("0.035"), null, null, null, null, null, null);
        assertThat(created.config().getNoShowFeeAmount()).isNull();

        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.of(created.config()));
        BusinessSettingsService.View updated = service.update(2L, null, null, null,
                null, null, null, null, null, new BigDecimal("20.00"), null, null, null, null, null);
        assertThat(updated.config().getNoShowFeeAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("no such business -> 404")
    void unknownBusinessNotFound() {
        when(businesses.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResponseStatusException.class);
    }
}
