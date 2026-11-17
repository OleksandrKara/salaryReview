package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.IntakeFormRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Status chip data for intake-form texts in every SMS list. Status and times only, never answers. */
@Component
public class IntakeSmsStatus {

    public static final String AUTOMATION_KEY = "first_visit_intake_form";

    /** {@code state} is SIGNED, OPENED or NOT_SIGNED; {@code signedVia} is CLIENT_FORM or STAFF_PAPER. */
    public record Chip(String state, Instant openedAt, Instant signedAt, String signedVia) {}

    private final IntakeFormRepository forms;

    public IntakeSmsStatus(IntakeFormRepository forms) {
        this.forms = forms;
    }

    public Map<Long, Chip> forMessages(long businessId, List<SmsMessage> messages) {
        List<SmsMessage> intake = messages.stream()
                .filter(m -> AUTOMATION_KEY.equals(m.getAutomationKey()) && "OUTBOUND".equals(m.getDirection()))
                .toList();
        if (intake.isEmpty()) {
            return Map.of();
        }
        Set<String> phones = intake.stream().map(SmsMessage::getPhoneNumber).collect(Collectors.toSet());
        Map<String, List<IntakeForm>> byPhone = forms.findByBusinessIdAndPhoneNumberIn(businessId, phones).stream()
                .collect(Collectors.groupingBy(IntakeForm::getPhoneNumber));
        Map<Long, Chip> out = new HashMap<>();
        for (SmsMessage m : intake) {
            List<IntakeForm> candidates = byPhone.getOrDefault(m.getPhoneNumber(), List.of());
            // A phone can belong to more than one customer: use the form created last before this text.
            candidates.stream()
                    .filter(f -> m.getCreatedAt() == null || !f.getCreatedAt().isAfter(m.getCreatedAt().plusSeconds(60)))
                    .max(Comparator.comparing(IntakeForm::getCreatedAt))
                    .or(() -> candidates.stream().min(Comparator.comparing(IntakeForm::getCreatedAt)))
                    .ifPresent(f -> out.put(m.getId(), chip(f)));
        }
        return out;
    }

    static Chip chip(IntakeForm f) {
        String state = f.isSigned() ? "SIGNED" : f.getOpenedAt() != null ? "OPENED" : "NOT_SIGNED";
        return new Chip(state, f.getOpenedAt(), f.getSignedAt(), f.getSignedVia());
    }
}
