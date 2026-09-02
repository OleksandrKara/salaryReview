package com.salonreview.domain;

import com.salonreview.repo.SeoTechnicalIssueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug found during manual E2E verification (seo-intelligence-advisor
 * Phase 8): {@code seo_technical_issue}'s {@code issue_type} CHECK constraint (V141) was never
 * widened when {@link SeoTechnicalIssue.IssueType#FCP}/{@link SeoTechnicalIssue.IssueType#TBT}
 * were added to the Java enum (Phase 1) — a real poor-FCP/TBT PageSpeed result would have hit a
 * CHECK violation in production. {@link SeoIssueFlaggingServiceTest} never caught this because it
 * mocks the repository; this test needs a real Postgres specifically to exercise the constraint
 * (fails locally without one, passes in CI — same as {@link BusinessRepositoryTest}).
 * {@code @Transactional} so the fixture rows roll back after each test.
 */
@SpringBootTest
@Transactional
class SeoTechnicalIssueRepositoryTest {

    @Autowired
    private SeoTechnicalIssueRepository issues;

    @Test
    void fcpAndTbtIssueTypesCanBePersisted() {
        SeoTechnicalIssue fcp = SeoTechnicalIssue.builder()
                .businessId(1L).issueType(SeoTechnicalIssue.IssueType.FCP).severity(SeoTechnicalIssue.Severity.POOR)
                .detail("First Contentful Paint is 2.0s...").url("https://akluxnails.com/")
                .strategy(SeoPageSnapshot.Strategy.MOBILE).firstSeenAt(Instant.now())
                .build();
        SeoTechnicalIssue tbt = SeoTechnicalIssue.builder()
                .businessId(1L).issueType(SeoTechnicalIssue.IssueType.TBT).severity(SeoTechnicalIssue.Severity.POOR)
                .detail("Total Blocking Time is 300ms...").url("https://akluxnails.com/")
                .strategy(SeoPageSnapshot.Strategy.MOBILE).firstSeenAt(Instant.now())
                .build();

        SeoTechnicalIssue savedFcp = issues.save(fcp);
        SeoTechnicalIssue savedTbt = issues.save(tbt);

        assertThat(savedFcp.getId()).isNotNull();
        assertThat(savedTbt.getId()).isNotNull();
    }
}
