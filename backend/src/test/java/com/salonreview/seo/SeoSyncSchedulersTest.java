package com.salonreview.seo;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SeoConnection;
import com.salonreview.repo.SeoConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cross-tenant isolation for the two scheduled sync jobs (proposal.md "How This Is Verified" /
 * tasks.md 5.4) — unit-level rather than a live-DB integration test, since the behavior under test
 * (skip-if-disabled, one business's failure never blocking another's) lives entirely in the loop
 * logic, not in Spring/ShedLock/JPA wiring that a real {@code CurrentBusinessContext}/repository
 * already have their own tests for.
 */
class SeoSyncSchedulersTest {

    private static SeoConnection connectionFor(Long businessId) {
        return SeoConnection.builder().businessId(businessId).build();
    }

    @Test
    void searchConsoleScheduler_skipsBusinessesWithFeatureDisabled() {
        SeoConnectionRepository connections = mock(SeoConnectionRepository.class);
        BusinessFeatureService features = mock(BusinessFeatureService.class);
        CurrentBusinessContext context = realRunAsContext();
        SeoSyncService syncService = mock(SeoSyncService.class);

        when(connections.findAll()).thenReturn(List.of(connectionFor(1L), connectionFor(2L)));
        when(features.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(false);
        when(features.isEnabled(2L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

        new SeoSearchConsoleSyncScheduler(connections, features, context, syncService).sync();

        verify(syncService, never()).syncSearchConsole(1L);
        verify(syncService).syncSearchConsole(2L);
    }

    @Test
    void searchConsoleScheduler_oneBusinessFailureDoesNotBlockAnother() {
        SeoConnectionRepository connections = mock(SeoConnectionRepository.class);
        BusinessFeatureService features = mock(BusinessFeatureService.class);
        CurrentBusinessContext context = realRunAsContext();
        SeoSyncService syncService = mock(SeoSyncService.class);

        when(connections.findAll()).thenReturn(List.of(connectionFor(1L), connectionFor(2L)));
        when(features.isEnabled(any(), eq(BusinessFeatureService.SEO_MONITORING_ENABLED))).thenReturn(true);
        doThrow(new RuntimeException("Business 1's credentials were revoked")).when(syncService).syncSearchConsole(1L);

        new SeoSearchConsoleSyncScheduler(connections, features, context, syncService).sync();

        verify(syncService).syncSearchConsole(1L);
        verify(syncService).syncSearchConsole(2L);
    }

    @Test
    void searchConsoleScheduler_alsoRunsAnalyticsSyncPerBusiness() {
        SeoConnectionRepository connections = mock(SeoConnectionRepository.class);
        BusinessFeatureService features = mock(BusinessFeatureService.class);
        CurrentBusinessContext context = realRunAsContext();
        SeoSyncService syncService = mock(SeoSyncService.class);

        when(connections.findAll()).thenReturn(List.of(connectionFor(1L), connectionFor(2L)));
        when(features.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(false);
        when(features.isEnabled(2L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

        new SeoSearchConsoleSyncScheduler(connections, features, context, syncService).sync();

        verify(syncService, never()).syncAnalytics(1L);
        verify(syncService).syncAnalytics(2L);
    }

    @Test
    void searchConsoleScheduler_analyticsFailureDoesNotBlockSearchConsoleOrAnotherBusiness() {
        SeoConnectionRepository connections = mock(SeoConnectionRepository.class);
        BusinessFeatureService features = mock(BusinessFeatureService.class);
        CurrentBusinessContext context = realRunAsContext();
        SeoSyncService syncService = mock(SeoSyncService.class);

        when(connections.findAll()).thenReturn(List.of(connectionFor(1L), connectionFor(2L)));
        when(features.isEnabled(any(), eq(BusinessFeatureService.SEO_MONITORING_ENABLED))).thenReturn(true);
        doThrow(new RuntimeException("GA4 property not accessible")).when(syncService).syncAnalytics(1L);

        new SeoSearchConsoleSyncScheduler(connections, features, context, syncService).sync();

        verify(syncService).syncSearchConsole(1L);
        verify(syncService).syncSearchConsole(2L);
        verify(syncService).syncAnalytics(1L);
        verify(syncService).syncAnalytics(2L);
    }

    @Test
    void pageSpeedScheduler_skipsBusinessesWithFeatureDisabled() {
        SeoConnectionRepository connections = mock(SeoConnectionRepository.class);
        BusinessFeatureService features = mock(BusinessFeatureService.class);
        CurrentBusinessContext context = realRunAsContext();
        SeoSyncService syncService = mock(SeoSyncService.class);

        when(connections.findAll()).thenReturn(List.of(connectionFor(1L), connectionFor(2L)));
        when(features.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);
        when(features.isEnabled(2L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(false);

        new SeoPageSpeedSyncScheduler(connections, features, context, syncService).sync();

        verify(syncService).syncPageSpeed(1L);
        verify(syncService, never()).syncPageSpeed(2L);
    }

    @Test
    void pageSpeedScheduler_oneBusinessFailureDoesNotBlockAnother() {
        SeoConnectionRepository connections = mock(SeoConnectionRepository.class);
        BusinessFeatureService features = mock(BusinessFeatureService.class);
        CurrentBusinessContext context = realRunAsContext();
        SeoSyncService syncService = mock(SeoSyncService.class);

        when(connections.findAll()).thenReturn(List.of(connectionFor(1L), connectionFor(2L)));
        when(features.isEnabled(any(), eq(BusinessFeatureService.SEO_MONITORING_ENABLED))).thenReturn(true);
        doThrow(new RuntimeException("PageSpeed quota exceeded")).when(syncService).syncPageSpeed(1L);

        new SeoPageSpeedSyncScheduler(connections, features, context, syncService).sync();

        verify(syncService).syncPageSpeed(1L);
        verify(syncService).syncPageSpeed(2L);
    }

    private static CurrentBusinessContext realRunAsContext() {
        // The real class, not a mock — its runAs() is trivial ThreadLocal plumbing, and using the
        // real implementation confirms the scheduler actually threads businessId through it
        // (rather than a mock silently accepting any lambda without running it).
        return new CurrentBusinessContext();
    }
}
