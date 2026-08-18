package com.salonreview.repo;

import com.salonreview.domain.BusinessMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface BusinessMembershipRepository extends JpaRepository<BusinessMembership, Long> {
    List<BusinessMembership> findByUserId(Long userId);

    boolean existsByUserIdAndBusinessId(Long userId, Long businessId);

    @Transactional
    void deleteByUserId(Long userId);
}
