package com.salonreview.repo;

import com.salonreview.domain.RagDocument;
import com.salonreview.domain.RagDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /** Admin list — newest first. */
    List<RagDocument> findAllByOrderByCreatedAtDesc();

    /** Indexed documents — the answerable corpus, used to ground starter-prompt suggestions. */
    List<RagDocument> findByStatusOrderByCreatedAtDesc(RagDocumentStatus status);
}
