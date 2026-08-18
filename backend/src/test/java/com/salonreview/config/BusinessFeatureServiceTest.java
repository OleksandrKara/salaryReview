package com.salonreview.config;

import com.salonreview.domain.BusinessFeature;
import com.salonreview.repo.BusinessFeatureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 4.3 (multi-tenant-salon-platform). */
class BusinessFeatureServiceTest {

    private BusinessFeatureRepository repository;
    private BusinessFeatureService service;

    @BeforeEach
    void setUp() {
        repository = mock(BusinessFeatureRepository.class);
        service = new BusinessFeatureService(repository);
    }

    @Test
    @DisplayName("an explicit enabled=true row is honored")
    void enabledRowReturnsTrue() {
        when(repository.findByBusinessIdAndFeatureKey(1L, BusinessFeatureService.RAG_ENABLED))
                .thenReturn(Optional.of(BusinessFeature.builder().businessId(1L)
                        .featureKey(BusinessFeatureService.RAG_ENABLED).enabled(true).build()));

        assertThat(service.isEnabled(1L, BusinessFeatureService.RAG_ENABLED)).isTrue();
    }

    @Test
    @DisplayName("an explicit enabled=false row returns false")
    void disabledRowReturnsFalse() {
        when(repository.findByBusinessIdAndFeatureKey(2L, BusinessFeatureService.RAG_ENABLED))
                .thenReturn(Optional.of(BusinessFeature.builder().businessId(2L)
                        .featureKey(BusinessFeatureService.RAG_ENABLED).enabled(false).build()));

        assertThat(service.isEnabled(2L, BusinessFeatureService.RAG_ENABLED)).isFalse();
    }

    @Test
    @DisplayName("a missing row defaults to disabled — ships dark per business, same as the "
            + "deployment-level flags themselves")
    void missingRowReturnsFalse() {
        when(repository.findByBusinessIdAndFeatureKey(2L, BusinessFeatureService.AI_TRIAGE_ENABLED))
                .thenReturn(Optional.empty());

        assertThat(service.isEnabled(2L, BusinessFeatureService.AI_TRIAGE_ENABLED)).isFalse();
    }
}
