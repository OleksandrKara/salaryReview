package com.salonreview.repo;

import com.salonreview.domain.KbRequest;
import com.salonreview.domain.KbRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbRequestRepository extends JpaRepository<KbRequest, Long> {

    /** Owner list — newest first. */
    List<KbRequest> findAllByOrderByCreatedAtDesc();

    /** Count for the open-requests badge. */
    long countByStatus(KbRequestStatus status);
}
