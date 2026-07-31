package com.salonreview.repo;

import com.salonreview.domain.SameDayRebookingGroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SameDayRebookingGroupMembershipRepository extends JpaRepository<SameDayRebookingGroupMembership, Long> {

    List<SameDayRebookingGroupMembership> findByRemovedAtIsNullAndExpiresAtBefore(Instant now);
}
