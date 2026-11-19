package com.salonreview.square.webhook;

import com.salonreview.domain.PmuBookingStaffAlert;
import com.salonreview.domain.Provider;
import com.salonreview.repo.PmuBookingStaffAlertRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.sms.SmsAutomationService;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Team-chat Telegram alert when a client books a PMU procedure through the website. Only bookings
 * Square marks with source API (our site integration) count; staff-made dashboard bookings and
 * Square's own booking page are ignored. Consultations are skipped here because the consultation
 * request alert already covers them. Fires on booking.created for the PMU business only. */
@Service
public class PmuBookingStaffAlertService {

    private static final Logger log = LoggerFactory.getLogger(PmuBookingStaffAlertService.class);
    static final long PMU_BUSINESS_ID = 2L;
    static final String AUTOMATION_KEY = "pmu_booking_staff_alert";
    static final String SITE_SOURCE = "API";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)
            .withZone(ZoneId.of("America/Los_Angeles"));

    private final SquareClientProvider squareClients;
    private final ProviderRepository providers;
    private final SquareCustomerMirrorRepository customers;
    private final TelegramNotificationService telegram;
    private final SmsAutomationService automations;
    private final PmuBookingStaffAlertRepository alerts;
    static final int MAX_LEAD_WAITS = 8;
    long leadWaitMs = 30_000;
    private final java.util.concurrent.ScheduledExecutorService retries =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pmu-alert-retry");
                t.setDaemon(true);
                return t;
            });

    public PmuBookingStaffAlertService(SquareClientProvider squareClients, ProviderRepository providers,
                                       SquareCustomerMirrorRepository customers,
                                       TelegramNotificationService telegram,
                                       SmsAutomationService automations,
                                       PmuBookingStaffAlertRepository alerts) {
        this.squareClients = squareClients;
        this.providers = providers;
        this.customers = customers;
        this.telegram = telegram;
        this.automations = automations;
        this.alerts = alerts;
    }

    public void handleBookingCreated(Long businessId, SquareWebhookEvent.Booking booking) {
        if (booking == null || businessId == null || businessId != PMU_BUSINESS_ID) return;
        if (!SITE_SOURCE.equals(booking.source())) return;
        if (booking.id() == null || booking.appointmentSegments() == null || booking.appointmentSegments().isEmpty()) return;
        try {
            if (!automations.isEnabled(businessId, AUTOMATION_KEY)) return;
            Set<String> variationIds = new LinkedHashSet<>();
            for (SquareClient.AppointmentSegment s : booking.appointmentSegments()) {
                if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId());
            }
            Map<String, String> names = squareClients.forBusiness(businessId).catalogNames(variationIds);
            List<String> serviceNames = new ArrayList<>();
            boolean procedure = false;
            for (String id : variationIds) {
                String name = names.get(id);
                if (name == null) continue;
                if (!name.toLowerCase(Locale.US).contains("consultation")) {
                    procedure = true;
                    serviceNames.add(name);
                }
            }
            if (!procedure) return;
            if (alerts.existsByBusinessIdAndSquareBookingId(businessId, booking.id())) return;
            PmuBookingStaffAlert row;
            try {
                row = alerts.saveAndFlush(PmuBookingStaffAlert.builder().businessId(businessId)
                        .squareBookingId(booking.id()).serviceNames(String.join(", ", serviceNames)).build());
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                return;
            }
            deliver(businessId, booking, row, String.join(", ", serviceNames), 0);
        } catch (RuntimeException ex) {
            log.warn("PMU staff booking alert failed for booking {}: {}", booking.id(), ex.toString());
        }
    }

    /** The website creates the Square booking first and the CRM lead a few minutes later, and the customer
     * mirror lags too. So the name and phone come from Square directly when the mirror has no row yet, and
     * while no CRM lead exists the alert is retried for a few minutes so it can carry the "Open lead" link. */
    private void deliver(Long businessId, SquareWebhookEvent.Booking booking, PmuBookingStaffAlert row,
                         String services, int attempt) {
        try {
            String customerId = booking.customerId() == null ? "" : booking.customerId();
            var mirror = customers.findByBusinessIdAndSquareCustomerId(businessId, customerId);
            String name = customerName(businessId, booking.customerId());
            String phone = mirror.map(c -> c.getPhoneNumber()).orElse(null);
            String email = mirror.map(c -> c.getEmailAddress()).orElse(null);
            if (name == null || phone == null) {
                try {
                    var live = squareClients.forBusiness(businessId).customerById(booking.customerId());
                    if (live != null && live.isPresent()) {
                        var c = live.get();
                        if (name == null && !c.fullName().isBlank()) name = c.fullName();
                        if (phone == null) phone = c.phoneNumber();
                        if (email == null) email = c.emailAddress();
                    }
                } catch (RuntimeException e) {
                    log.warn("Live Square customer lookup failed for booking {}: {}", booking.id(), e.toString());
                }
            }
            var ctx = telegram.leadContext(businessId, phone, email);
            if (ctx != null && ctx.state() == com.salonreview.telegram.LeadAlertContext.State.MISSING
                    && attempt < MAX_LEAD_WAITS) {
                retries.schedule(() -> deliver(businessId, booking, row, services, attempt + 1),
                        leadWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }
            boolean sent = telegram.sendProcedureBookingAlert(businessId, services, name, phone, email,
                    providerNames(booking), booking.startAt());
            row.setDeliveryStatus(sent ? "SENT" : "FAILED");
            if (sent) row.setDeliveredAt(Instant.now());
            alerts.save(row);
        } catch (RuntimeException ex) {
            log.warn("PMU staff booking alert failed for booking {}: {}", booking.id(), ex.toString());
        }
    }

    static String formatMessage(List<String> services, String customer, String providerNames, String startAt) {
        String service = String.join(", ", services);
        String when = formatWhen(startAt);
        return "🆕 New procedure booked on the website\n"
                + "💆 Service: " + service + "\n"
                + "👤 Client: " + (customer == null ? "not provided" : customer) + "\n"
                + "💇 Artist: " + (providerNames == null ? "not assigned" : providerNames) + "\n"
                + "🕐 Appointment: " + when
                + "\n\n—\n\n"
                + "🆕 Новая запись на процедуру через сайт\n"
                + "💆 Услуга: " + service + "\n"
                + "👤 Клиент: " + (customer == null ? "не указан" : customer) + "\n"
                + "💇 Мастер: " + (providerNames == null ? "не назначен" : providerNames) + "\n"
                + "🕐 Когда: " + when;
    }

    static String formatWhen(String startAt) {
        try {
            return WHEN.format(Instant.parse(startAt)) + " (PT)";
        } catch (Exception e) {
            return startAt == null ? "-" : startAt;
        }
    }

    private String providerNames(SquareWebhookEvent.Booking booking) {
        Set<String> names = new LinkedHashSet<>();
        for (SquareClient.AppointmentSegment seg : booking.appointmentSegments()) {
            if (seg.teamMemberId() == null) continue;
            providers.findBySquareTeamMemberId(seg.teamMemberId()).map(Provider::getDisplayName).ifPresent(names::add);
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private String customerName(Long businessId, String squareCustomerId) {
        if (squareCustomerId == null) return null;
        return customers.findByBusinessIdAndSquareCustomerId(businessId, squareCustomerId).map(c -> {
            String full = ((c.getGivenName() == null ? "" : c.getGivenName()) + " "
                    + (c.getFamilyName() == null ? "" : c.getFamilyName())).trim();
            return full.isBlank() ? null : full;
        }).orElse(null);
    }
}
