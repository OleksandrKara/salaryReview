package com.salonreview.repo;

import com.salonreview.domain.ProviderAvailabilitySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProviderAvailabilitySnapshotRepository extends JpaRepository<ProviderAvailabilitySnapshot, Long> {

    List<ProviderAvailabilitySnapshot> findByBusinessIdAndTeamMemberId(Long businessId, String teamMemberId);

    @Modifying
    @Transactional
    void deleteByBusinessIdAndTeamMemberId(Long businessId, String teamMemberId);
}
