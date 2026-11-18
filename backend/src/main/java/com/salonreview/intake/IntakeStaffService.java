package com.salonreview.intake;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.util.PhoneNumbers;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Staff-side reads and actions. Owners and managers see every form in their business; a provider only her own clients'. */
@Service
public class IntakeStaffService {

    public record Summary(Long id, String firstName, String phoneLast4, Instant appointmentStartAt, String status,
                          Instant sentAt, Instant signedAt, String signedVia, boolean needsAttention,
                          String providerName, String initialSmsState, String reminderSmsState, Instant openedAt) {}

    public record Detail(Summary summary, String signatureName, List<Map<String, String>> answers) {}

    public record PreviewRow(String firstInitial, String phoneLast4, Instant appointmentStartAt, String verdict) {}

    public enum ResendResult { SENT, TOO_SOON, SIGNED, NOT_SENT }

    static final Duration MANUAL_RESEND_COOLDOWN = Duration.ofMinutes(10);

    private final IntakeFormRepository forms;
    private final ProviderRepository providers;
    private final IntakeFormService service;
    private final FirstVisitDetector detector;
    private final SquareBookingMirrorRepository bookings;
    private final SquareCustomerMirrorRepository customers;

    public IntakeStaffService(IntakeFormRepository forms, ProviderRepository providers, IntakeFormService service,
                              FirstVisitDetector detector, SquareBookingMirrorRepository bookings,
                              SquareCustomerMirrorRepository customers) {
        this.forms = forms;
        this.providers = providers;
        this.service = service;
        this.detector = detector;
        this.bookings = bookings;
        this.customers = customers;
    }

    static boolean canSee(AppUserPrincipal who, IntakeForm f, Long businessId) {
        if (!f.getBusinessId().equals(businessId)) {
            return false;
        }
        Role role = who.getRole();
        if (role == Role.OWNER || role == Role.MANAGER) {
            return true;
        }
        return role == Role.PROVIDER && who.getProviderId() != null && who.getProviderId().equals(f.getProviderId());
    }

    private Optional<IntakeForm> visible(AppUserPrincipal who, Long businessId, Long id) {
        return forms.findByIdAndBusinessId(id, businessId).filter(f -> canSee(who, f, businessId));
    }

    public List<Summary> upcoming(AppUserPrincipal who, Long businessId, Duration back, Duration ahead) {
        Instant now = Instant.now();
        List<IntakeForm> rows = forms.findByBusinessIdAndAppointmentStartAtBetweenOrderByAppointmentStartAtAsc(
                businessId, now.minus(back), now.plus(ahead));
        return summarize(who, businessId, rows);
    }

    /** Forms for a client, matched by phone (the key the CRM cards already share). */
    public List<Summary> forPhone(AppUserPrincipal who, Long businessId, String phone) {
        String normalized = PhoneNumbers.normalize(phone);
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        customers.findByBusinessIdAndPhoneNumber(businessId, normalized)
                .forEach(c -> ids.add(c.getSquareCustomerId()));
        if (ids.isEmpty()) {
            return List.of();
        }
        return summarize(who, businessId, forms.findByBusinessIdAndSquareCustomerIdIn(businessId, ids));
    }

    private List<Summary> summarize(AppUserPrincipal who, Long businessId, List<IntakeForm> rows) {
        Map<Long, String> names = new HashMap<>();
        providers.findAllByBusinessId(businessId).forEach(p -> names.put(p.getId(), providerLabel(p)));
        List<Summary> out = new ArrayList<>();
        for (IntakeForm f : rows) {
            if (canSee(who, f, businessId)) {
                out.add(summary(f, names.get(f.getProviderId())));
            }
        }
        return out;
    }

    private static String providerLabel(Provider p) {
        return p.getDisplayName() != null && !p.getDisplayName().isBlank() ? p.getDisplayName() : p.getName();
    }

    static Summary summary(IntakeForm f, String providerName) {
        String phone = f.getPhoneNumber() == null ? "" : f.getPhoneNumber().replaceAll("\\D", "");
        String last4 = phone.length() >= 4 ? phone.substring(phone.length() - 4) : phone;
        return new Summary(f.getId(), f.getFirstName(), last4, f.getAppointmentStartAt(), f.getStatus(), f.getSentAt(),
                f.getSignedAt(), f.getSignedVia(), f.isNeedsAttention(), providerName, f.getInitialSmsState(),
                f.getReminderSmsState(), f.getOpenedAt());
    }

    public Optional<Detail> detail(AppUserPrincipal who, Long businessId, Long id) {
        return visible(who, businessId, id).map(f -> {
            String provider = f.getProviderId() == null ? null
                    : providers.findByIdAndBusinessId(f.getProviderId(), businessId).map(IntakeStaffService::providerLabel).orElse(null);
            Map<String, String> a = service.decryptAnswers(f);
            List<Map<String, String>> rows = new ArrayList<>();
            for (String[] q : IntakeAnswers.QUESTIONS) {
                boolean notAsked = "epilepsy".equals(q[0]) && !a.containsKey("epilepsy") && !a.isEmpty();
                if (a.containsKey(q[0]) || notAsked) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("key", q[0]);
                    row.put("label", q[1]);
                    row.put("value", notAsked ? "NOT_ASKED" : a.get(q[0]));
                    rows.add(row);
                }
            }
            return new Detail(summary(f, provider), f.getSignatureName(), rows);
        });
    }

    public Optional<ResendResult> resend(AppUserPrincipal who, Long businessId, Long id) {
        if (who.getRole() == Role.PROVIDER) {
            return Optional.empty();
        }
        return visible(who, businessId, id).map(f -> {
            if (f.isSigned()) {
                return ResendResult.SIGNED;
            }
            Instant now = Instant.now();
            if (f.getLastManualResendAt() != null && f.getLastManualResendAt().isAfter(now.minus(MANUAL_RESEND_COOLDOWN))) {
                return ResendResult.TOO_SOON;
            }
            return IntakeFormService.SMS_SENT.equals(service.sendText(f, f.getSentAt() != null, true))
                    ? ResendResult.SENT : ResendResult.NOT_SENT;
        });
    }

    public Optional<Boolean> markReceived(AppUserPrincipal who, Long businessId, Long id) {
        if (who.getRole() == Role.PROVIDER) {
            return Optional.empty();
        }
        return visible(who, businessId, id).map(f -> {
            service.markReceivedOnPaper(f);
            return true;
        });
    }

    /** Dry run for owner/manager: who would be treated as a first-time client among the next bookings. */
    public List<PreviewRow> preview(Long businessId, Duration ahead) {
        Instant now = Instant.now();
        List<PreviewRow> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SquareBookingMirror b : bookings.findByBusinessIdAndStartAtBetween(businessId, now, now.plus(ahead))) {
            if (!"ACCEPTED".equals(b.getStatus()) || b.getSquareCustomerId() == null) {
                continue;
            }
            FirstVisitDetector.Result r = detector.evaluate(b);
            if (r.verdict() == FirstVisitDetector.Verdict.RETURNING || !seen.add(b.getSquareCustomerId())) {
                continue;
            }
            SquareCustomerMirror c = customers.findByBusinessIdAndSquareCustomerId(businessId, b.getSquareCustomerId()).orElse(null);
            String name = c == null ? null : IntakeFormService.cleanFirstName(c.getGivenName());
            String phone = r.phone() == null ? "" : r.phone().replaceAll("\\D", "");
            out.add(new PreviewRow(name == null ? "?" : name.substring(0, 1).toUpperCase(),
                    phone.length() >= 4 ? phone.substring(phone.length() - 4) : phone, b.getStartAt(),
                    r.verdict().name()));
        }
        out.sort(java.util.Comparator.comparing(PreviewRow::appointmentStartAt));
        return out;
    }
}
