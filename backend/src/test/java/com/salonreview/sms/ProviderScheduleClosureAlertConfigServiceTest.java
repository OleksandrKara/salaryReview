package com.salonreview.sms;

import com.salonreview.domain.ProviderScheduleClosureAlertConfig;
import com.salonreview.repo.ProviderScheduleClosureAlertConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Owner-editable notice-threshold for {@code provider_schedule_closure_alert} — see
 * {@link ProviderScheduleClosureAlertScheduler}, which reads {@link
 * ProviderScheduleClosureAlertConfigService#getNoticeThresholdHours} on every poll. */
class ProviderScheduleClosureAlertConfigServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private ProviderScheduleClosureAlertConfigRepository repository;
    private ProviderScheduleClosureAlertConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProviderScheduleClosureAlertConfigRepository.class);
        service = new ProviderScheduleClosureAlertConfigService(repository);
    }

    @Test
    @DisplayName("no row yet → the 24h default, not an exception or zero")
    void defaultsTo24HoursWhenNoRowExists() {
        when(repository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());

        assertThat(service.getNoticeThresholdHours(BUSINESS_ID)).isEqualTo(24);
    }

    @Test
    @DisplayName("an existing row's value is returned as-is")
    void returnsConfiguredValue() {
        when(repository.findByBusinessId(BUSINESS_ID)).thenReturn(
                Optional.of(ProviderScheduleClosureAlertConfig.builder().businessId(BUSINESS_ID).noticeThresholdHours(6).build()));

        assertThat(service.getNoticeThresholdHours(BUSINESS_ID)).isEqualTo(6);
    }

    @Test
    @DisplayName("getSettings surfaces null updatedAt/updatedBy when the business hasn't saved this setting yet")
    void getSettingsReflectsUnconfiguredState() {
        when(repository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());

        var settings = service.getSettings(BUSINESS_ID);

        assertThat(settings.noticeThresholdHours()).isEqualTo(24);
        assertThat(settings.updatedAt()).isNull();
        assertThat(settings.updatedBy()).isNull();
    }

    @Test
    @DisplayName("update() creates a new row for a business with none yet")
    void updateCreatesRowWhenMissing() {
        when(repository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderScheduleClosureAlertConfig saved = service.update(BUSINESS_ID, 12, "owner@test");

        assertThat(saved.getBusinessId()).isEqualTo(BUSINESS_ID);
        assertThat(saved.getNoticeThresholdHours()).isEqualTo(12);
        assertThat(saved.getUpdatedBy()).isEqualTo("owner@test");
    }

    @Test
    @DisplayName("update() overwrites the existing row in place rather than creating a second one")
    void updateOverwritesExistingRow() {
        ProviderScheduleClosureAlertConfig existing = ProviderScheduleClosureAlertConfig.builder()
                .id(99L).businessId(BUSINESS_ID).noticeThresholdHours(24).build();
        when(repository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderScheduleClosureAlertConfig saved = service.update(BUSINESS_ID, 48, "owner@test");

        assertThat(saved.getId()).isEqualTo(99L);
        assertThat(saved.getNoticeThresholdHours()).isEqualTo(48);
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("update() rejects zero/negative hours")
    void updateRejectsNonPositiveHours() {
        assertThatThrownBy(() -> service.update(BUSINESS_ID, 0, "owner@test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(BUSINESS_ID, -5, "owner@test"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("update() rejects a value beyond the 168h (one week) ceiling")
    void updateRejectsTooLargeValue() {
        assertThatThrownBy(() -> service.update(BUSINESS_ID, 169, "owner@test"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("update() accepts the 168h ceiling itself")
    void updateAcceptsCeilingValue() {
        when(repository.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderScheduleClosureAlertConfig saved = service.update(BUSINESS_ID, 168, "owner@test");

        assertThat(saved.getNoticeThresholdHours()).isEqualTo(168);
    }
}
