package com.salonreview.repo;

import com.salonreview.domain.SameDayRebookingGroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SameDayRebookingGroupMembershipRepository extends JpaRepository<SameDayRebookingGroupMembership, Long> {

    List<SameDayRebookingGroupMembership> findByBusinessIdAndRemovedAtIsNullAndExpiresAtBefore(Long businessId, Instant now);

    /** The still-active membership a consultation follow-up offer created, so the scheduler can
     * extend it to the booked visit or end it when the offer window closes. */
    java.util.Optional<SameDayRebookingGroupMembership> findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(
            Long businessId, String squareCustomerId, String groupId);
}
