package com.salonreview.repo;

import com.salonreview.domain.LeadFollowUpSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LeadFollowUpSendRepository extends JpaRepository<LeadFollowUpSend, Long> {

    /** Belt-and-suspenders alongside the poll query's own {@code NOT EXISTS} — see
     * LeadFollowUpScheduler. */
    boolean existsByContactId(UUID contactId);
}
