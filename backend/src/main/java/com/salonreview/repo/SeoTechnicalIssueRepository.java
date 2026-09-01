package com.salonreview.repo;

import com.salonreview.domain.SeoTechnicalIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeoTechnicalIssueRepository extends JpaRepository<SeoTechnicalIssue, Long> {
    List<SeoTechnicalIssue> findByBusinessIdAndResolvedAtIsNull(Long businessId);

    Optional<SeoTechnicalIssue> findFirstByBusinessIdAndIssueTypeAndResolvedAtIsNullOrderByFirstSeenAtDesc(
            Long businessId, SeoTechnicalIssue.IssueType issueType);

    /** {@code url}/{@code query} are matched null-safely since either may legitimately be null
     * depending on {@code issueType} (see {@link SeoTechnicalIssue#getUrl()}). */
    @Query("SELECT i FROM SeoTechnicalIssue i WHERE i.businessId = :businessId "
            + "AND i.issueType = :issueType "
            + "AND ((:url IS NULL AND i.url IS NULL) OR i.url = :url) "
            + "AND ((:query IS NULL AND i.query IS NULL) OR i.query = :query) "
            + "AND i.resolvedAt IS NULL")
    Optional<SeoTechnicalIssue> findOpenBySubject(@Param("businessId") Long businessId,
            @Param("issueType") SeoTechnicalIssue.IssueType issueType,
            @Param("url") String url, @Param("query") String query);
}
