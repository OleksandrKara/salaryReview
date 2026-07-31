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

    @Test
    @DisplayName("expired membership → removed from Square group, removedAt set")
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
    @DisplayName("auto-discount group not yet configured → no-ops entirely, no Square calls")
    void noOpsWhenNotConfigured() {
        rebookingProperties.setAutoDiscountGroupId("");

        scheduler.removeExpiredMemberships();

        verifyNoInteractions(square, repository);
    }
}
