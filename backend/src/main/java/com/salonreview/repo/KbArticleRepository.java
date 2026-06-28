package com.salonreview.repo;

import com.salonreview.domain.KbArticle;
import com.salonreview.domain.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KbArticleRepository extends JpaRepository<KbArticle, Long> {

    /** All articles for the management/list view, grouped sensibly. */
    List<KbArticle> findAllByOrderByCategoryAscTitleAsc();

    /** Articles eligible for bulk sync (everything not currently SYNCED). */
    List<KbArticle> findBySyncStatusInOrderByCategoryAscTitleAsc(Collection<SyncStatus> statuses);
}
