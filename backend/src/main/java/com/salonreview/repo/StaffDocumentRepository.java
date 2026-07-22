package com.salonreview.repo;

import com.salonreview.domain.StaffDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, Long> {

    /** Soonest-expiring first, across every provider/manager — the admin list's default order so
     * whatever needs attention first is already at the top. */
    List<StaffDocument> findAllByOrderByExpirationDateAsc();
}
