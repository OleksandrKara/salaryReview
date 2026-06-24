package com.salonreview.repo;

import com.salonreview.domain.RagRedactionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagRedactionAuditRepository extends JpaRepository<RagRedactionAudit, Long> {
}
