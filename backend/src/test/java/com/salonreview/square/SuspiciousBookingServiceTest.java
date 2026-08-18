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

    private static final Long BUSINESS_ID = 1L;

    private SuspiciousBookingClearanceRepository repo;
    private SuspiciousBookingService service;

    @BeforeEach
    void setUp() {
        repo = mock(SuspiciousBookingClearanceRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        service = new SuspiciousBookingService(
                mock(SquareMonthAggregator.class),
                mock(SquareClientProvider.class),
                mock(SalonConfigRepository.class),
                mock(ProviderDirectory.class),
                repo,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(com.salonreview.repo.SuspiciousTriageRepository.class),
                currentBusinessContext);
    }

    @Test
    @DisplayName("clear() inserts a clearance row, scoped to the current business")
    void clearInsertsRow() {
        when(repo.findByBusinessIdAndSquareBookingId(BUSINESS_ID, "bk1")).thenReturn(Optional.empty());

        service.clear("bk1", "olexandr.kara2", "looked at it; client paid via Zelle later");

        ArgumentCaptor<SuspiciousBookingClearance> cap = ArgumentCaptor.forClass(SuspiciousBookingClearance.class);
        verify(repo).save(cap.capture());
        SuspiciousBookingClearance saved = cap.getValue();
        assertThat(saved.getBusinessId()).isEqualTo(BUSINESS_ID);
        assertThat(saved.getSquareBookingId()).isEqualTo("bk1");
        assertThat(saved.getClearedByUsername()).isEqualTo("olexandr.kara2");
        assertThat(saved.getNote()).isEqualTo("looked at it; client paid via Zelle later");
        assertThat(saved.getClearedAt()).isNotNull();
    }

    @Test
    @DisplayName("clear() is idempotent — already-cleared booking does NOT insert again")
    void clearIsIdempotent() {
        SuspiciousBookingClearance existing = SuspiciousBookingClearance.builder()
                .id(1L).businessId(BUSINESS_ID).squareBookingId("bk1").clearedByUsername("someone")
                .clearedAt(java.time.Instant.now()).build();
        when(repo.findByBusinessIdAndSquareBookingId(BUSINESS_ID, "bk1")).thenReturn(Optional.of(existing));

        service.clear("bk1", "olexandr.kara2", "second click");

        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: clear() checks only THIS business's row, even when "
            + "another business happens to have already cleared the same bookingId string")
    void clearDoesNotSeeAnotherBusinessClearance() {
        // Business 2's clearance for the same bookingId exists, but the mock only answers the
        // business-1-scoped lookup below — a bare findBySquareBookingId (the pre-fix behavior)
        // would have returned business 2's row here and skipped the insert entirely.
        when(repo.findByBusinessIdAndSquareBookingId(BUSINESS_ID, "bk1")).thenReturn(Optional.empty());

        service.clear("bk1", "olexandr.kara2", "business 1's own review");

        verify(repo).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("unclear() removes the clearance row, scoped to the current business")
    void unclearRemovesRow() {
        service.unclear("bk1");
        verify(repo).deleteByBusinessIdAndSquareBookingId(BUSINESS_ID, "bk1");
    }
}
