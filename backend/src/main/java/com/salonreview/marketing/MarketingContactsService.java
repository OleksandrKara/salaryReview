package com.salonreview.marketing;

import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import com.salonreview.web.dto.MarketingContactHistoryDto;
import com.salonreview.web.dto.MarketingContactHistoryDto.Appointment;
import com.salonreview.web.dto.MarketingContactHistoryDto.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MarketingContactsService {

    private static final Logger log = LoggerFactory.getLogger(MarketingContactsService.class);

    // Square has no public API for a direct "view this booking" link (confirmed — nothing
    // documented), but the customer profile URL is a stable, long-standing Dashboard pattern.
    // The booking's own date/time/provider/services/price are shown inline in this table
    // anyway, so clicking through is only needed to see the customer's full Square history.
    private static final String SQUARE_CUSTOMER_PROFILE_URL = "https://app.squareup.com/dashboard/customers/%s";

    private final MarketingContactsRepository repository;
    private final SquareClient square;

    public MarketingContactsService(MarketingContactsRepository repository, SquareClient square) {
        this.repository = repository;
        this.square = square;
    }

    /** Never throws: same "this app's health must never depend on the other service's
     * schema" guarantee as MarketingDashboardService.dashboard.
     */
    public MarketingContactDto contacts() {
        try {
            List<Contact> contacts = repository.listAll().stream()
                    .map(MarketingContactsService::toContact)
                    .collect(Collectors.toList());
            return new MarketingContactDto(true, contacts);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building contacts list", ex);
            return MarketingContactDto.unavailable();
        }
    }

    /** Submission history always comes from our own DB (never throws beyond a 404 for an unknown
     * contact); Square appointment history is best-effort — if Square is unreachable, the
     * contact's own data still renders with an empty appointments list rather than a 500.
     */
    public MarketingContactHistoryDto history(UUID contactId) {
        MarketingContactsRepository.RawContact contact = repository.findById(contactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such contact"));

        List<Submission> submissions = repository.findSubmissionHistory(contact.phoneNumber(), contact.emailAddress())
                .stream()
                .map(MarketingContactsService::toSubmission)
                .collect(Collectors.toList());

        List<Appointment> appointments = contact.squareCustomerId() == null
                ? List.of()
                : fetchAppointments(contact.squareCustomerId());

        return new MarketingContactHistoryDto(submissions, appointments);
    }

    private List<Appointment> fetchAppointments(String squareCustomerId) {
        try {
            List<Booking> bookings = square.bookingsForCustomer(squareCustomerId);
            if (bookings.isEmpty()) return List.of();

            Map<String, String> memberNames = new HashMap<>();
            for (TeamMember tm : square.allTeamMembers()) memberNames.put(tm.id(), tm.fullName());

            List<String> variationIds = bookings.stream()
                    .flatMap(b -> b.appointmentSegments() == null ? java.util.stream.Stream.<AppointmentSegment>empty() : b.appointmentSegments().stream())
                    .map(AppointmentSegment::serviceVariationId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, String> serviceNames = square.catalogNames(variationIds);
            Map<String, BigDecimal> servicePrices = square.catalogPrices(variationIds);

            return bookings.stream().map(b -> toAppointment(b, memberNames, serviceNames, servicePrices)).toList();
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch Square appointment history for customer {}", squareCustomerId, ex);
            return List.of();
        }
    }

    private static Appointment toAppointment(
            Booking booking, Map<String, String> memberNames, Map<String, String> serviceNames, Map<String, BigDecimal> servicePrices
    ) {
        List<AppointmentSegment> segments = booking.appointmentSegments() == null ? List.of() : booking.appointmentSegments();
        String serviceName = segments.stream()
                .map(s -> serviceNames.get(s.serviceVariationId()))
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(" + "));
        BigDecimal price = segments.stream()
                .map(s -> servicePrices.getOrDefault(s.serviceVariationId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String artistName = segments.stream()
                .map(s -> memberNames.get(s.teamMemberId()))
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(null);

        return new Appointment(
                booking.id(),
                booking.status(),
                booking.startAt() == null ? null : java.time.Instant.parse(booking.startAt()),
                serviceName.isBlank() ? null : serviceName,
                price.signum() == 0 ? null : price,
                artistName
        );
    }

    private static Submission toSubmission(MarketingContactsRepository.RawSubmission raw) {
        return new Submission(
                raw.submissionType(),
                raw.occurredAt(),
                raw.landingPageSlug(),
                raw.variantName(),
                raw.utmSource(),
                raw.utmMedium(),
                raw.utmCampaign(),
                raw.serviceName(),
                raw.price()
        );
    }

    private static Contact toContact(MarketingContactsRepository.RawContact raw) {
        String squareProfileUrl = raw.squareCustomerId() == null
                ? null
                : String.format(SQUARE_CUSTOMER_PROFILE_URL, raw.squareCustomerId());
        return new Contact(
                raw.id().toString(),
                raw.givenName(),
                raw.phoneNumber(),
                raw.emailAddress(),
                raw.originalTrafficSource(),
                raw.marketingTrafficSource(),
                raw.landingPageSlug(),
                raw.variantName(),
                raw.deviceType(),
                raw.osName(),
                raw.osVersion(),
                raw.browserName(),
                raw.browserVersion(),
                raw.smsMarketingConsent(),
                raw.emailMarketingConsent(),
                raw.squareBookingId() != null,
                squareProfileUrl,
                raw.bookingStatus(),
                raw.bookingStartAt(),
                raw.bookingServiceName(),
                raw.bookingPrice(),
                raw.bookingArtistName(),
                raw.createdAt()
        );
    }
}
