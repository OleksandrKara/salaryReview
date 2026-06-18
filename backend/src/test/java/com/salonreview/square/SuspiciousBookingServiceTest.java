package com.salonreview.square;

import com.salonreview.domain.SuspiciousBookingClearance;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.repo.SuspiciousBookingClearanceRepository;
import com.salonreview.service.ProviderDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Clearance round-trip: clear inserts a row; clearing an already-cleared booking is a no-op (no
 * duplicate insert); unclear removes the row. List/summary behavior tested via the detection test +
 * integration (DB-backed) — service-layer round-trip just covers the persistence wrapper.
 */
class SuspiciousBookingServiceTest {

    private SuspiciousBookingClearanceRepository repo;
    private SuspiciousBookingService service;

    @BeforeEach
    void setUp() {
        repo = mock(SuspiciousBookingClearanceRepository.class);
        service = new SuspiciousBookingService(
                mock(SquareMonthAggregator.class),
                mock(SquareClient.class),
                mock(SalonConfigRepository.class),
                mock(ProviderDirectory.class),
                repo,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.salonreview.repo.SuspiciousTriageRepository.class));
    }

    @Test
    @DisplayName("clear() inserts a clearance row")
    void clearInsertsRow() {
        when(repo.findBySquareBookingId("bk1")).thenReturn(Optional.empty());

        service.clear("bk1", "olexandr.kara2", "looked at it; client paid via Zelle later");

        ArgumentCaptor<SuspiciousBookingClearance> cap = ArgumentCaptor.forClass(SuspiciousBookingClearance.class);
        verify(repo).save(cap.capture());
        SuspiciousBookingClearance saved = cap.getValue();
        assertThat(saved.getSquareBookingId()).isEqualTo("bk1");
        assertThat(saved.getClearedByUsername()).isEqualTo("olexandr.kara2");
        assertThat(saved.getNote()).isEqualTo("looked at it; client paid via Zelle later");
        assertThat(saved.getClearedAt()).isNotNull();
    }

    @Test
    @DisplayName("clear() is idempotent — already-cleared booking does NOT insert again")
    void clearIsIdempotent() {
        SuspiciousBookingClearance existing = SuspiciousBookingClearance.builder()
                .id(1L).squareBookingId("bk1").clearedByUsername("someone")
                .clearedAt(java.time.Instant.now()).build();
        when(repo.findBySquareBookingId("bk1")).thenReturn(Optional.of(existing));

        service.clear("bk1", "olexandr.kara2", "second click");

        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("unclear() removes the clearance row")
    void unclearRemovesRow() {
        service.unclear("bk1");
        verify(repo).deleteBySquareBookingId("bk1");
    }
}
