package com.salonreview.repo;

import com.salonreview.domain.SopAcknowledgment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SopAcknowledgmentRepository extends JpaRepository<SopAcknowledgment, Long> {

    boolean existsBySopVersionIdAndUserId(Long sopVersionId, Long userId);

    Optional<SopAcknowledgment> findBySopVersionIdAndUserId(Long sopVersionId, Long userId);

    /** All acknowledgments for a version — used to build the roster (user_id → acknowledged_at). */
    List<SopAcknowledgment> findBySopVersionId(Long sopVersionId);

    /** Remove a user's acknowledgments — called before deleting the user (the FK has no cascade). */
    void deleteByUserId(Long userId);
}
