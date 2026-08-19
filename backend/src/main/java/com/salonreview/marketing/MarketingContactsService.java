package com.salonreview.marketing;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.MarketingContactSquareLink;
import com.salonreview.domain.MarketingSyncStatus;
import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.MarketingContactSquareLinkRepository;
import com.salonreview.repo.MarketingSyncStatusRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.util.TtlCache;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.sms.CheckoutReviewLinks;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.BookingPayment;
import com.salonreview.util.PhoneNumbers;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Appointment;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import com.salonreview.web.dto.MarketingContactDto.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
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
    private static final String CONTACTS_CACHE_KEY_PREFIX = "contacts:";

    // 2026-08-19: MessagesNotifierIcon polls /api/sms/conversations every 25s for the unread badge
    // (see SmsActivityController#conversations), and every poll re-resolved every conversation's
    // phone number via a fresh Square batch call with no caching at all — for a salon with hundreds
    // of conversations, that's 3 Square API calls every 25 seconds from just one open browser tab,
    // which started tripping Square's own rate limit live. Same TTL as CACHE_TTL above; keyed by
    // the actual phone-number set so a genuinely new conversation still gets a fresh lookup instead
    // of waiting out a stale cache entry keyed only by business.
    private static final Duration NAMES_CACHE_TTL = Duration.ofMinutes(10);
    private static final String NAMES_CACHE_KEY_PREFIX = "names:";

    // See #contactFromLivePhoneLookup and MarketingAnalyticsService#BOOKING_HISTORY_LOOKBACK for
    // the same rationale: with no contact createdAt to anchor the Square scan on, this caps it at
    // a generous window rather than paying for an unbounded "their whole history" lookup.
    private static final Duration LIVE_LOOKUP_HISTORY_WINDOW = Duration.ofDays(400);

    private final MarketingContactsRepository repository;
    private final MarketingContactSquareLinkRepository squareLinks;
    private final SquareClientProvider squareClientProvider;
    private final SquareMonthAggregator aggregator;
    private final SalonConfigRepository salonConfig;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final MarketingSyncStatusRepository syncStatus;
    private final RebookingProperties rebookingProperties;
    private final SmsMessageLogService smsMessageLogService;
    private final ProviderVisitRepository providerVisits;
    private final int vipVisitThreshold;
    private final TtlCache cache = new TtlCache();

    public MarketingContactsService(MarketingContactsRepository repository,
                                     MarketingContactSquareLinkRepository squareLinks,
                                     SquareClientProvider squareClientProvider,
                                     SquareMonthAggregator aggregator,
                                     SalonConfigRepository salonConfig,
                                     com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                     MarketingSyncStatusRepository syncStatus,
                                     RebookingProperties rebookingProperties,
                                     SmsMessageLogService smsMessageLogService,
                                     ProviderVisitRepository providerVisits,
                                     @Value("${vip.visit-threshold:4}") int vipVisitThreshold) {
        this.repository = repository;
        this.squareLinks = squareLinks;
        this.squareClientProvider = squareClientProvider;
        this.aggregator = aggregator;
        this.salonConfig = salonConfig;
        this.currentBusinessContext = currentBusinessContext;
        this.syncStatus = syncStatus;
        this.rebookingProperties = rebookingProperties;
        this.smsMessageLogService = smsMessageLogService;
        this.providerVisits = providerVisits;
        this.vipVisitThreshold = vipVisitThreshold;
    }

    /** Both link-engagement pairs (Google review + feedback form) for one phone number, in the
     * order the {@link Contact} record expects them — see #toContact and
     * #contactFromLivePhoneLookup, its two callers. */
    private record ContactLinkEngagement(
            SmsMessageLogService.LinkEngagement googleReview, SmsMessageLogService.LinkEngagement feedbackForm) {}

    private ContactLinkEngagement linkEngagementFor(String phoneNumber) {
        Long businessId = currentBusinessContext.id();
        return new ContactLinkEngagement(
                smsMessageLogService.linkEngagement(businessId, phoneNumber, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET),
                smsMessageLogService.linkEngagement(businessId, phoneNumber, CheckoutReviewLinks.FEEDBACK_FORM_TARGET));
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
     * phone number a thread is keyed by. Falls back to {@link #contactFromLivePhoneLookup} when
     * this number never went through the tracked capture flow at all (e.g. a checkout-review text
     * sent purely from Square/booking data, or a real Square customer who only ever booked directly
     * — reported live: "Lily Frei" had a conversation thread and a name/profile-link already
     * resolved via {@link #resolveDisplayNames}'s own live-phone-lookup fallback, but no
     * appointments here, since this method previously gave up the moment marketing.contacts had no
     * row — same fallback resolveDisplayNames/LeadFollowUpScheduler already use, just extended to
     * build the full contact rather than only a name). Empty only if the schema is unreachable or
     * no Square customer resolves either way. */
    public Optional<Contact> contactByPhone(String phoneNumber) {
        try {
            Optional<Contact> tracked = repository.findByPhoneNumber(phoneNumber, currentBusinessContext.id())
                    .map(r -> toContact(r, visitCountsByCustomerId()));
            return tracked.isPresent() ? tracked : contactFromLivePhoneLookup(phoneNumber);
        } catch (DataAccessException | RestClientException ex) {
            log.warn("Marketing schema or Square unavailable while resolving contact for phone {}", phoneNumber, ex);
            return Optional.empty();
        }
    }

    /** Builds a Contact for a phone number with no marketing.contacts row at all, purely from a
     * live Square phone lookup — same resolution ladder's last resort as
     * {@link #resolveDisplayNames} and {@code LeadFollowUpScheduler#hasUpcomingAppointment}. Every
     * capture-flow-only field (traffic source, UTMs, device info, createdAt/updatedAt) is null —
     * there was never a capture event to record them from. {@code since} is capped at
     * {@link #LIVE_LOOKUP_HISTORY_WINDOW} back, same rationale as
     * MarketingAnalyticsService#BOOKING_HISTORY_LOOKBACK: with no contact createdAt to anchor on,
     * an unbounded "their whole history" Square scan isn't free, so this caps it at a generous
     * window instead. Empty if Square has no customer for this phone either. */
    private Optional<Contact> contactFromLivePhoneLookup(String phoneNumber) {
        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        List<String> candidates = square.customerIdsForPhone(phoneNumber);
        if (candidates.isEmpty()) return Optional.empty();
        String customerId = candidates.get(0);

        String givenName = square.customerGivenNames(List.of(customerId)).get(customerId);
        String familyName = square.customerFamilyNames(List.of(customerId)).get(customerId);
        String squareProfileUrl = String.format(SQUARE_CUSTOMER_PROFILE_URL, customerId);
        boolean smsMarketingConsent = hasSquareConsentSegment(customerId);

        List<Submission> submissions = repository.findSubmissionHistory(phoneNumber)
                .stream().map(MarketingContactsService::toSubmission).collect(Collectors.toList());
        Instant since = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minus(LIVE_LOOKUP_HISTORY_WINDOW);
        List<Appointment> appointments = fetchAppointments(customerId, since);
        ContactLinkEngagement engagement = linkEngagementFor(phoneNumber);
        long visitCount = visitCountsByCustomerId().getOrDefault(customerId, 0L);

        return Optional.of(new Contact(
                "square:" + customerId, givenName, familyName, phoneNumber, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                smsMarketingConsent, false,
                squareProfileUrl, submissions, appointments,
                null, null,
                engagement.googleReview().sentAt(), engagement.googleReview().clickedAt(),
                engagement.feedbackForm().sentAt(), engagement.feedbackForm().clickedAt(),
                visitCount >= vipVisitThreshold, (int) visitCount
        ));
    }

    public Optional<Contact> contactByCustomerId(String squareCustomerId) {
        try {
            Map<String, Long> visitCounts = visitCountsByCustomerId();
            return repository.listAllForBusiness(currentBusinessContext.id()).stream()
                    .filter(r -> squareCustomerId.equals(resolveSquareCustomerId(r)))
                    .findFirst()
                    .map(r -> toContact(r, visitCounts));
        } catch (DataAccessException | RestClientException ex) {
            log.warn("Marketing schema or Square unavailable while resolving contact for customer {}", squareCustomerId, ex);
            return Optional.empty();
        }
    }

    /** Given name + optional family name + merged SMS-marketing-consent + Square profile link,
     * for every phone number in a Messages conversation list, in one batch (see
     * SmsActivityController#conversations). Name resolution ladder mirrors LeadFollowUpScheduler's
     * own fallback order: marketing.contacts' own given_name/square_customer_id columns first,
     * then marketing_contact_square_link for a customer id when the row itself has none, then —
     * only for phone numbers with no marketing.contacts row at all — a live Square phone lookup,
     * same as LeadFollowUpScheduler. Family name is always Square-resolved (marketing.contacts has
     * no such column, see Contact#familyName). Consent is true if *either* source says so — the
     * same "either source" rule SameDayRebookingScheduler#hasConsent already uses for sending:
     * marketing.contacts.sms_marketing_consent, or the customer belonging to Square's own
     * consentSegmentId (RebookingProperties) — so this page never shows "no consent" for someone
     * the automations would in fact be allowed to text. squareProfileUrl uses the same customer id
     * this method already resolved via the fallback ladder above — deliberately more permissive
     * than {@link #contactByPhone}'s own squareProfileUrl, which requires a marketing.contacts row
     * to exist at all (a checkout-review text sent purely from Square payment data has none, but
     * can still resolve a customer id here). Best-effort throughout: a phone number with nothing
     * resolvable still gets an entry (all-null/false), so a caller doesn't need a separate
     * null-check just for consent. {@code vip} is the same distinct-day visit-count threshold
     * check as MarketingContactDto.Contact#vip (see #visitCountsByCustomerId) — always false
     * when no Square customer id was resolved above. */
    public Map<String, ContactNameInfo> resolveDisplayNames(Collection<String> phoneNumbers) {
        if (phoneNumbers.isEmpty()) {
            return Map.of();
        }
        String key = NAMES_CACHE_KEY_PREFIX + currentBusinessContext.id() + ":" + phoneNumberSetKey(phoneNumbers);
        try {
            // A failed load must never be cached — that would freeze "no names" in place for the
            // full TTL even after Square recovers, so the exception is caught out here, outside
            // cache.get(), letting TtlCache's own put() never run on a failed loader call.
            return cache.get(key, NAMES_CACHE_TTL, () -> computeDisplayNames(phoneNumbers));
        } catch (DataAccessException | RestClientException ex) {
            log.warn("Marketing schema or Square unavailable while resolving display names for {} phone numbers",
                    phoneNumbers.size(), ex);
            return Map.of();
        }
    }

    /** Stable regardless of input order/duplicates/formatting — same normalization
     * {@link #resolveDisplayNames}'s own lookup already keys on, so two calls for "the same"
     * conversation list (e.g. two consecutive unread-badge polls) hit the same cache entry even if
     * the underlying query returned rows in a different order. */
    private static String phoneNumberSetKey(Collection<String> phoneNumbers) {
        return phoneNumbers.stream().map(PhoneNumbers::last10Digits).distinct().sorted()
                .collect(Collectors.joining(","));
    }

    private Map<String, ContactNameInfo> computeDisplayNames(Collection<String> phoneNumbers) {
        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        // Keyed by last10Digits, not the raw phone string — marketing.contacts' own stored
        // format for this row isn't guaranteed to match the format phoneNumbers is holding
        // (see PhoneNumbers' own doc comment and MarketingContactsRepository#findNamesByPhoneNumbers).
        Map<String, MarketingContactsRepository.PhoneName> byPhone = repository
                .findNamesByPhoneNumbers(phoneNumbers).stream()
                .collect(Collectors.toMap(MarketingContactsRepository.PhoneName::last10, r -> r, (a, b) -> a));

        Map<String, String> customerIdByPhone = new HashMap<>();
        for (String phone : phoneNumbers) {
            MarketingContactsRepository.PhoneName row = byPhone.get(PhoneNumbers.last10Digits(phone));
            if (row != null) {
                String customerId = row.squareCustomerId() != null
                        ? row.squareCustomerId()
                        : squareLinks.findByPhoneNumber(phone).map(MarketingContactSquareLink::getSquareCustomerId).orElse(null);
                if (customerId != null) {
                    customerIdByPhone.put(phone, customerId);
                }
            } else {
                // No marketing.contacts row at all (e.g. a checkout-review/rebooking text
                // sent from Square data with no tracked capture) — last-resort live lookup,
                // same fallback LeadFollowUpScheduler uses.
                List<String> candidates = square.customerIdsForPhone(phone);
                if (!candidates.isEmpty()) {
                    customerIdByPhone.put(phone, candidates.get(0));
                }
            }
        }

        Map<String, String> givenFromSquare = square.customerGivenNames(customerIdByPhone.values());
        Map<String, String> familyFromSquare = square.customerFamilyNames(customerIdByPhone.values());
        Map<String, List<String>> segmentsByCustomer = square.customerSegmentIdsBatch(customerIdByPhone.values());
        if (segmentsByCustomer == null) {
            segmentsByCustomer = Map.of();
        }
        String consentSegmentId = rebookingProperties.getConsentSegmentId();
        Map<String, Long> visitCounts = visitCountsByCustomerId();

        Map<String, ContactNameInfo> result = new HashMap<>();
        for (String phone : phoneNumbers) {
            MarketingContactsRepository.PhoneName row = byPhone.get(PhoneNumbers.last10Digits(phone));
            String customerId = customerIdByPhone.get(phone);
            String givenName = row != null && row.givenName() != null && !row.givenName().isBlank()
                    ? row.givenName()
                    : (customerId != null ? givenFromSquare.get(customerId) : null);
            String familyName = customerId != null ? familyFromSquare.get(customerId) : null;

            boolean ownConsent = row != null && Boolean.TRUE.equals(row.smsMarketingConsent());
            boolean squareConsent = customerId != null
                    && consentSegmentId != null && !consentSegmentId.isBlank()
                    && segmentsByCustomer.getOrDefault(customerId, List.of()).contains(consentSegmentId);

            String squareProfileUrl = customerId == null
                    ? null
                    : String.format(SQUARE_CUSTOMER_PROFILE_URL, customerId);

            Long visitCount = customerId == null ? null : visitCounts.getOrDefault(customerId, 0L);
            boolean vip = visitCount != null && visitCount >= vipVisitThreshold;

            result.put(phone, new ContactNameInfo(givenName, familyName, ownConsent || squareConsent, squareProfileUrl,
                    vip, visitCount == null ? null : visitCount.intValue()));
        }
        return result;
    }

    /** Nullable given/family name plus merged SMS-marketing-consent and Square profile link for
     * one phone number — see #resolveDisplayNames. {@code vip}/{@code visitCount} mirror
     * MarketingContactDto.Contact's own fields (see #visitCountsByCustomerId) — always
     * false/null when no Square customer could be resolved for this phone number at all. */
    public record ContactNameInfo(String givenName, String familyName, boolean smsConsent, String squareProfileUrl,
                                   boolean vip, Integer visitCount) {}

    /** Never throws: same "this app's health must never depend on the other service's
     * schema" guarantee as MarketingDashboardService.dashboard. Submissions are fetched eagerly for
     * every contact here (cheap — our own DB), so the UI can show "no submissions" without an extra
     * round trip. Appointment history/family name are deliberately NOT fetched eagerly here
     * (2026-08-19, see {@link #toContact(MarketingContactsRepository.RawContact, Map, boolean)}) —
     * that's a real Square round trip per Square-linked contact, and doing it for the full list on
     * every cache miss was hammering Square's rate limit live; the frontend calls
     * {@link #enrichContacts} for just the rows actually scrolled into view instead.
     */
    public MarketingContactDto contacts() {
        return cache.get(CONTACTS_CACHE_KEY_PREFIX + currentBusinessContext.id(), CACHE_TTL, this::computeContacts);
    }

    private MarketingContactDto computeContacts() {
        try {
            // Computed once up front (a single query against our own DB, no Square round trip)
            // rather than per-contact inside the parallelStream below — every contact shares the
            // same visit ledger, so there's no reason to recount it once per row.
            Map<String, Long> visitCounts = visitCountsByCustomerId();
            // Each contact with a known Square customer needs its own round trip(s) to Square
            // (toContact -> fetchAppointments) — parallelizing across contacts, on top of the
            // per-customer window fan-out inside SquareClient.bookingsForCustomer, is what keeps
            // this page from taking many seconds to load once there are more than a couple of
            // Square-linked contacts.
            //
            // toContact() -> fetchAppointments() -> paymentsForBookings() -> priceCutoff() reaches
            // CurrentBusinessContext.id(), but parallelStream() runs each element on a common
            // ForkJoinPool worker thread — a ThreadLocal set on the calling thread doesn't carry
            // over there. Resolve it once here and re-establish it explicitly per element, same fix
            // as OwnerOverviewService/RevenuePulseService's identical async ThreadLocal loss.
            Long businessId = currentBusinessContext.id();
            List<Contact> contacts = repository.listAllForBusiness(businessId).parallelStream()
                    .map(r -> currentBusinessContext.runAsAndGet(businessId, () -> toContact(r, visitCounts, false)))
                    .collect(Collectors.toList());
            return new MarketingContactDto(true, contacts);
        } catch (DataAccessException | RestClientException ex) {
            log.warn("Marketing schema or Square unavailable while building contacts list", ex);
            return MarketingContactDto.unavailable();
        }
    }

    /** {@code familyName}/{@code appointments} for exactly the given contact ids — the follow-up
     * call the frontend makes for whichever rows have actually scrolled into view (see
     * {@link #toContact(MarketingContactsRepository.RawContact, Map, boolean)}'s doc for why the
     * bulk {@link #contacts} response no longer includes these itself). Not cached: unlike
     * {@link #contacts}, each call is already scoped to a small, caller-chosen batch (the frontend
     * only asks once per contact, when it first becomes visible), so there's no repeated-poll
     * pattern here to protect Square from the way {@link #resolveDisplayNames} needed to. A
     * contact id that doesn't exist (or belongs to another business — {@link
     * MarketingContactsRepository#findByIds} has no business scoping of its own, same as every
     * other read in this class, since marketing.contacts has no business_id column yet) is simply
     * absent from the result rather than an error. */
    public record ContactEnrichment(String familyName, List<Appointment> appointments) {}

    public Map<String, ContactEnrichment> enrichContacts(Collection<String> contactIds) {
        if (contactIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<java.util.UUID> ids = contactIds.stream().map(java.util.UUID::fromString).toList();
            List<MarketingContactsRepository.RawContact> raws = repository.findByIds(ids, currentBusinessContext.id());
            Long businessId = currentBusinessContext.id();
            return raws.parallelStream()
                    .collect(Collectors.toMap(
                            r -> r.id().toString(),
                            r -> currentBusinessContext.runAsAndGet(businessId, () -> enrichOne(r))));
        } catch (DataAccessException | RestClientException | IllegalArgumentException ex) {
            log.warn("Marketing schema or Square unavailable while enriching {} contacts", contactIds.size(), ex);
            return Map.of();
        }
    }

    private ContactEnrichment enrichOne(MarketingContactsRepository.RawContact raw) {
        String customerId = resolveSquareCustomerId(raw);
        if (customerId == null) {
            return new ContactEnrichment(null, List.of());
        }
        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        String familyName = square.customerFamilyNames(List.of(customerId)).get(customerId);
        List<Appointment> appointments = fetchAppointments(customerId, raw.createdAt());
        return new ContactEnrichment(familyName, appointments);
    }

    /** Distinct-day visit counts per Square customer id, backing the VIP badge/filter (see #toContact
     * and #vipVisitThreshold) — counted as distinct service dates, not raw provider_visit rows,
     * since two providers seeing the same customer on the same day (e.g. a 4-hand booking) is one
     * salon visit, not two. Recomputed on demand rather than cached: it's one cheap in-process
     * query against our own DB (same "load once, compute in memory" pattern
     * MarketingAnalyticsService already uses for this table), not a Square round trip. */
    private Map<String, Long> visitCountsByCustomerId() {
        return providerVisits.findAllByBusinessIdOrderByServiceDateAsc(currentBusinessContext.id()).stream()
                .collect(Collectors.groupingBy(
                        ProviderVisit::getCustomerId,
                        Collectors.mapping(ProviderVisit::getServiceDate, Collectors.toSet())))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue().size()));
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
        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        square.invalidate();
        cache.invalidateAll();
        List<MarketingContactsRepository.RawContact> raw = repository.listAllForBusiness(currentBusinessContext.id());
        for (MarketingContactsRepository.RawContact r : raw) {
            if (r.squareCustomerId() != null) continue; // already linked via the tracked flow
            if (squareLinks.findByPhoneNumber(r.phoneNumber()).isPresent()) continue; // resolved by an earlier sync
            List<String> candidates = square.customerIdsForPhone(r.phoneNumber());
            if (candidates.isEmpty()) continue;
            squareLinks.save(MarketingContactSquareLink.builder()
                    .phoneNumber(PhoneNumbers.normalize(r.phoneNumber()))
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
        cache.get(CONTACTS_CACHE_KEY_PREFIX + currentBusinessContext.id(), CACHE_TTL, () -> fresh);
        return fresh;
    }

    /** Backs the "Sync now" button (see SquareSyncController) — busts only this business's own
     * cached contacts list and cached name resolutions, not every business's. */
    public void invalidateCache() {
        String businessId = currentBusinessContext.id().toString();
        cache.invalidateWhere(k -> k.equals(CONTACTS_CACHE_KEY_PREFIX + businessId)
                || k.startsWith(NAMES_CACHE_KEY_PREFIX + businessId + ":"));
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
        //
        // uncountedAppointments() -> fetchAppointments() -> priceCutoff() needs CurrentBusinessContext
        // on whatever thread it runs on — parallelStream()'s worker threads don't inherit it. Same
        // fix as computeContacts() above.
        Long businessId = currentBusinessContext.id();
        return repository.listAllForBusiness(businessId).parallelStream()
                .filter(r -> landingPageSlug.equals(r.landingPageSlug()))
                .filter(r -> statsSince == null || !r.createdAt().isBefore(statsSince))
                .filter(r -> periodTo == null || r.createdAt().isBefore(periodTo))
                .filter(r -> !convertedCustomerIds.contains(resolveSquareCustomerId(r)))
                .filter(r -> currentBusinessContext.runAsAndGet(businessId,
                        () -> !uncountedAppointments(r, attributedBookingIds).isEmpty()))
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
        for (MarketingContactsRepository.RawContact r : repository.listAllForBusiness(currentBusinessContext.id())) {
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
        // Same CurrentBusinessContext-across-parallelStream fix as countFollowUpBookingsByVariant.
        Long businessId = currentBusinessContext.id();
        return repository.listAllForBusiness(businessId).parallelStream()
                .filter(r -> landingPageSlug.equals(r.landingPageSlug()))
                .filter(r -> statsSince == null || !r.createdAt().isBefore(statsSince))
                .filter(r -> !convertedCustomerIds.contains(resolveSquareCustomerId(r)))
                .flatMap(r -> currentBusinessContext.runAsAndGet(businessId, () -> {
                    String customerId = resolveSquareCustomerId(r);
                    return uncountedAppointments(r, attributedBookingIds).stream()
                            .map(a -> new FollowUpAppointment(customerId, a));
                }))
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

    /** Whether this Square customer belongs to the consent-bearing segment configured in
     * {@link RebookingProperties#getConsentSegmentId()} — see #resolveDisplayNames and #toContact
     * for the two callers that fold this into a merged consent flag. False (not an error) for a
     * null customer id or an unconfigured segment. */
    private boolean hasSquareConsentSegment(String squareCustomerId) {
        String consentSegmentId = rebookingProperties.getConsentSegmentId();
        if (squareCustomerId == null || consentSegmentId == null || consentSegmentId.isBlank()) {
            return false;
        }
        List<String> segments = squareClientProvider.forBusiness(currentBusinessContext.id())
                .customerSegmentIds(squareCustomerId);
        return segments != null && segments.contains(consentSegmentId);
    }

    private Contact toContact(MarketingContactsRepository.RawContact raw, Map<String, Long> visitCounts) {
        return toContact(raw, visitCounts, true);
    }

    /**
     * {@code includeSquareHistory=false} skips {@code familyName}/{@code appointments} — the two
     * fields that cost a real Square round trip per contact ({@code customerFamilyNames} and,
     * heaviest of all, {@code fetchAppointments}'s booking/catalog/payment fan-out) — leaving them
     * at their "unknown yet" defaults (null / empty list, same as a contact with no Square customer
     * resolved at all). {@link #computeContacts} uses this for the full list (2026-08-19: this was
     * paying for up to one appointment-history fetch per contact on every cache miss, contributing
     * to Square rate-limiting seen live); {@link #enrichContacts} backs the frontend's lazy,
     * scroll-triggered follow-up call that fills these back in only for the rows actually on
     * screen. Single-contact lookups ({@link #contactByPhone}, {@link #contactByCustomerId}) keep
     * the eager {@code true} default above — there's no "just the visible rows" concept for one
     * contact.
     */
    private Contact toContact(MarketingContactsRepository.RawContact raw, Map<String, Long> visitCounts,
                               boolean includeSquareHistory) {
        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        String effectiveSquareCustomerId = resolveSquareCustomerId(raw);

        String squareProfileUrl = effectiveSquareCustomerId == null
                ? null
                : String.format(SQUARE_CUSTOMER_PROFILE_URL, effectiveSquareCustomerId);

        // marketing.contacts has no family_name column of its own (the booking form collects one,
        // it's just never persisted there) — Square is the only source, so this is best-effort
        // and only attempted when a customer is already linked, never a fresh phone lookup just
        // for display.
        String familyName = effectiveSquareCustomerId == null || !includeSquareHistory
                ? null
                : square.customerFamilyNames(List.of(effectiveSquareCustomerId)).get(effectiveSquareCustomerId);

        List<Submission> submissions = repository.findSubmissionHistory(raw.phoneNumber())
                .stream()
                .map(MarketingContactsService::toSubmission)
                .collect(Collectors.toList());

        List<Appointment> appointments = effectiveSquareCustomerId == null || !includeSquareHistory
                ? List.of()
                : fetchAppointments(effectiveSquareCustomerId, raw.createdAt());

        // True from *either* source — the same "either source" consent rule
        // SameDayRebookingScheduler#hasConsent already uses for sending, so this field never
        // shows "no consent" for someone the automations would in fact be allowed to text.
        boolean smsMarketingConsent = Boolean.TRUE.equals(raw.smsMarketingConsent())
                || hasSquareConsentSegment(effectiveSquareCustomerId);

        ContactLinkEngagement engagement = linkEngagementFor(raw.phoneNumber());

        // 0 (not "not applicable") for a contact with no Square customer resolved yet — visitCount
        // stays null in that case, same rationale as squareProfileUrl/appointments above.
        Long visitCount = effectiveSquareCustomerId == null ? null : visitCounts.getOrDefault(effectiveSquareCustomerId, 0L);
        boolean vip = visitCount != null && visitCount >= vipVisitThreshold;

        return new Contact(
                raw.id().toString(),
                raw.givenName(),
                familyName,
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
                smsMarketingConsent,
                raw.emailMarketingConsent(),
                squareProfileUrl,
                submissions,
                appointments,
                raw.createdAt(),
                raw.updatedAt(),
                engagement.googleReview().sentAt(), engagement.googleReview().clickedAt(),
                engagement.feedbackForm().sentAt(), engagement.feedbackForm().clickedAt(),
                vip, visitCount == null ? null : visitCount.intValue()
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
            SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
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
            String tz = squareClientProvider.forBusiness(currentBusinessContext.id()).locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private BigDecimal priceCutoff() {
        Long businessId = currentBusinessContext.id();
        SalonConfig cfg = salonConfig.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("Salon config for business " + businessId + " is missing"));
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
