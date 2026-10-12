package com.salonreview.aivisibility;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AiVisibilitySettings;
import com.salonreview.repo.AiVisibilitySettingsRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Weekly AI-visibility run (design.md D3): Monday 06:00 PT, one replica (ShedLock), every business
 * with {@code ai-visibility.enabled} and a settings row; a failure in one business never stops the
 * next. The digest goes out right after each business's run; on the first Monday of a month the
 * page comparison is refreshed first and the digest adds the top page gaps. */
@Component
public class AiVisibilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiVisibilityScheduler.class);

    private final AiVisibilitySettingsRepository settings;
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;
    private final AiVisibilityRunService runService;
    private final AiVisibilityDigestService digest;
    private final AiVisibilityPageService pages;

    public AiVisibilityScheduler(AiVisibilitySettingsRepository settings, BusinessFeatureService businessFeatures,
            CurrentBusinessContext currentBusinessContext, AiVisibilityRunService runService, AiVisibilityDigestService digest,
            AiVisibilityPageService pages) {
        this.settings = settings;
        this.businessFeatures = businessFeatures;
        this.currentBusinessContext = currentBusinessContext;
        this.runService = runService;
        this.digest = digest;
        this.pages = pages;
    }

    @Scheduled(cron = "0 0 6 * * MON", zone = "America/Los_Angeles")
    @SchedulerLock(name = "AiVisibilityScheduler_run", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void run() {
        for (AiVisibilitySettings s : settings.findAll()) {
            Long businessId = s.getBusinessId();
            if (!businessFeatures.isEnabled(businessId, BusinessFeatureService.AI_VISIBILITY_ENABLED)) continue;
            try {
                boolean firstMondayOfMonth = java.time.LocalDate.now(AiVisibilityRunService.PT).getDayOfMonth() <= 7;
                currentBusinessContext.runAs(businessId, () -> runService.runScheduled(businessId).ifPresent(run -> {
                    // design.md D10: monthly page refresh right after the checks, so the digest can
                    // report this month's page gaps.
                    if (firstMondayOfMonth) {
                        try {
                            pages.refreshAll(businessId);
                        } catch (RuntimeException e) {
                            log.warn("Monthly page refresh failed for business {}: {}", businessId, e.toString());
                        }
                    }
                    digest.sendFor(run, firstMondayOfMonth);
                }));
            } catch (RuntimeException e) {
                log.warn("AI visibility run failed for business {}: {}", businessId, e.toString());
            }
        }
    }
}
