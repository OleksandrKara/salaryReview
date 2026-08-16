package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
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
    private static final Long BUSINESS_ID = 1L;

    private SameDayRebookingGroupMembershipRepository repository;
    private SquareClient square;
    private RebookingProperties rebookingProperties;
    private SquareClientProvider squareClientProvider;
    private TwilioSmsConfigRepository twilioConfigs;
    private SameDayRebookingGroupExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(SameDayRebookingGroupMembershipRepository.class);
        square = mock(SquareClient.class);
        squareClientProvider = mock(SquareClientProvider.class);
        twilioConfigs = mock(TwilioSmsConfigRepository.class);
        when(twilioConfigs.findAll()).thenReturn(List.of(TwilioSmsConfig.builder().businessId(BUSINESS_ID).build()));
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        rebookingProperties = new RebookingProperties();
        rebookingProperties.setAutoDiscountGroupId(GROUP_ID);
        scheduler = new SameDayRebookingGroupExpiryScheduler(repository, squareClientProvider, twilioConfigs, rebookingProperties);
    }

    private static SameDayRebookingGroupMembership membership(String customerId, Instant expiresAt) {
        return SameDayRebookingGroupMembership.builder()
                .id(1L)
                .businessId(BUSINESS_ID)
                .squareCustomerId(customerId)
                .expiresAt(expiresAt)
                .build();
    }

    private static SameDayRebookingGroupMembership membership(String customerId, Instant expiresAt, String groupId) {
        return SameDayRebookingGroupMembership.builder()
                .id(1L)
                .businessId(BUSINESS_ID)
                .squareCustomerId(customerId)
                .groupId(groupId)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("expired legacy membership (no groupId of its own) → falls back to the $10 group, removedAt set")
    void removesExpiredMembership() {
        SameDayRebookingGroupMembership m = membership("cust1", Instant.now().minusSeconds(60));
        when(repository.findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(eq(BUSINESS_ID), any())).thenReturn(List.of(m));

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
        when(repository.findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(eq(BUSINESS_ID), any())).thenReturn(List.of(m));

        scheduler.removeExpiredMemberships();

        verify(square).removeCustomerFromGroup("cust1", winbackGroupId);
        verify(square, never()).removeCustomerFromGroup("cust1", GROUP_ID);
        assertThat(m.getRemovedAt()).isNotNull();
    }

    @Test
    @DisplayName("Square failure → row left unremoved, retried next tick")
    void squareFailureLeavesRowUnremoved() {
        SameDayRebookingGroupMembership m = membership("cust1", Instant.now().minusSeconds(60));
        when(repository.findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(eq(BUSINESS_ID), any())).thenReturn(List.of(m));
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
        when(repository.findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(eq(BUSINESS_ID), any())).thenReturn(List.of(m));

        scheduler.removeExpiredMemberships();

        verifyNoInteractions(square);
        verify(repository, never()).save(any());
        assertThat(m.getRemovedAt()).isNull();
    }

    @Test
    @DisplayName("one business's SquareClientProvider failure doesn't stop another business's expiry sweep (tasks.md 3.7)")
    void oneBusinessSquareFailureDoesNotBlockAnother() {
        Long otherBusinessId = 2L;
        SquareClient otherSquare = mock(SquareClient.class);
        when(twilioConfigs.findAll()).thenReturn(List.of(
                TwilioSmsConfig.builder().businessId(BUSINESS_ID).build(),
                TwilioSmsConfig.builder().businessId(otherBusinessId).build()));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenThrow(new RuntimeException("business A Square down"));
        when(squareClientProvider.forBusiness(otherBusinessId)).thenReturn(otherSquare);

        SameDayRebookingGroupMembership otherMembership = SameDayRebookingGroupMembership.builder()
                .id(2L).businessId(otherBusinessId).squareCustomerId("cust2").expiresAt(Instant.now().minusSeconds(60)).build();
        when(repository.findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(eq(otherBusinessId), any()))
                .thenReturn(List.of(otherMembership));

        scheduler.removeExpiredMemberships();

        verify(repository, never()).findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(eq(BUSINESS_ID), any());
        verify(otherSquare).removeCustomerFromGroup("cust2", GROUP_ID);
        assertThat(otherMembership.getRemovedAt()).isNotNull();
    }
}
