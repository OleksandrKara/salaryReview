package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** See openspec/changes/same-day-rebooking-discount design.md D7. */
class SameDayRebookingGroupExpirySchedulerTest {

    private static final String GROUP_ID = "grp1";

    private SameDayRebookingGroupMembershipRepository repository;
    private SquareClient square;
    private RebookingProperties rebookingProperties;
    private SameDayRebookingGroupExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(SameDayRebookingGroupMembershipRepository.class);
        square = mock(SquareClient.class);
        rebookingProperties = new RebookingProperties();
        rebookingProperties.setAutoDiscountGroupId(GROUP_ID);
        scheduler = new SameDayRebookingGroupExpiryScheduler(repository, square, rebookingProperties);
    }

    private static SameDayRebookingGroupMembership membership(String customerId, Instant expiresAt) {
        return SameDayRebookingGroupMembership.builder()
                .id(1L)
                .squareCustomerId(customerId)
                .expiresAt(expiresAt)
                .build();
    }

    private static SameDayRebookingGroupMembership membership(String customerId, Instant expiresAt, String groupId) {
        return SameDayRebookingGroupMembership.builder()
                .id(1L)
                .squareCustomerId(customerId)
                .groupId(groupId)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("expired legacy membership (no groupId of its own) → falls back to the $10 group, removedAt set")
    void removesExpiredMembership() {
        SameDayRebookingGroupMembership m = membership("cust1", Instant.now().minusSeconds(60));
        when(repository.findByRemovedAtIsNullAndExpiresAtBefore(any())).thenReturn(List.of(m));

        scheduler.removeExpiredMemberships();

        verify(square).removeCustomerFromGroup("cust1", GROUP_ID);
        ArgumentCaptor<SameDayRebookingGroupMembership> captor = ArgumentCaptor.forClass(SameDayRebookingGroupMembership.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRemovedAt()).isNotNull();
    }

    @Test
    @DisplayName("expired membership with its own groupId (e.g. the $5 winback group) → removed from THAT group, not the $10 default")
    void removesFromOwnGroupWhenSet() {
        String winbackGroupId = "grp-winback5";
        SameDayRebookingGroupMembership m = membership("cust1", Instant.now().minusSeconds(60), winbackGroupId);
        when(repository.findByRemovedAtIsNullAndExpiresAtBefore(any())).thenReturn(List.of(m));

        scheduler.removeExpiredMemberships();

        verify(square).removeCustomerFromGroup("cust1", winbackGroupId);
        verify(square, never()).removeCustomerFromGroup("cust1", GROUP_ID);
        assertThat(m.getRemovedAt()).isNotNull();
    }

    @Test
    @DisplayName("Square failure → row left unremoved, retried next tick")
    void squareFailureLeavesRowUnremoved() {
        SameDayRebookingGroupMembership m = membership("cust1", Instant.now().minusSeconds(60));
        when(repository.findByRemovedAtIsNullAndExpiresAtBefore(any())).thenReturn(List.of(m));
        doThrow(new RuntimeException("Square down")).when(square).removeCustomerFromGroup(eq("cust1"), eq(GROUP_ID));

        scheduler.removeExpiredMemberships();

        verify(repository, never()).save(any());
        assertThat(m.getRemovedAt()).isNull();
    }

    @Test
    @DisplayName("legacy row with no groupId, $10 default not yet configured either → skipped, left for a future run, no Square call")
    void skipsRowWithNoResolvableGroupId() {
        rebookingProperties.setAutoDiscountGroupId("");
        SameDayRebookingGroupMembership m = membership("cust1", Instant.now().minusSeconds(60));
        when(repository.findByRemovedAtIsNullAndExpiresAtBefore(any())).thenReturn(List.of(m));

        scheduler.removeExpiredMemberships();

        verifyNoInteractions(square);
        verify(repository, never()).save(any());
        assertThat(m.getRemovedAt()).isNull();
    }
}
