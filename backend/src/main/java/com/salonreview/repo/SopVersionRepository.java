package com.salonreview.repo;

import com.salonreview.domain.SopVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SopVersionRepository extends JpaRepository<SopVersion, Long> {

    List<SopVersion> findBySopIdOrderByVersionNumberAsc(Long sopId);

    /** Highest existing version for a SOP — the next is this + 1. */
    Optional<SopVersion> findTopBySopIdOrderByVersionNumberDesc(Long sopId);
}
