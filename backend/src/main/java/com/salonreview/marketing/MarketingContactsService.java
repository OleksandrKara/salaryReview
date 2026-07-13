package com.salonreview.marketing;

import com.salonreview.domain.MarketingContactSquareLink;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.MarketingContactSquareLinkRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.BookingPayment;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Appointment;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import com.salonreview.web.dto.MarketingContactDto.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MarketingContactsService {

    private static final Logger log = LoggerFactory.getLogger(MarketingContactsService.class);

    // Square's actual customer profile deep link — confirmed correct after the previous
    // .../directory/{id} form (missing the /customer/ segment) was reported broken too.
    private static final String SQUARE_CUSTOMER_PROFILE_URL = "https://app.squareup.com/dashboard/customers/directory/customer/%s";

    private final MarketingContactsRepository repository;
    private final MarketingContactSquareLinkRepository squareLinks;
    private final SquareClient square;
    private final SquareMonthAggregator aggregator;
    private final SalonConfigRepository salonConfig;

    public MarketingContactsService(MarketingContactsRepository repository,
                                     MarketingContactSquareLinkRepository squareLinks,
                                     SquareClient square,
                                     SquareMonthAggregator aggregator,
                                     SalonConfigRepository salonConfig) {
        this.repository = repository;
        this.squareLinks = squareLinks;
        this.square = square;
        this.aggregator = aggregator;
        this.salonConfig = salonConfig;
    }

    /** Never throws: same "this app's health must never depend on the other service's
     * schema" guarantee as MarketingDashboardService.dashboard. Submissions and (when a Square
     * customer is known) appointment history are fetched eagerly for every contact here, rather
     * than lazily per-click, so the UI can show "no appointments"/"no submissions" without an
     * extra round trip — see the Contact record's field docs.
     */
    public MarketingContactDto contacts() {
        try {
            // Each contact with a known Square customer needs its own round trip(s) to Square
            // (toContact -> fetchAppointments) — parallelizing across contacts, on top of the
            // per-customer window fan-out inside SquareClient.bookingsForCustomer, is what keeps
            // this page from taking many seconds to load once there are more than a couple of
            // Square-linked contacts.
            List<Contact> contacts = repository.listAll().parallelStream()
                    .map(this::toContact)
                    .collect(Collectors.toList());
            return new MarketingContactDto(true, contacts);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while building contacts list", ex);
            return MarketingContactDto.unavailable();
        }
    }

    /**
     * "Sync appointments": for every lead that never got a square_customer_id through the tracked
     * booking flow (contacts.square_customer_id is null — no completed booking, or four-hand
     * request, went through the website), tries to resolve one anyway by phone number — the case
     * a manager followed up and booked them directly in Square, or the client eventually came back
     * and booked through some other channel entirely. A match is cached in
     * marketing_contact_square_link (this app's own table — marketing.contacts itself is never
     * written) so every ordinary page load keeps showing that lead's appointment/no-show/cancelled
     * history afterward, not just right after this runs.
     *
     * <p>Also busts the Square read cache first, so already-linked contacts' appointment statuses
     * (a booking that's since become NO_SHOW or CANCELLED) are refreshed too, not just up to
     * whatever the normal cache TTL happens to allow.
     */
    @Transactional
    public MarketingContactDto syncSquareLinks() {
        square.invalidate();
        List<MarketingContactsRepository.RawContact> raw = repository.listAll();
        for (MarketingContactsRepository.RawContact r : raw) {
            if (r.squareCustomerId() != null) continue; // already linked via the tracked flow
            if (squareLinks.findByPhoneNumber(r.phoneNumber()).isPresent()) continue; // resolved by an earlier sync
            List<String> candidates = square.customerIdsForPhone(r.phoneNumber());
            if (candidates.isEmpty()) continue;
            squareLinks.save(MarketingContactSquareLink.builder()
                    .phoneNumber(r.phoneNumber())
                    .squareCustomerId(candidates.get(0))
                    .lastSyncedAt(Instant.now())
                    .build());
        }
        return contacts();
    }

    /**
     * For the Overview dashboard's "bookings incl. manager follow-up" total: counts, per variant
     * name (matching how the dashboard already groups contactsCreated), contacts under this
     * landing page whose current real (non-cancelled) Square appointments include at least one
     * booking_id that {@code attributedBookingIds} (marketing.attribution for this page) doesn't
     * know about. That covers two real cases: a lead who never completed the tracked flow at all
     * but a manager booked them by phone (found via the Sync-cached link), and a lead whose
     * original tracked request was later cancelled and replaced by a different booking a manager
     * created directly in Square (their contacts row still has a square_customer_id, but that
     * particular new booking never went through our attribution recording).
     */
    public Map<String, Long> countFollowUpBookingsByVariant(String landingPageSlug, Instant statsSince, java.util.Set<String> attributedBookingIds) {
        // hasUncountedRealAppointment is a Square round trip per candidate contact — same
        // parallelization reasoning as contacts() above.
        return repository.listAll().parallelStream()
                .filter(r -> landingPageSlug.equals(r.landingPageSlug()))
                .filter(r -> statsSince == null || !r.createdAt().isBefore(statsSince))
                .filter(r -> hasUncountedRealAppointment(r, attributedBookingIds))
                .collect(Collectors.groupingBy(
                        r -> r.variantName() == null ? "" : r.variantName(),
                        Collectors.counting()));
    }

    private boolean hasUncountedRealAppointment(MarketingContactsRepository.RawContact raw, java.util.Set<String> attributedBookingIds) {
        String customerId = resolveSquareCustomerId(raw);
        if (customerId == null) return false;
        return fetchAppointments(customerId, raw.createdAt()).stream()
                .filter(a -> !isCancelled(a.status()))
                .anyMatch(a -> !attributedBookingIds.contains(a.bookingId()));
    }

    private static boolean isCancelled(String status) {
        return status != null && status.startsWith("CANCELLED");
    }

    // Falls back to a phone-resolved link (see syncSquareLinks) when this lead never completed
    // the tracked booking flow itself — a manager who booked them by phone, or a return visit
    // through some other channel, still resolves here once a sync has found the match.
    private String resolveSquareCustomerId(MarketingContactsRepository.RawContact raw) {
        return raw.squareCustomerId() != null
                ? raw.squareCustomerId()
                : squareLinks.findByPhoneNumber(raw.phoneNumber()).map(MarketingContactSquareLink::getSquareCustomerId).orElse(null);
    }

    private Contact toContact(MarketingContactsRepository.RawContact raw) {
        String effectiveSquareCustomerId = resolveSquareCustomerId(raw);

        String squareProfileUrl = effectiveSquareCustomerId == null
                ? null
                : String.format(SQUARE_CUSTOMER_PROFILE_URL, effectiveSquareCustomerId);

        List<Submission> submissions = repository.findSubmissionHistory(raw.phoneNumber())
                .stream()
                .map(MarketingContactsService::toSubmission)
                .collect(Collectors.toList());

        List<Appointment> appointments = effectiveSquareCustomerId == null
                ? List.of()
                : fetchAppointments(effectiveSquareCustomerId, raw.createdAt());

        return new Contact(
                raw.id().toString(),
                raw.givenName(),
                raw.phoneNumber(),
                raw.emailAddress(),
                raw.originalTrafficSource(),
                raw.marketingTrafficSource(),
                raw.channel(),
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
     * empty appointments list rather than breaking the whole page. {@code since} bounds how far
     * back to look for this customer's booking history — a real appointment can't predate the
     * moment this lead first appeared in our own funnel, so the contact's own createdAt is used
     * (see SquareClient#bookingsForCustomer for why an explicit bound is required at all).
     */
    private List<Appointment> fetchAppointments(String squareCustomerId, Instant since) {
        try {
            List<Booking> bookings = square.bookingsForCustomer(squareCustomerId, since);
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

            Map<String, BookingPayment> payments = paymentsForBookings(bookings);

            return bookings.stream()
                    .map(b -> toAppointment(b, memberNames, serviceNames, servicePrices,
                            submissionsByBookingId.get(b.id()), payments.get(b.id())))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch Square appointment history for customer {}", squareCustomerId, ex);
            return List.of();
        }
    }

    /** What was actually collected for this customer's already-past bookings, keyed by booking id
     * — reuses the same month-based payroll matching SquareMonthAggregator does (order/cash-note
     * matched to a booking by customer + service + date), so the Contacts tab shows the real
     * collected amount, not the catalog-price estimate {@code toAppointment}'s price field is.
     * Best-effort per month: a failure fetching one month's data (e.g. a transient Square error)
     * just leaves that month's bookings without payment info, same "never break the page"
     * philosophy as the rest of this service.
     */
    private Map<String, BookingPayment> paymentsForBookings(List<Booking> bookings) {
        Instant now = Instant.now();
        ZoneId zone = resolveZone();
        Set<YearMonth> months = bookings.stream()
                .map(Booking::startAt)
                .filter(Objects::nonNull)
                .map(Instant::parse)
                .filter(i -> i.isBefore(now)) // only a past appointment can have been paid already
                .map(i -> YearMonth.from(i.atZone(zone)))
                .collect(Collectors.toSet());
        if (months.isEmpty()) return Map.of();

        BigDecimal cutoff = priceCutoff();
        Map<String, BookingPayment> merged = new HashMap<>();
        for (YearMonth ym : months) {
            try {
                var agg = aggregator.aggregate(ym.getYear(), ym.getMonthValue(), cutoff);
                merged.putAll(SquareMonthAggregator.paymentsByBookingId(agg.services()));
            } catch (RuntimeException ex) {
                log.warn("Failed to fetch payment info for {}-{}", ym.getYear(), ym.getMonthValue(), ex);
            }
        }
        return merged;
    }

    private ZoneId resolveZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private BigDecimal priceCutoff() {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        return cfg.getServicePriceCutoff();
    }

    private static Appointment toAppointment(
            Booking booking,
            Map<String, String> memberNames,
            Map<String, String> serviceNames,
            Map<String, BigDecimal> servicePrices,
            MarketingContactsRepository.RawAppointmentSubmission submission,
            BookingPayment payment
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
                payment == null ? null : payment.channel(),
                payment == null ? null : payment.collected(),
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
