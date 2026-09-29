package com.salonreview.sms;

import com.salonreview.domain.ProviderScheduleClosureAlertConfig;
import com.salonreview.repo.ProviderScheduleClosureAlertConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Owner policy for notice, minimum lost start window and observation mode. The scheduler reads
 * the complete policy on every poll; changed policy invalidates comparisons with older evidence.
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

    public record Settings(int noticeThresholdHours, int minimumLossWindowMinutes, boolean observationOnly,
                           Instant updatedAt, String updatedBy) {
        public Settings(int noticeThresholdHours, Instant updatedAt, String updatedBy) {
            this(noticeThresholdHours, 240, true, updatedAt, updatedBy);
        }
    }

    /** {@code updatedAt}/{@code updatedBy} are {@code null} when the business hasn't saved this
     * setting yet — same "still on the default, nobody's touched it" signal the GET endpoint
     * surfaces to the frontend, not a placeholder timestamp. */
    public Settings getSettings(Long businessId) {
        return repository.findByBusinessId(businessId)
                .map(c -> new Settings(c.getNoticeThresholdHours(), c.getMinimumLossWindowMinutes(),
                        c.isObservationOnly(), c.getUpdatedAt(), c.getUpdatedBy()))
                .orElse(new Settings(DEFAULT_NOTICE_THRESHOLD_HOURS, null, null));
    }

    public ProviderScheduleClosureAlertConfig update(Long businessId, int noticeThresholdHours, String updatedBy) {
        return update(businessId, noticeThresholdHours, null, null, updatedBy);
    }

    public ProviderScheduleClosureAlertConfig update(Long businessId, int noticeThresholdHours,
                                                     Integer minimumLossWindowMinutes, Boolean observationOnly,
                                                     String updatedBy) {
        if (noticeThresholdHours < 1 || noticeThresholdHours > MAX_NOTICE_THRESHOLD_HOURS) {
            throw new IllegalArgumentException(
                    "Notice threshold must be between 1 and " + MAX_NOTICE_THRESHOLD_HOURS + " hours");
        }
        if (minimumLossWindowMinutes != null && (minimumLossWindowMinutes < 60 || minimumLossWindowMinutes > 1440)) {
            throw new IllegalArgumentException("Minimum loss window must be between 60 and 1440 minutes");
        }
        ProviderScheduleClosureAlertConfig config = repository.findByBusinessId(businessId)
                .orElseGet(() -> ProviderScheduleClosureAlertConfig.builder().businessId(businessId).build());
        config.setNoticeThresholdHours(noticeThresholdHours);
        if (minimumLossWindowMinutes != null) config.setMinimumLossWindowMinutes(minimumLossWindowMinutes);
        if (observationOnly != null) config.setObservationOnly(observationOnly);
        config.setUpdatedBy(updatedBy);
        return repository.save(config);
    }
}
