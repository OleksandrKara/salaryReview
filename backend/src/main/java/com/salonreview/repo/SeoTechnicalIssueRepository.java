package com.salonreview.repo;

import com.salonreview.domain.SeoTechnicalIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoTechnicalIssueRepository extends JpaRepository<SeoTechnicalIssue, Long> {
    List<SeoTechnicalIssue> findByBusinessIdAndResolvedAtIsNull(Long businessId);

    Optional<SeoTechnicalIssue> findFirstByBusinessIdAndIssueTypeAndResolvedAtIsNullOrderByFirstSeenAtDesc(
            Long businessId, SeoTechnicalIssue.IssueType issueType);
}
