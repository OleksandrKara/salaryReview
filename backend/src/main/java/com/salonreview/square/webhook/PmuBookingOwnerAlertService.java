package com.salonreview.square.webhook;

import com.salonreview.domain.Provider;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Owner-only Telegram alert when someone books a PMU procedure or an in-studio consultation.
 * Free online consultations are skipped (staff already get their own alert). Fires on
 * booking.created only, for the PMU business only. */
@Service
public class PmuBookingOwnerAlertService {

    private static final Logger log = LoggerFactory.getLogger(PmuBookingOwnerAlertService.class);
    static final long PMU_BUSINESS_ID = 2L;
    private static final int SEEN_LIMIT = 500;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)
            .withZone(ZoneId.of("America/Los_Angeles"));

    private final SquareClientProvider squareClients;
    private final ProviderRepository providers;
    private final SquareCustomerMirrorRepository customers;
    private final TelegramNotificationService telegram;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    });

    public PmuBookingOwnerAlertService(SquareClientProvider squareClients, ProviderRepository providers,
                                       SquareCustomerMirrorRepository customers,
                                       TelegramNotificationService telegram) {
        this.squareClients = squareClients;
        this.providers = providers;
        this.customers = customers;
        this.telegram = telegram;
    }

    public void handleBookingCreated(Long businessId, SquareWebhookEvent.Booking booking) {
        if (booking == null || businessId == null || businessId != PMU_BUSINESS_ID) return;
        if (booking.appointmentSegments() == null || booking.appointmentSegments().isEmpty()) return;
        try {
            if (booking.id() != null) {
                synchronized (seen) {
                    if (!seen.add(booking.id())) return;
                }
            }
            Set<String> variationIds = new LinkedHashSet<>();
            for (SquareClient.AppointmentSegment s : booking.appointmentSegments()) {
                if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId());
            }
            Map<String, String> names = squareClients.forBusiness(businessId).catalogNames(variationIds);
            List<String> serviceNames = new ArrayList<>();
            boolean offlineConsultation = false;
            boolean procedure = false;
            boolean onlineConsultation = false;
            for (String id : variationIds) {
                String name = names.get(id);
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.US);
                if (lower.contains("consultation")) {
                    if (lower.contains("online")) onlineConsultation = true;
                    else offlineConsultation = true;
                } else {
                    procedure = true;
                }
                serviceNames.add(name);
            }
            if (!procedure && !offlineConsultation) {
                if (!onlineConsultation) {
                    log.warn("PMU owner booking alert: no service names resolved for booking {}", booking.id());
                }
                return;
            }
            String kind = procedure ? "процедура" : "консультация в студии";
            telegram.sendOwnerAlert(businessId, formatMessage(kind, serviceNames,
                    customerName(businessId, booking.customerId()), providerNames(booking), booking.startAt()));
        } catch (RuntimeException ex) {
            log.warn("PMU owner booking alert failed for booking {}: {}", booking.id(), ex.toString());
        }
    }

    static String formatMessage(String kind, List<String> services, String customer, String providerNames, String startAt) {
        return "Новая запись: " + kind + "\n"
                + "Услуга: " + String.join(", ", services) + "\n"
                + "Клиент: " + (customer == null ? "не указан" : customer) + "\n"
                + "Мастер: " + (providerNames == null ? "не указан" : providerNames) + "\n"
                + "Когда: " + formatWhen(startAt);
    }

    static String formatWhen(String startAt) {
        try {
            return WHEN.format(Instant.parse(startAt)) + " (PT)";
        } catch (Exception e) {
            return startAt == null ? "не указано" : startAt;
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
