package com.salonreview.repo;

import com.salonreview.domain.SeoPageSnapshot;
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

    /** {@code url}/{@code query}/{@code strategy} are matched null-safely since each may
     * legitimately be null depending on {@code issueType} (see {@link SeoTechnicalIssue#getUrl()}/
     * {@link SeoTechnicalIssue#getStrategy()}) — {@code strategy} in particular must be part of the
     * key, not just {@code url}, since mobile/desktop share the same URL but can have opposite
     * pass/fail states for the same metric (found live 2026-09-01 — see V144's own comment). */
    @Query("SELECT i FROM SeoTechnicalIssue i WHERE i.businessId = :businessId "
            + "AND i.issueType = :issueType "
            + "AND ((:url IS NULL AND i.url IS NULL) OR i.url = :url) "
            + "AND ((:query IS NULL AND i.query IS NULL) OR i.query = :query) "
            + "AND ((:strategy IS NULL AND i.strategy IS NULL) OR i.strategy = :strategy) "
            + "AND i.resolvedAt IS NULL")
    Optional<SeoTechnicalIssue> findOpenBySubject(@Param("businessId") Long businessId,
            @Param("issueType") SeoTechnicalIssue.IssueType issueType,
            @Param("url") String url, @Param("query") String query,
            @Param("strategy") SeoPageSnapshot.Strategy strategy);
}
