package com.salonreview.marketing;

import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Appointment;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import com.salonreview.web.dto.MarketingContactDto.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MarketingContactsService {

    private static final Logger log = LoggerFactory.getLogger(MarketingContactsService.class);

    // Square has no public API for a direct "view this booking" link, and no officially
    // documented deep link to one specific customer either. /dashboard/customers/directory is
    // Square's own confirmed path for the customer directory (support article); appending the
    // customer id to it is the best-available link — the previous .../customers/{id} form
    // (with no /directory) was reported broken.
    private static final String SQUARE_CUSTOMER_PROFILE_URL = "https://app.squareup.com/dashboard/customers/directory/%s";

    private final MarketingContactsRepository repository;
    private final SquareClient square;

    public MarketingContactsService(MarketingContactsRepository repository, SquareClient square) {
        this.repository = repository;
        this.square = square;
    }

    /** Never throws: same "this app's health must never depend on the other service's
     * schema" guarantee as MarketingDashboardService.dashboard. Submissions and (when a Square
     * customer is known) appointment history are fetched eagerly for every contact here, rather
     * than lazily per-click, so the UI can show "no appointments"/"no submissions" without an
     * extra round trip — see the Contact record's field docs.
     */
    public MarketingContactDto contacts() {
        try {
            List<Contact> contacts = repository.listAll().stream()
                    .map(this::toContact)
                    .collect(Collectors.toList());
            return new MarketingContactDto(true, contacts);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building contacts list", ex);
            return MarketingContactDto.unavailable();
        }
    }

    private Contact toContact(MarketingContactsRepository.RawContact raw) {
        String squareProfileUrl = raw.squareCustomerId() == null
                ? null
                : String.format(SQUARE_CUSTOMER_PROFILE_URL, raw.squareCustomerId());

        List<Submission> submissions = repository.findSubmissionHistory(raw.phoneNumber())
                .stream()
                .map(MarketingContactsService::toSubmission)
                .collect(Collectors.toList());

        List<Appointment> appointments = raw.squareCustomerId() == null ? List.of() : fetchAppointments(raw.squareCustomerId());

        return new Contact(
                raw.id().toString(),
                raw.givenName(),
                raw.phoneNumber(),
                raw.emailAddress(),
                raw.originalTrafficSource(),
                raw.marketingTrafficSource(),
                raw.utmSource(),
                raw.utmMedium(),
                raw.utmCampaign(),
                raw.landingPageSlug(),
                raw.variantName(),
                raw.deviceType(),
                raw.osName(),
                raw.osVersion(),
                raw.browserName(),
                raw.browserVersion(),
                raw.smsMarketingConsent(),
                raw.emailMarketingConsent(),
                squareProfileUrl,
                submissions,
                appointments,
                raw.createdAt(),
                raw.updatedAt()
        );
    }

    /** Best-effort: if Square is unreachable, the contact's own data still renders with an
     * empty appointments list rather than breaking the whole page.
     */
    private List<Appointment> fetchAppointments(String squareCustomerId) {
        try {
            List<Booking> bookings = square.bookingsForCustomer(squareCustomerId);
            if (bookings.isEmpty()) return List.of();

            Map<String, String> memberNames = new HashMap<>();
            for (TeamMember tm : square.allTeamMembers()) memberNames.put(tm.id(), tm.fullName());

            List<String> variationIds = bookings.stream()
                    .flatMap(b -> b.appointmentSegments() == null ? Stream.<AppointmentSegment>empty() : b.appointmentSegments().stream())
                    .map(AppointmentSegment::serviceVariationId)
                    .filter(Objects::nonNull)
                    .toList();
            Map<String, String> serviceNames = square.catalogNames(variationIds);
            Map<String, BigDecimal> servicePrices = square.catalogPrices(variationIds);

            // Only bookings that came through our own funnel have a matching submission row —
            // this is what surfaces traffic source/device/OS/submission time per appointment.
            Map<String, MarketingContactsRepository.RawAppointmentSubmission> submissionsByBookingId =
                    repository.findSubmissionsByBookingIds(bookings.stream().map(Booking::id).toList());

            return bookings.stream()
                    .map(b -> toAppointment(b, memberNames, serviceNames, servicePrices, submissionsByBookingId.get(b.id())))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch Square appointment history for customer {}", squareCustomerId, ex);
            return List.of();
        }
    }

    private static Appointment toAppointment(
            Booking booking,
            Map<String, String> memberNames,
            Map<String, String> serviceNames,
            Map<String, BigDecimal> servicePrices,
            MarketingContactsRepository.RawAppointmentSubmission submission
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
                artistName,
                submission == null ? null : submission.trafficSource(),
                submission == null ? null : submission.deviceType(),
                submission == null ? null : submission.osName(),
                submission == null ? null : submission.osVersion(),
                submission == null ? null : submission.browserName(),
                submission == null ? null : submission.occurredAt()
        );
    }

    private static Submission toSubmission(MarketingContactsRepository.RawSubmission raw) {
        return new Submission(
                raw.submissionType(),
                raw.occurredAt(),
                raw.landingPageSlug(),
                raw.variantName(),
                raw.trafficSource(),
                raw.utmSource(),
                raw.utmMedium(),
                raw.utmCampaign(),
                raw.serviceName(),
                raw.price()
        );
    }
}
