package com.salonreview.repo;

import com.salonreview.domain.LeadFollowUpSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface LeadFollowUpSendRepository extends JpaRepository<LeadFollowUpSend, Long> {

    /** Belt-and-suspenders alongside the poll query's own {@code NOT EXISTS} — see
     * LeadFollowUpScheduler. True only if this exact touch (or a later one) has already been
     * processed — a contact whose updated_at has since moved past what's on file here is a new,
     * still-eligible touch, not a duplicate. */
    boolean existsByContactIdAndContactUpdatedAtGreaterThanEqual(UUID contactId, Instant contactUpdatedAt);
}
