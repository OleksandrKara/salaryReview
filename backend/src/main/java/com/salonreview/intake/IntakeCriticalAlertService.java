package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Critical intake answers must never be missed (owner request 2026-11-18). For every signed form with
 * a flagged answer this: (1) alerts the managers' Telegram chat once, wording by severity; (2) writes a
 * loud banner into the seller note of the client's upcoming Square bookings, so the technician sees it
 * on the calendar. Nothing is ever sent to the client. Runs for the businesses in
 * {@code intake.form.businesses}; it is a safety net, so it has no on/off switch of its own.
 */
@Component
public class IntakeCriticalAlertService {

    private static final Logger log = LoggerFactory.getLogger(IntakeCriticalAlertService.class);
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US).withZone(PACIFIC);
    static final String ACCEPTED = "ACCEPTED";
    static final int MAX_ALERT_ATTEMPTS = 5;
    static final int MAX_MARKER_ATTEMPTS = 6;
    static final int MAX_MARKERS_PER_RUN = 25;
    static final Duration LOOKBACK = Duration.ofHours(2);
    static final Duration BACKFILL_ALERT_WINDOW = Duration.ofDays(2);
    static final Duration SCOPE_ALERT_EVERY = Duration.ofHours(24);

    private final Set<Long> businesses;
    private final IntakeFormRepository forms;
    private final SquareBookingMirrorRepository bookings;
    private final ProviderRepository providers;
    private final IntakeFormService service;
    private final SquareClientProvider squareProvider;
    private final TelegramNotificationService telegram;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private Instant lastScopeAlert = Instant.EPOCH;

    @Autowired
    public IntakeCriticalAlertService(@Value("${intake.form.businesses:}") String businesses,
                                      IntakeFormRepository forms, SquareBookingMirrorRepository bookings,
                                      ProviderRepository providers, IntakeFormService service,
                                      SquareClientProvider squareProvider, TelegramNotificationService telegram,
                                      JdbcTemplate jdbc) {
        this(parse(businesses), forms, bookings, providers, service, squareProvider, telegram, jdbc,
                Clock.systemUTC());
    }

    IntakeCriticalAlertService(Set<Long> businesses, IntakeFormRepository forms,
                               SquareBookingMirrorRepository bookings, ProviderRepository providers,
                               IntakeFormService service, SquareClientProvider squareProvider,
                               TelegramNotificationService telegram, JdbcTemplate jdbc, Clock clock) {
        this.businesses = businesses;
        this.forms = forms;
        this.bookings = bookings;
        this.providers = providers;
        this.service = service;
        this.squareProvider = squareProvider;
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static Set<Long> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(spec.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public void run() {
        step("backfill", this::backfillLevels);
        step("alerts", this::sendAlerts);
        step("bookings", this::markBookings);
    }

    private void step(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("Intake critical alert {} step failed (retried next run): {}", name, e.getMessage());
        }
    }

    /** Forms signed before levels existed: set the level; alert only if signed recently. */
    void backfillLevels() {
        Instant now = clock.instant();
        for (IntakeForm f : forms.findByStatusAndNeedsAttentionTrueAndAttentionLevelIsNull(IntakeForm.SIGNED)) {
            if (!businesses.contains(f.getBusinessId())) {
                continue;
            }
            try {
                IntakeFlagRules.Severity level = IntakeFlagRules.level(service.decryptAnswers(f));
                f.setAttentionLevel(level == null ? "NONE" : level.name());
                if (level != null && f.getCriticalAlertState() == null) {
                    boolean recent = f.getSignedAt() != null && f.getSignedAt().isAfter(now.minus(BACKFILL_ALERT_WINDOW));
                    f.setCriticalAlertState(recent ? "PENDING" : "SKIPPED");
                }
                forms.save(f);
            } catch (RuntimeException e) {
                log.warn("Intake form {} level backfill failed: {}", f.getId(), e.getMessage());
            }
        }
    }

    void sendAlerts() {
        for (IntakeForm f : forms.findByStatusAndCriticalAlertStateAndCriticalAlertAttemptsLessThan(
                IntakeForm.SIGNED, "PENDING", MAX_ALERT_ATTEMPTS)) {
            if (!businesses.contains(f.getBusinessId())) {
                continue;
            }
            boolean ok = false;
            try {
                List<IntakeFlagRules.Flag> flags = IntakeFlagRules.evaluate(service.decryptAnswers(f));
                if (flags.isEmpty()) {
                    f.setCriticalAlertState("SKIPPED");
                    forms.save(f);
                    continue;
                }
                ok = telegram.sendHtmlAlert(f.getBusinessId(), alertHtml(f, flags), "Open intake forms",
                        telegram.intakeBoardLink(f.getBusinessId()));
            } catch (RuntimeException e) {
                log.warn("Intake form {} critical alert failed: {}", f.getId(), e.getMessage());
            }
            if (ok) {
                f.setCriticalAlertState("SENT");
                f.setCriticalAlertedAt(clock.instant());
            } else {
                f.setCriticalAlertAttempts(f.getCriticalAlertAttempts() + 1);
                if (f.getCriticalAlertAttempts() >= MAX_ALERT_ATTEMPTS) {
                    f.setCriticalAlertState("FAILED");
                    telegram.sendOwnerAlert(f.getBusinessId(), "Intake form #" + f.getId()
                            + " has a flagged answer but the managers' Telegram alert could not be sent after "
                            + MAX_ALERT_ATTEMPTS + " tries. Check the form in the CRM now.");
                }
            }
            forms.save(f);
        }
    }

    /** The message for the managers' chat (Telegram HTML), EN then RU. Answers as labels, phone last 4 only. */
    String alertHtml(IntakeForm f, List<IntakeFlagRules.Flag> flags) {
        boolean critical = IntakeFlagRules.level(flags) == IntakeFlagRules.Severity.CRITICAL;
        StringBuilder sb = new StringBuilder();
        if (critical) {
            sb.append("🚨🚨 <b>CRITICAL INTAKE ANSWER</b> / <b>КРИТИЧНЫЙ ОТВЕТ В АНКЕТЕ</b>\n");
        } else {
            sb.append("⚠️ <b>Intake form needs attention</b> / <b>Обратите внимание на анкету</b>\n");
        }
        sb.append("👤 ").append(TelegramNotificationService.escapeHtml(name(f))).append(" (…")
                .append(last4(f.getPhoneNumber())).append(")\n");
        sb.append("📅 ").append(TelegramNotificationService.escapeHtml(appointmentLine(f))).append("\n\n");
        for (IntakeFlagRules.Flag fl : flags) {
            sb.append(fl.critical() ? "🔴 " : "🟠 ")
                    .append(TelegramNotificationService.escapeHtml(fl.rule().noteLabel())).append(": <b>")
                    .append(fl.valueText()).append("</b> / ")
                    .append(TelegramNotificationService.escapeHtml(fl.rule().ru())).append(": <b>")
                    .append(fl.valueRu()).append("</b>\n");
        }
        sb.append('\n');
        if (critical) {
            sb.append("Do NOT start the service until a manager has checked with the client.\n")
                    .append("Не начинать услугу, пока менеджер не уточнит у клиента.");
        } else {
            sb.append("The technician must read the intake form before starting.\n")
                    .append("Мастер должен прочитать анкету перед началом услуги.");
        }
        return sb.toString();
    }

    private String name(IntakeForm f) {
        return f.getFirstName() == null || f.getFirstName().isBlank() ? "Client" : f.getFirstName().strip();
    }

    private static String last4(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() <= 4 ? d : d.substring(d.length() - 4);
    }

    private String appointmentLine(IntakeForm f) {
        Instant from = clock.instant().minus(LOOKBACK);
        List<SquareBookingMirror> upcoming = upcoming(f, from);
        SquareBookingMirror next = upcoming.isEmpty() ? null : upcoming.get(0);
        Instant start = next != null ? next.getStartAt() : f.getAppointmentStartAt();
        Long providerId = f.getProviderId();
        String tech = providerId == null ? null
                : providers.findByIdAndBusinessId(providerId, f.getBusinessId())
                        .map(p -> p.getDisplayName() != null && !p.getDisplayName().isBlank() ? p.getDisplayName() : p.getName())
                        .orElse(null);
        return (start == null ? "time unknown" : WHEN.format(start)) + (tech == null ? "" : " · " + tech);
    }

    private List<SquareBookingMirror> upcoming(IntakeForm f, Instant from) {
        List<SquareBookingMirror> out = new ArrayList<>();
        for (SquareBookingMirror b : bookings.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(
                f.getBusinessId(), f.getSquareCustomerId(), from)) {
            if (ACCEPTED.equals(b.getStatus()) && b.getStartAt() != null) {
                out.add(b);
            }
        }
        out.sort(java.util.Comparator.comparing(SquareBookingMirror::getStartAt));
        return out;
    }

    /** Banner into the seller note of every upcoming booking of a flagged client, once per banner text. */
    void markBookings() {
        Instant now = clock.instant();
        int budget = MAX_MARKERS_PER_RUN;
        for (IntakeForm f : forms.findByStatusAndAttentionLevelIn(IntakeForm.SIGNED, List.of("CRITICAL", "ATTENTION"))) {
            if (!businesses.contains(f.getBusinessId()) || budget <= 0) {
                continue;
            }
            String banner;
            try {
                banner = IntakeSquareNote.alertBlock(f, service.decryptAnswers(f));
            } catch (RuntimeException e) {
                continue;
            }
            if (banner == null) {
                continue;
            }
            String key = Integer.toHexString(banner.hashCode());
            for (SquareBookingMirror b : upcoming(f, now.minus(LOOKBACK))) {
                if (budget <= 0) {
                    break;
                }
                if (markerDone(f.getBusinessId(), b.getSquareBookingId(), key)) {
                    continue;
                }
                budget--;
                markBooking(f.getBusinessId(), b.getSquareBookingId(), banner, key);
            }
        }
    }

    private boolean markerDone(Long businessId, String bookingId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT flag_key, attempts, synced_at FROM intake_booking_flag_sync WHERE business_id = ? AND square_booking_id = ?",
                businessId, bookingId);
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> r = rows.get(0);
        boolean sameKey = key.equals(r.get("flag_key"));
        if (!sameKey) {
            return false;
        }
        return r.get("synced_at") != null || ((Number) r.get("attempts")).intValue() >= MAX_MARKER_ATTEMPTS;
    }

    private void markBooking(Long businessId, String bookingId, String banner, String key) {
        try {
            squareProvider.forBusiness(businessId).updateBookingSellerNote(bookingId, note -> {
                String rest = IntakeSquareNote.stripBlocks(note, IntakeSquareNote.ALERT_START, IntakeSquareNote.ALERT_END, false);
                return rest.isEmpty() ? banner : banner + "\n" + rest;
            });
            record(businessId, bookingId, key, true);
        } catch (RestClientResponseException e) {
            record(businessId, bookingId, key, false);
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403) {
                alertMissingScope(businessId, code);
            } else {
                log.warn("Booking {} seller-note marker failed: HTTP {}", bookingId, code);
            }
        } catch (RuntimeException e) {
            record(businessId, bookingId, key, false);
            log.warn("Booking {} seller-note marker failed: {}", bookingId, e.getMessage());
        }
    }

    private void record(Long businessId, String bookingId, String key, boolean synced) {
        jdbc.update("""
                INSERT INTO intake_booking_flag_sync (business_id, square_booking_id, flag_key, attempts, synced_at, updated_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? THEN now() END, now())
                ON CONFLICT (business_id, square_booking_id) DO UPDATE SET
                    attempts = CASE WHEN intake_booking_flag_sync.flag_key = EXCLUDED.flag_key
                                    THEN intake_booking_flag_sync.attempts + EXCLUDED.attempts ELSE EXCLUDED.attempts END,
                    flag_key = EXCLUDED.flag_key,
                    synced_at = EXCLUDED.synced_at,
                    updated_at = now()
                """, businessId, bookingId, key, synced ? 0 : 1, synced);
    }

    private void alertMissingScope(Long businessId, int code) {
        Instant now = clock.instant();
        if (lastScopeAlert.plus(SCOPE_ALERT_EVERY).isAfter(now)) {
            return;
        }
        lastScopeAlert = now;
        telegram.sendOwnerAlert(businessId, "Intake critical alert: Square refused to write the warning into an "
                + "appointment (HTTP " + code + "). The Square app needs the APPOINTMENTS_ALL_WRITE permission "
                + "(Square Developer Dashboard > the app > OAuth > Appointments write, then reconnect Square). "
                + "Until then the warning is only in the client note and the Telegram alert.");
    }
}
