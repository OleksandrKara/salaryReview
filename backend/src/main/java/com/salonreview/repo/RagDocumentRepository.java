package com.salonreview.repo;

import com.salonreview.domain.RagDocument;
import com.salonreview.domain.RagDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /** Admin list — newest first, for one business. */
    List<RagDocument> findAllByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /** Indexed documents — the answerable corpus, used to ground starter-prompt suggestions. */
    List<RagDocument> findByBusinessIdAndStatusOrderByCreatedAtDesc(Long businessId, RagDocumentStatus status);

    /** Single-document lookup scoped to a business — the owner-side approve/delete ownership check. */
    Optional<RagDocument> findByIdAndBusinessId(Long id, Long businessId);
}
