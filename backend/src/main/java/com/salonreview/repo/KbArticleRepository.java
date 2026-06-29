package com.salonreview.repo;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface KbArticleRepository extends JpaRepository<KbArticle, Long> {

    /** All articles for the management/list view, grouped sensibly. */
    List<KbArticle> findAllByOrderByCategoryAscTitleAsc();

    /** Articles eligible for bulk sync (everything not currently SYNCED). */
    List<KbArticle> findBySyncStatusInOrderByCategoryAscTitleAsc(Collection<SyncStatus> statuses);

    /**
     * Articles that need (re-)sync: any non-SYNCED status, plus SYNCED articles whose rag_doc_id
     * is null — those were marked SYNCED by older code that predated RAG ingestion.
     */
    @Query("SELECT a FROM KbArticle a WHERE a.syncStatus IN :statuses OR (a.syncStatus = com.salonreview.domain.SyncStatus.SYNCED AND a.ragDocId IS NULL) ORDER BY a.category ASC, a.title ASC")
    List<KbArticle> findPendingSyncOrderByCategoryAscTitleAsc(Collection<SyncStatus> statuses);
}
