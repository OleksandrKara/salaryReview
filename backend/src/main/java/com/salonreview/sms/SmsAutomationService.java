package com.salonreview.sms;

import com.salonreview.domain.SmsAutomation;
import com.salonreview.repo.SmsAutomationRepository;
import com.salonreview.repo.SmsMessageRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * DB-backed enable/disable state per automation (see V52, design.md D8). A newly-added
 * automation's row defaults {@code enabled = false} at the schema level — this service never
 * creates a row itself, it only reads/updates the ones a migration seeded.
 */
@Service
public class SmsAutomationService {

    public record AutomationSummary(String key, String name, String audienceDescription,
                                     boolean enabled, long sentLast30Days) {}

    private final SmsAutomationRepository repository;
    private final SmsMessageRepository messageRepository;

    public SmsAutomationService(SmsAutomationRepository repository, SmsMessageRepository messageRepository) {
        this.repository = repository;
        this.messageRepository = messageRepository;
    }

    /** {@code true} for a template with no {@code automationKey} (nothing to gate) or for a
     * key with no row yet (fail open — matches every other "unconfigured means don't block" shape
     * in this codebase, though in practice every real automation key always has a seeded row). */
    public boolean isEnabled(String automationKey) {
        if (automationKey == null) {
            return true;
        }
        return repository.findById(automationKey).map(SmsAutomation::isEnabled).orElse(true);
    }

    public List<AutomationSummary> list() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        return SmsAutomationRegistry.all().values().stream()
                .map(meta -> {
                    boolean enabled = repository.findById(meta.key()).map(SmsAutomation::isEnabled).orElse(false);
                    long sent = messageRepository.countByAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
                            meta.key(), "OUTBOUND", "SENT", since);
                    return new AutomationSummary(meta.key(), meta.name(), meta.audienceDescription(), enabled, sent);
                })
                .toList();
    }

    public void setEnabled(String automationKey, boolean enabled, String updatedBy) {
        SmsAutomation automation = repository.findById(automationKey)
                .orElseGet(() -> SmsAutomation.builder().automationKey(automationKey).build());
        automation.setEnabled(enabled);
        automation.setUpdatedBy(updatedBy);
        repository.save(automation);
    }
}
