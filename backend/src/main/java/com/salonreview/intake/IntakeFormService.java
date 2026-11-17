package com.salonreview.intake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.SquareCredentialCipher;
import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.marketing.ContactNames;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Creates, sends and records first-visit intake forms. Answers are never logged or put in a text. */
@Service
public class IntakeFormService {

    private static final Logger log = LoggerFactory.getLogger(IntakeFormService.class);

    public static final String TEMPLATE_INITIAL_TECH = "intake_form_request_with_technician";
    public static final String TEMPLATE_INITIAL = "intake_form_request_no_technician";
    public static final String TEMPLATE_REMINDER_TECH = "intake_form_reminder_with_technician";
    public static final String TEMPLATE_REMINDER = "intake_form_reminder_no_technician";

    public static final String SMS_SENT = "SENT";
    public static final String SMS_SEND_FAILED = "SEND_FAILED";
    public static final String SMS_SKIPPED_PREFIX = "SKIPPED_";

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, MMM d 'at' h:mm a", Locale.US);
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private final IntakeFormRepository forms;
    private final SquareCustomerMirrorRepository customers;
    private final ProviderRepository providers;
    private final TwilioSmsService sms;
    private final SquareCredentialCipher cipher;
    private final ObjectMapper mapper;
    private final String publicBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public IntakeFormService(IntakeFormRepository forms, SquareCustomerMirrorRepository customers,
                             ProviderRepository providers, TwilioSmsService sms, SquareCredentialCipher cipher,
                             ObjectMapper mapper, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.forms = forms;
        this.customers = customers;
        this.providers = providers;
        this.sms = sms;
        this.cipher = cipher;
        this.mapper = mapper;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public IntakeForm create(SquareBookingMirror booking, FirstVisitDetector.Result detected) {
        SquareCustomerMirror customer = customers
                .findByBusinessIdAndSquareCustomerId(booking.getBusinessId(), booking.getSquareCustomerId()).orElse(null);
        IntakeForm form = IntakeForm.builder()
                .businessId(booking.getBusinessId())
                .squareCustomerId(booking.getSquareCustomerId())
                .squareBookingId(booking.getSquareBookingId())
                .providerId(providerOf(booking))
                .phoneNumber(detected.phone())
                .firstName(customer == null ? null : cleanFirstName(customer.getGivenName()))
                .token(newToken())
                .appointmentStartAt(booking.getStartAt())
                .status(IntakeForm.PENDING)
                .build();
        return forms.save(form);
    }

    private Long providerOf(SquareBookingMirror booking) {
        if (booking.getAppointmentSegments() == null) {
            return null;
        }
        for (SquareBookingMirror.Segment s : booking.getAppointmentSegments()) {
            if (s.teamMemberId() != null) {
                Optional<Provider> p = providers.findBySquareTeamMemberIdAndBusinessId(
                        s.teamMemberId(), booking.getBusinessId());
                if (p.isPresent()) {
                    return p.get().getId();
                }
            }
        }
        return null;
    }

    static String cleanFirstName(String given) {
        if (given == null || given.isBlank() || ContactNames.isJunk(given)) {
            return null;
        }
        String first = Names.firstNameOnly(Names.capitalizeFirst(ContactNames.clean(given)));
        return first == null || first.isBlank() || ContactNames.isJunk(first) ? null : first;
    }

    private String newToken() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public String link(IntakeForm f) {
        return publicBaseUrl + "/intake/" + f.getToken();
    }

    /** Sends the first or the reminder text. Returns the stored SMS state. */
    public String sendText(IntakeForm f, boolean reminder) {
        return sendText(f, reminder, false);
    }

    /** {@code manual}: a staff resend, which leaves the automatic first/reminder states alone. */
    public String sendText(IntakeForm f, boolean reminder, boolean manual) {
        String techName = technicianName(f);
        String template = reminder
                ? (techName == null ? TEMPLATE_REMINDER : TEMPLATE_REMINDER_TECH)
                : (techName == null ? TEMPLATE_INITIAL : TEMPLATE_INITIAL_TECH);
        Map<String, String> vars = new HashMap<>();
        vars.put("greeting", f.getFirstName() == null ? "Hi!" : "Hi " + f.getFirstName() + "!");
        vars.put("when", WHEN.format(f.getAppointmentStartAt().atZone(PACIFIC)));
        vars.put("link", link(f));
        if (techName != null) {
            vars.put("technician", techName);
        }
        TwilioSmsService.SmsSendResult r = sms.sendTemplated(f.getBusinessId(), template, f.getPhoneNumber(), vars);
        String state = r.sent() ? SMS_SENT
                : "send_failed".equals(r.reason()) || r.reason() == null ? SMS_SEND_FAILED
                : SMS_SKIPPED_PREFIX + r.reason().toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        if (manual) {
            if (r.sent()) {
                f.setLastManualResendAt(now);
            }
        } else if (reminder) {
            f.setReminderSmsState(state);
            if (r.sent()) {
                f.setReminderSentAt(now);
            }
        } else {
            f.setInitialSmsState(state);
        }
        if (r.sent()) {
            f.setSentAt(f.getSentAt() == null ? now : f.getSentAt());
            if (!f.isSigned()) {
                f.setStatus(IntakeForm.SENT);
            }
        }
        f.setUpdatedAt(now);
        forms.save(f);
        return state;
    }

    private String technicianName(IntakeForm f) {
        if (f.getProviderId() == null) {
            return null;
        }
        return providers.findByIdAndBusinessId(f.getProviderId(), f.getBusinessId())
                .map(p -> p.getDisplayName() != null && !p.getDisplayName().isBlank() ? p.getDisplayName() : p.getName())
                .map(n -> Names.firstNameOnly(Names.capitalizeFirst(n)))
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
    }

    // ---- client side -------------------------------------------------------------------------

    public record PublicView(boolean signed, String firstName, String businessName, String when, boolean expired) {}

    public Optional<PublicView> openForClient(String token) {
        return forms.findByToken(token).map(f -> {
            if (f.getOpenedAt() == null) {
                f.setOpenedAt(Instant.now());
                forms.save(f);
            }
            boolean expired = !f.isSigned() && f.getAppointmentStartAt().isBefore(Instant.now().minusSeconds(48 * 3600));
            return new PublicView(f.isSigned(), f.getFirstName(), "AK.LUX.NAILS",
                    WHEN.format(f.getAppointmentStartAt().atZone(PACIFIC)), expired);
        });
    }

    public enum SubmitResult { OK, ALREADY_SIGNED, NOT_FOUND }

    public SubmitResult submit(String token, Map<String, String> rawAnswers, String signatureName, boolean agreed) {
        IntakeForm f = forms.findByToken(token).orElse(null);
        if (f == null) {
            return SubmitResult.NOT_FOUND;
        }
        if (f.isSigned()) {
            return SubmitResult.ALREADY_SIGNED;
        }
        Map<String, String> answers = IntakeAnswers.validate(rawAnswers);
        String name = signatureName == null ? "" : signatureName.replaceAll("\\p{Cntrl}", "").trim();
        if (!agreed || name.length() < 2 || name.length() > 120) {
            throw new IntakeAnswers.Invalid("signature");
        }
        try {
            f.setAnswersEncrypted(cipher.encrypt(mapper.writeValueAsString(answers)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not serialise intake answers");
        }
        f.setSignatureName(name);
        f.setNeedsAttention(IntakeAnswers.needsAttention(answers));
        f.setStatus(IntakeForm.SIGNED);
        f.setSignedAt(Instant.now());
        f.setSignedVia(IntakeForm.VIA_CLIENT_FORM);
        f.setUpdatedAt(Instant.now());
        forms.save(f);
        log.info("Intake form {} signed", f.getId());
        return SubmitResult.OK;
    }

    // ---- staff side --------------------------------------------------------------------------

    public Map<String, String> decryptAnswers(IntakeForm f) {
        if (f.getAnswersEncrypted() == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(cipher.decrypt(f.getAnswersEncrypted()), MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not read intake answers");
        }
    }

    /** Staff marks a form received on paper or in person; no answers are stored. */
    public void markReceivedOnPaper(IntakeForm f) {
        if (f.isSigned()) {
            return;
        }
        Instant now = Instant.now();
        f.setStatus(IntakeForm.SIGNED);
        f.setSignedAt(now);
        f.setSignedVia(IntakeForm.VIA_STAFF_PAPER);
        f.setUpdatedAt(now);
        forms.save(f);
    }
}
