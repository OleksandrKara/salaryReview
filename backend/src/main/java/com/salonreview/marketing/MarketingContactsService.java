package com.salonreview.marketing;

import com.salonreview.domain.MarketingContactSquareLink;
import com.salonreview.domain.MarketingSyncStatus;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.MarketingContactSquareLinkRepository;
import com.salonreview.repo.MarketingSyncStatusRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MarketingContactsService {

    private static final Logger log = LoggerFactory.getLogger(MarketingContactsService.class);

    // Square's actual customer profile deep link — confirmed correct after the previous
    // .../directory/{id} form (missing the /customer/ segment) was reported broken too.
    private static final String SQUARE_CUSTOMER_PROFILE_URL = "https://app.squareup.com/dashboard/customers/directory/customer/%s";

    // See docs/CACHING.md / MarketingDashboardService's own CACHE_TTL — same 10-min TTL. contacts()
    // is the most Square-call-heavy of the marketing tabs (one round trip per Square-linked
    // contact), so it benefits the most from this.
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String CONTACTS_CACHE_KEY = "contacts";

    private final MarketingContactsRepository repository;
    private final MarketingContactSquareLinkRepository squareLinks;
    private final SquareClient square;
    private final SquareMonthAggregator aggregator;
    private final SalonConfigRepository salonConfig;
    private final MarketingSyncStatusRepository syncStatus;
    private final TtlCache cache = new TtlCache();

    public MarketingContactsService(MarketingContactsRepository repository,
                                     MarketingContactSquareLinkRepository squareLinks,
                                     SquareClient square,
                                     SquareMonthAggregator aggregator,
                                     SalonConfigRepository salonConfig,
                                     MarketingSyncStatusRepository syncStatus) {
        this.repository = repository;
        this.squareLinks = squareLinks;
        this.square = square;
        this.aggregator = aggregator;
        this.salonConfig = salonConfig;
        this.syncStatus = syncStatus;
    }

    /** When "Sync appointments" was last actually invoked — null if never (see V50). */
    public Instant lastSyncedAt() {
        return syncStatus.getSingleton().getLastSyncedAt();
    }

    /** One customer's submission + appointment history, resolved by Square customer id rather
     * than by contact row — for the Ads Report breakdown drill-down's per-row "expand" (see
     * MarketingAdsReportController), which only knows a completed/upcoming row's customerId, not
     * which marketing.contacts row it came from. Fetched lazily, one customer at a time, on the
     * owner's own click — see design.md's "fetch on click" decision for that capability. Empty
     * (not an error) if the schema is unreachable or no contact resolves to this customer id.
     */
    /** One contact's profile + submission/appointment history, resolved by phone number — for the
     * manager conversation view's contact info sidebar (see MessagesView), which only knows the
     * phone number a thread is keyed by. Empty (not an error) if this number never went through
     * the tracked capture flow (e.g. a checkout-review/lead-follow-up text sent purely from
     * Square/booking data with no matching marketing.contacts row) or if the schema is
     * unreachable. */
    public Optional<Contact> contactByPhone(String phoneNumber) {
        try {
            return repository.findByPhoneNumber(phoneNumber).map(this::toContact);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while resolving contact for phone {}", phoneNumber, ex);
            return Optional.empty();
        }
    }

    public Optional<Contact> contactByCustomerId(String squareCustomerId) {
        try {
            return repository.listAll().stream()
                    .filter(r -> squareCustomerId.equals(resolveSquareCustomerId(r)))
                    .findFirst()
                    .map(this::toContact);
        } catch (DataAccessException ex) {
            log.warn("Marketing schema unavailable while resolving contact for customer {}", squareCustomerId, ex);
            return Optional.empty();
        }
    }

    /** Never throws: same "this app's health must never depend on the other service's
     * schema" guarantee as MarketingDashboardService.dashboard. Submissions and (when a Square
     * customer is known) appointment history are fetched eagerly for every contact here, rather
     * than lazily per-click, so the UI can show "no appointments"/"no submissions" without an
     * extra round trip — see the Contact record's field docs.
     */
    public MarketingContactDto contacts() {
        return cache.get(CONTACTS_CACHE_KEY, CACHE_TTL, this::computeContacts);
    }

    private MarketingContactDto computeContacts() {
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
     *
     * <p>Records this run's time in marketing_sync_status regardless of whether any new link was
     * actually found — a no-op run (everything already linked) is still a real sync the owner
     * should be able to trust "Last synced: just now" for, not a stale timestamp left over from
     * whenever a new link last happened to be created.
     */
    @Transactional
    public MarketingContactDto syncSquareLinks() {
        square.invalidate();
        cache.invalidateAll();
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
        MarketingSyncStatus status = syncStatus.getSingleton();
        status.setLastSyncedAt(Instant.now());
        syncStatus.save(status);
        // Bypasses the cache deliberately — this sync just ran live, so the result must reflect
        // it immediately; contacts() would otherwise be a legitimate cache miss anyway (just
        // cleared above), but computing directly avoids relying on that as an implementation detail.
        MarketingContactDto fresh = computeContacts();
        cache.get(CONTACTS_CACHE_KEY, CACHE_TTL, () -> fresh);
        return fresh;
    }

    /** Backs the global "Sync now" button (see SquareSyncController) — so forcing a fresh Square
     * pull also busts this service's own cached contacts list. */
    public void invalidateCache() {
        cache.invalidateAll();
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
     *
     * <p>{@code convertedCustomerIds} (every real customer who already has a genuine attributed
     * conversion on this page — see MarketingDashboardService#conversionsByVariant) excludes that
     * customer from follow-up counting entirely, not just their one already-tracked booking_id. A
     * customer who converted on-page and later gets any other real appointment — a normal repeat
     * visit booked directly in Square, a reschedule that landed on a new booking_id, anything —
     * isn't a manager "follow-up" story; without this exclusion that other booking still isn't in
     * {@code attributedBookingIds} (it's a different booking_id), so it would double-count an
     * already-converted client as a fresh follow-up too.
     */
    public Map<String, Long> countFollowUpBookingsByVariant(
            String landingPageSlug, Instant statsSince, Instant periodTo,
            java.util.Set<String> attributedBookingIds, java.util.Set<String> convertedCustomerIds) {
        // uncountedAppointments is a Square round trip per candidate contact — same
        // parallelization reasoning as contacts() above. Grouped by resolved customer id first,
        // keeping only the earliest qualifying contact row per real client, before counting by
        // variant — the same lead can otherwise show up as two separate marketing.contacts rows
        // (e.g. they filled the form again on a later visit), which would double-count one real
        // follow-up as two. A repeat contact row surfacing the exact same follow-up doesn't
        // deserve a second count just because the client happened to re-submit.
        return repository.listAll().parallelStream()
                .filter(r -> landingPageSlug.equals(r.landingPageSlug()))
                .filter(r -> statsSince == null || !r.createdAt().isBefore(statsSince))
                .filter(r -> periodTo == null || r.createdAt().isBefore(periodTo))
                .filter(r -> !convertedCustomerIds.contains(resolveSquareCustomerId(r)))
                .filter(r -> !uncountedAppointments(r, attributedBookingIds).isEmpty())
                .collect(Collectors.toMap(
                        this::resolveSquareCustomerId,
                        r -> r,
                        (a, b) -> a.createdAt().isBefore(b.createdAt()) ? a : b))
                .values().stream()
                .collect(Collectors.groupingBy(
                        r -> r.variantName() == null ? "" : r.variantName(),
                        Collectors.counting()));
    }

    /** booking_id -> resolved Square customer id (see resolveSquareCustomerId), for every contact
     * under this landing page that completed a booking through the tracked flow — used to
     * collapse a customer's attributed bookings down to just their first genuine conversion on
     * this page (see MarketingDashboardService), the same resolution countFollowUpBookingsByVariant
     * above already trusts. A contact with no square_booking_id never completed the tracked flow,
     * so it's irrelevant here and skipped; one whose customer id can't be resolved at all is
     * skipped too (the caller treats an unresolvable booking as its own, uncollapsed conversion
     * rather than silently dropping it). */
    public Map<String, String> resolveCustomerIdsByBookingId(String landingPageSlug) {
        Map<String, String> byBooking = new java.util.HashMap<>();
        for (MarketingContactsRepository.RawContact r : repository.listAll()) {
            if (!landingPageSlug.equals(r.landingPageSlug()) || r.squareBookingId() == null) continue;
            String customerId = resolveSquareCustomerId(r);
            if (customerId != null) byBooking.put(r.squareBookingId(), customerId);
        }
        return byBooking;
    }

    /** Pairs a follow-up {@link Appointment} with the Square customer id it belongs to —
     * {@code Appointment} itself carries no customer reference (it's normally nested inside one
     * {@code Contact} already), but {@link MarketingAnalyticsService} needs the id to check
     * freshness and resolve a display name, the same way it already does for tracked-flow
     * bookings. */
    public record FollowUpAppointment(String customerId, Appointment appointment) {}

    /** Page-scoped sibling of {@link #countFollowUpBookingsByVariant} — same live Square
     * resolution, but returns the full appointment records (not grouped by variant, not just a
     * count) so Ads Report can fold their real/catalog price and date straight into the same
     * revenueCollected/anticipatedRevenue figures the tracked-flow path already computes. A
     * booking already in {@code attributedBookingIds} is never included here — it's already
     * counted via that path, never double-counted as a follow-up too. {@code convertedCustomerIds}
     * carries the same customer-level exclusion {@link #countFollowUpBookingsByVariant} applies —
     * see its doc comment for why a booking_id-only check isn't enough.
     */
    public List<FollowUpAppointment> followUpAppointments(
            String landingPageSlug, Instant statsSince,
            java.util.Set<String> attributedBookingIds, java.util.Set<String> convertedCustomerIds) {
        return repository.listAll().parallelStream()
                .filter(r -> landingPageSlug.equals(r.landingPageSlug()))
                .filter(r -> statsSince == null || !r.createdAt().isBefore(statsSince))
                .filter(r -> !convertedCustomerIds.contains(resolveSquareCustomerId(r)))
                .flatMap(r -> {
                    String customerId = resolveSquareCustomerId(r);
                    return uncountedAppointments(r, attributedBookingIds).stream()
                            .map(a -> new FollowUpAppointment(customerId, a));
                })
                .toList();
    }

    private List<Appointment> uncountedAppointments(MarketingContactsRepository.RawContact raw, java.util.Set<String> attributedBookingIds) {
        String customerId = resolveSquareCustomerId(raw);
        if (customerId == null) return List.of();
        return fetchAppointments(customerId, raw.createdAt()).stream()
                .filter(a -> !isCancelled(a.status()))
                .filter(a -> !attributedBookingIds.contains(a.bookingId()))
                .toList();
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
