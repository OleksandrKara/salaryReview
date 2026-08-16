package com.salonreview.repo;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KbArticleRepository extends JpaRepository<KbArticle, Long> {

    /** All articles for the management/list view, grouped sensibly, for one business. */
    List<KbArticle> findAllByBusinessIdOrderByCategoryAscTitleAsc(Long businessId);

    /**
     * Articles that need (re-)sync in one business: any non-SYNCED status, plus SYNCED articles
     * whose rag_doc_id is null — those were marked SYNCED by older code that predated RAG ingestion.
     */
    @Query("SELECT a FROM KbArticle a WHERE a.businessId = :businessId AND (a.syncStatus IN :statuses OR (a.syncStatus = com.salonreview.domain.SyncStatus.SYNCED AND a.ragDocId IS NULL)) ORDER BY a.category ASC, a.title ASC")
    List<KbArticle> findPendingSyncByBusinessIdOrderByCategoryAscTitleAsc(
            @Param("businessId") Long businessId, @Param("statuses") Collection<SyncStatus> statuses);

    /** Single-article lookup scoped to a business — every read/write goes through this (or the list
     * method above) so an article id from another business's table 404s instead of being
     * visible/mutable cross-tenant. */
    Optional<KbArticle> findByIdAndBusinessId(Long id, Long businessId);
}
