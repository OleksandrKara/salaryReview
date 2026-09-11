package com.salonreview.sms;

import com.salonreview.domain.ProviderScheduleClosureAlertConfig;
import com.salonreview.repo.ProviderScheduleClosureAlertConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Owner-editable notice-threshold (hours) for {@code provider_schedule_closure_alert} — see
 * {@link ProviderScheduleClosureAlertConfig}. {@link #getNoticeThresholdHours} is the single read
 * path {@code ProviderScheduleClosureAlertScheduler} uses every poll, so this owner setting takes
 * effect on the very next poll, no restart/redeploy needed.
 */
@Service
public class ProviderScheduleClosureAlertConfigService {

    /** Used both as the fallback when a business has no row yet, and as the seed value shown to an
     * owner who's never touched this setting — matches the fixed threshold this automation shipped
     * with before it became configurable. */
    public static final int DEFAULT_NOTICE_THRESHOLD_HOURS = 24;

    /** A week — generous enough for any real "how much notice is too little" policy, small enough
     * to keep {@code ProviderScheduleClosureAlertScheduler}'s derived lookahead window (threshold +
     * a fixed buffer) from growing unreasonably large. */
    public static final int MAX_NOTICE_THRESHOLD_HOURS = 168;

    private final ProviderScheduleClosureAlertConfigRepository repository;

    public ProviderScheduleClosureAlertConfigService(ProviderScheduleClosureAlertConfigRepository repository) {
        this.repository = repository;
    }

    public int getNoticeThresholdHours(Long businessId) {
        return repository.findByBusinessId(businessId)
                .map(ProviderScheduleClosureAlertConfig::getNoticeThresholdHours)
                .orElse(DEFAULT_NOTICE_THRESHOLD_HOURS);
    }

    public record Settings(int noticeThresholdHours, Instant updatedAt, String updatedBy) {
    }

    /** {@code updatedAt}/{@code updatedBy} are {@code null} when the business hasn't saved this
     * setting yet — same "still on the default, nobody's touched it" signal the GET endpoint
     * surfaces to the frontend, not a placeholder timestamp. */
    public Settings getSettings(Long businessId) {
        return repository.findByBusinessId(businessId)
                .map(c -> new Settings(c.getNoticeThresholdHours(), c.getUpdatedAt(), c.getUpdatedBy()))
                .orElse(new Settings(DEFAULT_NOTICE_THRESHOLD_HOURS, null, null));
    }

    public ProviderScheduleClosureAlertConfig update(Long businessId, int noticeThresholdHours, String updatedBy) {
        if (noticeThresholdHours < 1 || noticeThresholdHours > MAX_NOTICE_THRESHOLD_HOURS) {
            throw new IllegalArgumentException(
                    "Notice threshold must be between 1 and " + MAX_NOTICE_THRESHOLD_HOURS + " hours");
        }
        ProviderScheduleClosureAlertConfig config = repository.findByBusinessId(businessId)
                .orElseGet(() -> ProviderScheduleClosureAlertConfig.builder().businessId(businessId).build());
        config.setNoticeThresholdHours(noticeThresholdHours);
        config.setUpdatedBy(updatedBy);
        return repository.save(config);
    }
}
