package com.salonreview.repo;

import com.salonreview.domain.BusinessMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessMembershipRepository extends JpaRepository<BusinessMembership, Long> {
    List<BusinessMembership> findByUserId(Long userId);
}
