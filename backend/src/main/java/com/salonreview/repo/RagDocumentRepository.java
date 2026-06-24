package com.salonreview.repo;

import com.salonreview.domain.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /** Admin list — newest first. */
    List<RagDocument> findAllByOrderByCreatedAtDesc();
}
