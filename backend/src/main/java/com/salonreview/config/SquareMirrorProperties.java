package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 2i cutover switch for {@code SquareMonthAggregator#aggregate()}: bound from
 * {@code square.mirror.*} (see application.yml). {@link #aggregateEnabled} defaults to {@code true}
 * — the local Square mirror, not live Square, is the default raw-data source for payroll now that
 * Milestone 2g's shadow-diff came back clean across both businesses' full backfilled history. Kept
 * as a live, env-var-overridable flag (not just a one-time code change) so a burn-in-period surprise
 * can revert every caller to the live path with a restart, no code revert or redeploy of a fix.
 */
@Component
@ConfigurationProperties(prefix = "square.mirror")
@Getter
@Setter
public class SquareMirrorProperties {

    private boolean aggregateEnabled = true;
}
