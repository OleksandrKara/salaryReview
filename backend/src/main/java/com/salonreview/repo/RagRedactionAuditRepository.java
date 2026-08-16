package com.salonreview.repo;

import com.salonreview.domain.RagRedactionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagRedactionAuditRepository extends JpaRepository<RagRedactionAudit, Long> {

    /** Audit trail for one business, newest first. */
    List<RagRedactionAudit> findAllByBusinessIdOrderByDeletedAtDesc(Long businessId);
}
