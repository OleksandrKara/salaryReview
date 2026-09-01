package com.salonreview.marketing;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.MarketingContactSquareLink;
import com.salonreview.domain.MarketingSyncStatus;
import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.marketing.MarketingContactsRepository.RawAppointmentSubmission;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.marketing.MarketingContactsRepository.RawSubmission;
import com.salonreview.repo.MarketingContactSquareLinkRepository;
import com.salonreview.repo.MarketingSyncStatusRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.square.SquareMonthAggregator.BookingPayment;
import com.salonreview.web.dto.MarketingContactDto;
import com.salonreview.web.dto.MarketingContactDto.Appointment;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingContactsServiceTest {

    private MarketingContactsRepository repository;
    private MarketingContactSquareLinkRepository squareLinks;
    private SquareClient square;
    private SquareBookingMirrorRepository bookingMirrorRepository;
    private MarketingBookingPaymentMatcher paymentMatcher;
    private MarketingSyncStatusRepository syncStatus;
    private com.salonreview.sms.SmsMessageLogService smsMessageLogService;
    private ProviderVisitRepository providerVisits;
    private com.salonreview.square.SquareCustomerMirrorLookupService customerLookup;
    private MarketingContactsService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketingContactsRepository.class);
        squareLinks = mock(MarketingContactSquareLinkRepository.class);
        square = mock(SquareClient.class);
        bookingMirrorRepository = mock(SquareBookingMirrorRepository.class);
        paymentMatcher = mock(MarketingBookingPaymentMatcher.class);
        syncStatus = mock(MarketingSyncStatusRepository.class);
        smsMessageLogService = mock(com.salonreview.sms.SmsMessageLogService.class);
        providerVisits = mock(ProviderVisitRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        // computeContacts()/countFollowUpBookingsByVariant()/followUpAppointments() wrap their
        // parallelStream() work in runAsAndGet(businessId, Supplier) so worker threads see the business
        // id (see the async ThreadLocal fix on this class) — a plain mock's runAsAndGet() is a no-op
        // that never invokes the wrapped supplier, so make it actually run.
        when(currentBusinessContext.runAsAndGet(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<Object>>any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        com.salonreview.square.SquareClientProvider squareClientProvider =
                mock(com.salonreview.square.SquareClientProvider.class);
        when(squareClientProvider.forBusiness(org.mockito.ArgumentMatchers.anyLong())).thenReturn(square);
        // Phase 3: mocked to transparently forward to whatever square.customerIdsForPhone(...) was
        // stubbed to return, so every existing test's stub keeps working unchanged — this file is
        // about MarketingContactsService's own logic, not the mirror-vs-live resolution strategy.
        customerLookup = mock(com.salonreview.square.SquareCustomerMirrorLookupService.class);
        when(customerLookup.customerIdsForPhone(any(), any(), eq(square)))
                .thenAnswer(inv -> square.customerIdsForPhone(inv.getArgument(1)));
        service = new MarketingContactsService(repository, squareLinks, squareClientProvider,
                bookingMirrorRepository, paymentMatcher,
                currentBusinessContext, syncStatus,
                new RebookingProperties(), smsMessageLogService, providerVisits, customerLookup, 4);
        when(repository.findSubmissionHistory(any())).thenReturn(List.of());
        when(repository.findSubmissionsByBookingIds(any())).thenReturn(Map.of());
        when(squareLinks.findByPhoneNumber(any())).thenReturn(Optional.empty());
        when(syncStatus.getSingleton()).thenReturn(MarketingSyncStatus.builder().build());
        when(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(1L)).thenReturn(List.of());
        // No link engagement by default — individual tests override with a specific stub if they
        // care about the repeat-reviewer/click-status fields.
        when(smsMessageLogService.linkEngagement(any(), any(), any()))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(null, null));
        // Default: no mirrored bookings for anyone unless a test opts in via stubBookings() — the
        // local-mirror replacement for the old "square.bookingsForCustomer(...) -> List.of()" default.
        when(bookingMirrorRepository.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(any(), any(), any()))
                .thenReturn(List.of());
    }

    private static RawContact rawContact(UUID id, String squareCustomerId) {
        return new RawContact(
                id, "(858) 555-0100", "Jane", "jane@example.com",
                "instagram / paid / promo", "google / cpc / retargeting",
                "google_ads",
                "google", "cpc", "retargeting",
                "mani", "Version_1",
                "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                true, true,
                squareCustomerId, null, null, null,
                null, null, null,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
    }

    /** Stubs {@link #bookingMirrorRepository} to return the given fixture bookings (converted to
     * {@link SquareBookingMirror} rows) for this one customer — the local-mirror replacement for
     * the old {@code when(square.bookingsForCustomer(eq(customerId), any())).thenReturn(...)}. */
    private void stubBookings(String customerId, Booking... bookings) {
        List<SquareBookingMirror> rows = java.util.Arrays.stream(bookings)
                .map(MarketingContactsServiceTest::toMirror).toList();
        when(bookingMirrorRepository.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(eq(1L), eq(customerId), any()))
                .thenReturn(rows);
    }

    private static SquareBookingMirror toMirror(Booking b) {
        List<SquareBookingMirror.Segment> segments = b.appointmentSegments() == null ? null
                : b.appointmentSegments().stream()
                        .map(s -> new SquareBookingMirror.Segment(s.teamMemberId(), s.serviceVariationId(), s.durationMinutes()))
                        .toList();
        return SquareBookingMirror.builder()
                .businessId(1L)
                .squareBookingId(b.id())
                .squareCustomerId(b.customerId())
                .status(b.status())
                .startAt(b.startAt() == null ? null : Instant.parse(b.startAt()))
                .createdAt(b.createdAt() == null ? null : Instant.parse(b.createdAt()))
                .updatedAt(b.updatedAt() == null ? null : Instant.parse(b.updatedAt()))
                .locationId(b.locationId())
                .sellerNote(b.sellerNote())
                .customerNote(b.customerNote())
                .appointmentSegments(segments)
                .build();
    }

    @Test
    @DisplayName("a contact's review-link engagement (sent/clicked for Google review, Yelp review, and feedback form) is surfaced on the Contact DTO")
    void contactSurfacesReviewLinkEngagement() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Instant googleSent = Instant.parse("2026-07-20T10:00:00Z");
        Instant googleClicked = Instant.parse("2026-07-20T10:05:00Z");
        Instant yelpSent = Instant.parse("2026-07-20T10:10:00Z");
        when(smsMessageLogService.linkEngagement(1L, "(858) 555-0100", com.salonreview.sms.CheckoutReviewLinks.GOOGLE_REVIEW_TARGET))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(googleSent, googleClicked));
        when(smsMessageLogService.linkEngagement(1L, "(858) 555-0100", com.salonreview.sms.CheckoutReviewLinks.YELP_REVIEW_TARGET))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(yelpSent, null));
        when(smsMessageLogService.linkEngagement(1L, "(858) 555-0100", com.salonreview.sms.CheckoutReviewLinks.FEEDBACK_FORM_TARGET))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(null, null));

        Contact c = service.contacts().contacts().get(0);

        assertThat(c.googleReviewSentAt()).isEqualTo(googleSent);
        assertThat(c.googleReviewClickedAt()).isEqualTo(googleClicked);
        assertThat(c.yelpReviewSentAt()).isEqualTo(yelpSent);
        assertThat(c.yelpReviewClickedAt()).isNull();
        assertThat(c.feedbackFormSentAt()).isNull();
        assertThat(c.feedbackFormClickedAt()).isNull();
    }

    @Test
    @DisplayName("a contact with a known Square customer gets a profile link")
    void contactWithSquareCustomerHasProfileLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isTrue();
        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/directory/customer/SQCUST123");
        assertThat(c.originalTrafficSource()).isEqualTo("instagram / paid / promo");
        assertThat(c.marketingTrafficSource()).isEqualTo("google / cpc / retargeting");
        assertThat(c.channel()).isEqualTo("google_ads");
        assertThat(c.landingPageSlug()).isEqualTo("mani");
        assertThat(c.variantName()).isEqualTo("Version_1");
        assertThat(c.deviceType()).isEqualTo("mobile");
        assertThat(c.osName()).isEqualTo("iOS");
        assertThat(c.utmSource()).isEqualTo("google");
        assertThat(c.utmMedium()).isEqualTo("cpc");
        assertThat(c.utmCampaign()).isEqualTo("retargeting");
        assertThat(c.updatedAt()).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
    }

    @Test
    @DisplayName("a customer with visits on 4+ distinct days is flagged VIP, below threshold is not")
    void vipFlagReflectsDistinctDayVisitCount() {
        UUID vipId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(vipId, "SQCUST_VIP"), rawContact(regularId, "SQCUST_REGULAR")));
        when(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(1L)).thenReturn(List.of(
                visit("SQCUST_VIP", java.time.LocalDate.parse("2026-01-05")),
                visit("SQCUST_VIP", java.time.LocalDate.parse("2026-02-05")),
                visit("SQCUST_VIP", java.time.LocalDate.parse("2026-03-05")),
                visit("SQCUST_VIP", java.time.LocalDate.parse("2026-04-05")),
                visit("SQCUST_REGULAR", java.time.LocalDate.parse("2026-01-05")),
                visit("SQCUST_REGULAR", java.time.LocalDate.parse("2026-02-05"))
        ));

        List<Contact> contacts = service.contacts().contacts();

        Contact vip = contacts.stream().filter(c -> "SQCUST_VIP".equals(c.squareProfileUrl() == null ? null
                : c.squareProfileUrl().substring(c.squareProfileUrl().lastIndexOf('/') + 1))).findFirst().orElseThrow();
        Contact regular = contacts.stream().filter(c -> "SQCUST_REGULAR".equals(c.squareProfileUrl() == null ? null
                : c.squareProfileUrl().substring(c.squareProfileUrl().lastIndexOf('/') + 1))).findFirst().orElseThrow();

        assertThat(vip.vip()).isTrue();
        assertThat(vip.visitCount()).isEqualTo(4);
        assertThat(regular.vip()).isFalse();
        assertThat(regular.visitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("two providers seeing a customer on the same day counts as one visit, not two")
    void sameDayTwoProvidersCountsAsOneVisit() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST_SAMEDAY")));
        when(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(1L)).thenReturn(List.of(
                visit("SQCUST_SAMEDAY", java.time.LocalDate.parse("2026-05-01")),
                visitWithProvider("SQCUST_SAMEDAY", java.time.LocalDate.parse("2026-05-01"), "PROVIDER_2")
        ));

        Contact c = service.contacts().contacts().get(0);

        assertThat(c.visitCount()).isEqualTo(1);
        assertThat(c.vip()).isFalse();
    }

    private static ProviderVisit visit(String customerId, java.time.LocalDate date) {
        return visitWithProvider(customerId, date, "PROVIDER_1");
    }

    private static ProviderVisit visitWithProvider(String customerId, java.time.LocalDate date, String providerRef) {
        return ProviderVisit.builder().customerId(customerId).providerRef(providerRef).serviceDate(date).build();
    }

    @Test
    @DisplayName("a lead with no known Square customer has no profile link and an empty appointments list")
    void leadWithoutSquareCustomerHasNoLinkOrAppointments() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, null)));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isNull();
        assertThat(c.appointments()).isEmpty();
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.listAllForBusiness(1L)).thenThrow(new DataAccessResourceFailureException("relation \"marketing.contacts\" does not exist"));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isFalse();
        assertThat(dto.contacts()).isEmpty();
    }

    @Test
    @DisplayName("2026-08-17 live incident: resolveDisplayNames returns an empty map, not a thrown exception, when Square "
            + "fails — this is the exact call TwilioInboundSmsController's Telegram-alert path makes with no try/catch of "
            + "its own, relying entirely on this method's own \"never throws\" contract")
    void resolveDisplayNamesSurvivesSquareFailure() {
        when(repository.findNamesByPhoneNumbers(any())).thenReturn(List.of(
                new MarketingContactsRepository.PhoneName("(858) 555-0100", "8585550100", "Jane", "SQCUST123", true)));
        when(square.customerGivenNames(any()))
                .thenThrow(new org.springframework.web.client.RestClientException("Square: 429 RATE_LIMITED"));

        Map<String, MarketingContactsService.ContactNameInfo> result =
                service.resolveDisplayNames(List.of("(858) 555-0100"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("2026-08-19 fix: a second resolveDisplayNames call for the same phone-number set is a cache hit — "
            + "MessagesNotifierIcon polls this every 25s and was hitting Square's rate limit live before this cache existed")
    void resolveDisplayNamesCachesRepeatedCallsForTheSamePhoneSet() {
        when(repository.findNamesByPhoneNumbers(any())).thenReturn(List.of(
                new MarketingContactsRepository.PhoneName("(858) 555-0100", "8585550100", "Jane", "SQCUST123", true)));
        when(square.customerGivenNames(any())).thenReturn(Map.of("SQCUST123", "Jane"));
        when(square.customerFamilyNames(any())).thenReturn(Map.of("SQCUST123", "Doe"));
        when(square.customerSegmentIdsBatch(any())).thenReturn(Map.of());

        service.resolveDisplayNames(List.of("(858) 555-0100"));
        service.resolveDisplayNames(List.of("(858) 555-0100"));

        verify(square, times(1)).customerGivenNames(any());
        verify(square, times(1)).customerFamilyNames(any());
    }

    @Test
    @DisplayName("2026-08-19 fix: a genuinely different phone-number set still gets its own fresh Square lookup, "
            + "not stale-cached data from an unrelated set")
    void resolveDisplayNamesRefetchesForADifferentPhoneSet() {
        when(repository.findNamesByPhoneNumbers(eq(List.of("(858) 555-0100")))).thenReturn(List.of(
                new MarketingContactsRepository.PhoneName("(858) 555-0100", "8585550100", "Jane", "SQCUST123", true)));
        when(repository.findNamesByPhoneNumbers(eq(List.of("(858) 555-0200")))).thenReturn(List.of(
                new MarketingContactsRepository.PhoneName("(858) 555-0200", "8585550200", "Bob", "SQCUST456", true)));
        when(square.customerGivenNames(any())).thenReturn(Map.of("SQCUST123", "Jane", "SQCUST456", "Bob"));
        when(square.customerFamilyNames(any())).thenReturn(Map.of());
        when(square.customerSegmentIdsBatch(any())).thenReturn(Map.of());

        service.resolveDisplayNames(List.of("(858) 555-0100"));
        service.resolveDisplayNames(List.of("(858) 555-0200"));

        verify(square, times(2)).customerGivenNames(any());
    }

    @Test
    @DisplayName("2026-08-19 fix: a failed lookup is never cached — the very next call still hits Square again "
            + "rather than freezing an empty result for the full TTL")
    void resolveDisplayNamesDoesNotCacheAFailure() {
        when(repository.findNamesByPhoneNumbers(any())).thenReturn(List.of(
                new MarketingContactsRepository.PhoneName("(858) 555-0100", "8585550100", "Jane", "SQCUST123", true)));
        when(square.customerGivenNames(any()))
                .thenThrow(new org.springframework.web.client.RestClientException("Square: 429 RATE_LIMITED"))
                .thenReturn(Map.of("SQCUST123", "Jane"));
        when(square.customerFamilyNames(any())).thenReturn(Map.of());
        when(square.customerSegmentIdsBatch(any())).thenReturn(Map.of());

        Map<String, MarketingContactsService.ContactNameInfo> first =
                service.resolveDisplayNames(List.of("(858) 555-0100"));
        Map<String, MarketingContactsService.ContactNameInfo> second =
                service.resolveDisplayNames(List.of("(858) 555-0100"));

        assertThat(first).isEmpty();
        assertThat(second.get("(858) 555-0100").givenName()).isEqualTo("Jane");
        verify(square, times(2)).customerGivenNames(any());
    }

    @Test
    @DisplayName("several phone numbers with no marketing.contacts row all resolve correctly via the "
            + "customer-mirror/live fallback at once — regression guard for the fan-out fix: this branch used to "
            + "run as a plain sequential for loop, so a real conversation-log page (every phone the salon has "
            + "ever texted, not just ads-attributed ones) chained one blocking Square lookup after another into "
            + "confirmed 30-55s real production loads; now runs via parallelStream like resolveAdsCustomersUncached")
    void resolveDisplayNamesResolvesMultiplePhonesWithNoContactsRowConcurrently() {
        // No marketing.contacts row for any of these — every one falls through to customerLookup.
        when(repository.findNamesByPhoneNumbers(any())).thenReturn(List.of());
        when(customerLookup.customerIdsForPhone(eq(1L), eq("(858) 555-0100"), eq(square)))
                .thenReturn(List.of("SQCUST-A"));
        when(customerLookup.customerIdsForPhone(eq(1L), eq("(858) 555-0200"), eq(square)))
                .thenReturn(List.of("SQCUST-B"));
        when(customerLookup.customerIdsForPhone(eq(1L), eq("(858) 555-0300"), eq(square)))
                .thenReturn(List.of()); // genuinely unresolvable — no candidates anywhere
        when(square.customerGivenNames(any())).thenReturn(Map.of("SQCUST-A", "Amy", "SQCUST-B", "Ben"));
        when(square.customerFamilyNames(any())).thenReturn(Map.of("SQCUST-A", "Adams", "SQCUST-B", "Brooks"));
        when(square.customerSegmentIdsBatch(any())).thenReturn(Map.of());

        Map<String, MarketingContactsService.ContactNameInfo> result = service.resolveDisplayNames(
                List.of("(858) 555-0100", "(858) 555-0200", "(858) 555-0300"));

        assertThat(result.get("(858) 555-0100").givenName()).isEqualTo("Amy");
        assertThat(result.get("(858) 555-0100").familyName()).isEqualTo("Adams");
        assertThat(result.get("(858) 555-0200").givenName()).isEqualTo("Ben");
        assertThat(result.get("(858) 555-0200").familyName()).isEqualTo("Brooks");
        assertThat(result.get("(858) 555-0300").givenName()).isNull();
        assertThat(result.get("(858) 555-0300").squareProfileUrl()).isNull();
    }

    @Test
    @DisplayName("submissions come from our own DB regardless of whether a Square customer is known")
    void submissionsAlwaysPopulated() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, null)));
        when(repository.findSubmissionHistory("(858) 555-0100")).thenReturn(List.of(
                new RawSubmission("step1", Instant.parse("2026-07-01T00:00:00Z"), "mani", "Version_1",
                        "google / cpc / promo", "google", "cpc", "promo", null, null)
        ));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.submissions()).hasSize(1);
        assertThat(c.submissions().get(0).submissionType()).isEqualTo("step1");
        assertThat(c.submissions().get(0).trafficSource()).isEqualTo("google / cpc / promo");
        assertThat(c.submissions().get(0).utmCampaign()).isEqualTo("promo");
    }

    @Test
    @DisplayName("2026-08-28 (Phase 1f): bulk contacts() eagerly resolves appointments again now that "
            + "fetchAppointments reads the local Square booking mirror instead of calling Square live — "
            + "family name is the one field still deferred to enrichContacts, since customerFamilyNames "
            + "is still a real live Square call per contact")
    void bulkContactsResolvesAppointmentsButDefersFamilyName() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", booking);

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.appointments()).hasSize(1);
        assertThat(c.appointments().get(0).bookingId()).isEqualTo("SQBOOK1");
        assertThat(c.familyName()).isNull();
        verify(square, never()).customerFamilyNames(any());
    }

    @Test
    @DisplayName("appointments resolve Square bookings into service name, price, and provider name")
    void appointmentsResolveFromSquare() {
        UUID id = UUID.randomUUID();
        when(repository.findByIds(List.of(id), 1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        stubBookings("SQCUST123", booking);
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember("TM1", "Susan", "A.", "ACTIVE", false, null, null)));
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of("VAR1", "Manicure"));
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of("VAR1", new BigDecimal("85.00")));

        Map<String, MarketingContactsService.ContactEnrichment> result =
                service.enrichContacts(List.of(id.toString()));

        List<Appointment> appointments = result.get(id.toString()).appointments();
        assertThat(appointments).hasSize(1);
        var appt = appointments.get(0);
        assertThat(appt.bookingId()).isEqualTo("SQBOOK1");
        assertThat(appt.serviceName()).isEqualTo("Manicure");
        assertThat(appt.price()).isEqualByComparingTo("85.00");
        assertThat(appt.artistName()).isEqualTo("Susan A.");
        assertThat(appt.trafficSource()).isNull();
        assertThat(appt.submissionOccurredAt()).isNull();
    }

    @Test
    @DisplayName("an appointment that came through our own funnel is enriched with its originating submission's traffic/device info")
    void appointmentEnrichedWithOriginatingSubmission() {
        UUID id = UUID.randomUUID();
        when(repository.findByIds(List.of(id), 1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        stubBookings("SQCUST123", booking);
        when(square.allTeamMembers()).thenReturn(List.of());
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of());
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of());
        when(repository.findSubmissionsByBookingIds(List.of("SQBOOK1"))).thenReturn(Map.of(
                "SQBOOK1", new RawAppointmentSubmission("SQBOOK1", Instant.parse("2026-07-30T10:00:00Z"),
                        "google / cpc / promo", "mobile", "iOS", "17.5", "Mobile Safari")
        ));

        Map<String, MarketingContactsService.ContactEnrichment> result =
                service.enrichContacts(List.of(id.toString()));

        var appt = result.get(id.toString()).appointments().get(0);
        assertThat(appt.trafficSource()).isEqualTo("google / cpc / promo");
        assertThat(appt.deviceType()).isEqualTo("mobile");
        assertThat(appt.osName()).isEqualTo("iOS");
        assertThat(appt.submissionOccurredAt()).isEqualTo(Instant.parse("2026-07-30T10:00:00Z"));
    }

    @Test
    @DisplayName("contactByPhone falls back to a live Square phone lookup when there's no marketing.contacts row at all")
    void contactByPhoneFallsBackToLivePhoneLookupWhenNoTrackedRow() {
        when(repository.findByPhoneNumber("(863) 660-3063", 1L)).thenReturn(Optional.empty());
        when(square.customerIdsForPhone("(863) 660-3063")).thenReturn(List.of("SQCUST999"));
        when(square.customerGivenNames(List.of("SQCUST999"))).thenReturn(Map.of("SQCUST999", "Lily"));
        when(square.customerFamilyNames(List.of("SQCUST999"))).thenReturn(Map.of("SQCUST999", "Frei"));
        when(repository.findSubmissionHistory("(863) 660-3063")).thenReturn(List.of());

        Booking booking = new Booking("SQBOOK9", "ACCEPTED", "2026-08-10T17:00:00Z", null, null,
                "LOC1", "SQCUST999", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        stubBookings("SQCUST999", booking);
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember("TM1", "Susan", "A.", "ACTIVE", false, null, null)));
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of("VAR1", "Manicure"));
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of("VAR1", new BigDecimal("85.00")));
        when(repository.findSubmissionsByBookingIds(List.of("SQBOOK9"))).thenReturn(Map.of());

        Optional<Contact> result = service.contactByPhone("(863) 660-3063");

        assertThat(result).isPresent();
        Contact c = result.get();
        assertThat(c.givenName()).isEqualTo("Lily");
        assertThat(c.familyName()).isEqualTo("Frei");
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/directory/customer/SQCUST999");
        assertThat(c.appointments()).hasSize(1);
        assertThat(c.appointments().get(0).bookingId()).isEqualTo("SQBOOK9");
        assertThat(c.createdAt()).isNull();
    }

    @Test
    @DisplayName("contactByPhone stays empty when neither marketing.contacts nor a live Square lookup resolves anything")
    void contactByPhoneEmptyWhenNothingResolves() {
        when(repository.findByPhoneNumber("(555) 000-0000", 1L)).thenReturn(Optional.empty());
        when(square.customerIdsForPhone("(555) 000-0000")).thenReturn(List.of());

        Optional<Contact> result = service.contactByPhone("(555) 000-0000");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a past appointment shows the real collected amount and channel when the payment matcher finds one")
    void appointmentShowsRealCollectedPayment() {
        UUID id = UUID.randomUUID();
        when(repository.findByIds(List.of(id), 1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2020-06-15T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", booking);
        when(paymentMatcher.match(eq(1L), eq("SQCUST123"), any(), any()))
                .thenReturn(Optional.of(new BookingPayment("CASH", new BigDecimal("50.00"), new BigDecimal("50.00"))));

        Map<String, MarketingContactsService.ContactEnrichment> result =
                service.enrichContacts(List.of(id.toString()));

        var appt = result.get(id.toString()).appointments().get(0);
        assertThat(appt.paymentChannel()).isEqualTo("CASH");
        assertThat(appt.collectedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("a past appointment with no matching payment shows no payment info, without throwing")
    void appointmentWithNoMatchingPaymentShowsNull() {
        UUID id = UUID.randomUUID();
        when(repository.findByIds(List.of(id), 1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2020-06-15T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", booking);
        when(paymentMatcher.match(eq(1L), eq("SQCUST123"), any(), any())).thenReturn(Optional.empty());

        Map<String, MarketingContactsService.ContactEnrichment> result =
                service.enrichContacts(List.of(id.toString()));

        var appt = result.get(id.toString()).appointments().get(0);
        assertThat(appt.paymentChannel()).isNull();
        assertThat(appt.collectedAmount()).isNull();
    }

    @Test
    @DisplayName("an upcoming appointment never triggers a payment-matcher lookup")
    void upcomingAppointmentSkipsPaymentLookup() {
        UUID id = UUID.randomUUID();
        when(repository.findByIds(List.of(id), 1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking future = new Booking("SQBOOK1", "ACCEPTED", "2099-01-01T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", future);

        Map<String, MarketingContactsService.ContactEnrichment> result =
                service.enrichContacts(List.of(id.toString()));

        var appt = result.get(id.toString()).appointments().get(0);
        assertThat(appt.paymentChannel()).isNull();
        verify(paymentMatcher, never()).match(any(), any(), any(), any());
    }

    @Test
    @DisplayName("yields an empty appointments list, not a thrown exception, when the booking mirror lookup fails")
    void toleratesSquareFailure() {
        UUID id = UUID.randomUUID();
        when(repository.findByIds(List.of(id), 1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        when(bookingMirrorRepository.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(eq(1L), eq("SQCUST123"), any()))
                .thenThrow(new RuntimeException("DB unreachable"));

        Map<String, MarketingContactsService.ContactEnrichment> result =
                service.enrichContacts(List.of(id.toString()));

        assertThat(result.get(id.toString()).appointments()).isEmpty();
    }

    @Test
    @DisplayName("a lead with no stored Square id, but a previously-cached phone link, shows appointments through it")
    void usesCachedLinkAsFallback() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, null)));
        when(squareLinks.findByPhoneNumber("(858) 555-0100")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0100").squareCustomerId("SQCUST999")
                        .lastSyncedAt(Instant.now()).build()));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/directory/customer/SQCUST999");
    }

    @Test
    @DisplayName("sync resolves an unlinked lead's Square customer by phone and caches it")
    void syncResolvesUnlinkedLeadByPhone() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, null)));
        when(square.customerIdsForPhone("(858) 555-0100")).thenReturn(List.of("SQCUST777"));

        service.syncSquareLinks();

        var captor = org.mockito.ArgumentCaptor.forClass(MarketingContactSquareLink.class);
        verify(squareLinks).save(captor.capture());
        // Normalized to E.164 on save — see PhoneNumbers' own doc comment for why: this table's
        // own exact-format writes must stay internally consistent for later lookups to work.
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo("+18585550100");
        assertThat(captor.getValue().getSquareCustomerId()).isEqualTo("SQCUST777");
    }

    @Test
    @DisplayName("sync skips a contact that already has a stored Square id or an already-cached link, and busts the Square cache first")
    void syncSkipsAlreadyLinkedContacts() {
        UUID linkedId = UUID.randomUUID();
        UUID cachedId = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(
                rawContact(linkedId, "SQCUST_STORED"),
                new RawContact(cachedId, "(858) 555-0200", "Ann", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "mani", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, null, null, null, null, null, null, null,
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"))
        ));
        when(squareLinks.findByPhoneNumber("(858) 555-0200")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0200").squareCustomerId("SQCUST_CACHED")
                        .lastSyncedAt(Instant.now()).build()));

        service.syncSquareLinks();

        verify(square, never()).customerIdsForPhone(any());
        verify(squareLinks, never()).save(any());
        verify(square).invalidate();
    }

    @Test
    @DisplayName("sync records a fresh lastSyncedAt even on a no-op run — the button's own timestamp "
            + "must stay trustworthy regardless of whether anything new was actually linked")
    void syncRecordsTimestampEvenWhenNothingNewIsLinked() {
        UUID linkedId = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(linkedId, "SQCUST_STORED")));
        MarketingSyncStatus status = MarketingSyncStatus.builder()
                .lastSyncedAt(Instant.parse("2020-01-01T00:00:00Z")).build();
        when(syncStatus.getSingleton()).thenReturn(status);

        service.syncSquareLinks();

        verify(squareLinks, never()).save(any());
        var captor = org.mockito.ArgumentCaptor.forClass(MarketingSyncStatus.class);
        verify(syncStatus).save(captor.capture());
        assertThat(captor.getValue().getLastSyncedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("lastSyncedAt reads straight off the marketing_sync_status singleton")
    void lastSyncedAtReadsSingleton() {
        Instant syncedAt = Instant.parse("2026-06-01T12:00:00Z");
        when(syncStatus.getSingleton()).thenReturn(MarketingSyncStatus.builder().lastSyncedAt(syncedAt).build());

        assertThat(service.lastSyncedAt()).isEqualTo(syncedAt);
    }

    @Test
    @DisplayName("counts a contact's real appointment as a follow-up booking when its booking_id isn't in the attributed set")
    void followUpCountsRealAppointmentNotInAttribution() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", accepted);

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of("OTHERBOOK"), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("counts one real client only once even if they left two separate lead-capture contact rows")
    void followUpCollapsesDuplicateContactRowsForSameCustomer() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id1, "SQCUST123"), rawContact(id2, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", accepted);

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of("OTHERBOOK"), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("does not count an already-converted customer's later, unrelated real appointment as a follow-up")
    void followUpExcludesAlreadyConvertedCustomersOtherAppointment() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        // A brand-new booking (never attributed) for a customer who already converted on-page
        // through some other tracked booking — e.g. a normal future rebooking. Since SQCUST123 is
        // in convertedCustomerIds, this must not add a second, spurious follow-up for them.
        Booking futureRebooking = new Booking("FUTUREBOOK", "ACCEPTED", "2026-08-01T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", futureRebooking);

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant(
                "mani", null, null, java.util.Set.of("OTHERBOOK"), java.util.Set.of("SQCUST123"), TrafficSourceSql.ALL);

        assertThat(byVariant).isEmpty();
        verify(bookingMirrorRepository, never()).findByBusinessIdAndSquareCustomerIdAndStartAtAfter(any(), eq("SQCUST123"), any());
    }

    @Test
    @DisplayName("does not count a booking that's already in the attributed set")
    void followUpSkipsAlreadyAttributedBooking() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("TRACKEDBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", accepted);

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of("TRACKEDBOOK"), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(byVariant).isEmpty();
    }

    @Test
    @DisplayName("does not count a cancelled booking as a follow-up conversion")
    void followUpIgnoresCancelledBookings() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking cancelled = new Booking("CANCELLEDBOOK", "CANCELLED_BY_SELLER", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", cancelled);

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of(), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(byVariant).isEmpty();
    }

    @Test
    @DisplayName("a null-channel (unclassified) contact doesn't crash a non-'All traffic' filter — "
            + "found live 2026-08-28: TrafficSourceSql.ADS_ONLY is a 2-element Set.of(...), whose "
            + "contains(null) throws instead of returning false, and ADS_ONLY is the default whenever "
            + "the caller hasn't picked 'All traffic' — this crashed the Overview tab's very first "
            + "default-view page load for any business with an unclassified contact")
    void nullChannelContactDoesNotCrashNonAllTrafficFilter() {
        UUID id = UUID.randomUUID();
        RawContact unclassified = new RawContact(
                id, "(858) 555-0500", "Unclassified", null,
                null, null, null,
                null, null, null,
                "mani", "Version_1",
                "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                true, true,
                "SQCUST_UNCLASSIFIED", null, null, null,
                null, null, null,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(unclassified));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant(
                "mani", null, null, java.util.Set.of(), java.util.Set.of(), TrafficSourceSql.ADS_ONLY);
        List<MarketingContactsService.FollowUpAppointment> appointments = service.followUpAppointments(
                "mani", null, java.util.Set.of(), java.util.Set.of(), TrafficSourceSql.ADS_ONLY);

        // Correctly excluded, not crashed: an unclassified contact is "visible under All traffic
        // but not selectable as its own bucket" (see TrafficSourceSql's VISIT_CASE doc comment).
        assertThat(byVariant).isEmpty();
        assertThat(appointments).isEmpty();
        verify(bookingMirrorRepository, never()).findByBusinessIdAndSquareCustomerIdAndStartAtAfter(any(), eq("SQCUST_UNCLASSIFIED"), any());
    }

    @Test
    @DisplayName("a lead resolved only through the cached Sync link (never had a stored square_customer_id) also counts")
    void followUpCountsLeadResolvedThroughSyncLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, null)));
        when(squareLinks.findByPhoneNumber("(858) 555-0100")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0100").squareCustomerId("SQCUST999")
                        .lastSyncedAt(Instant.now()).build()));
        Booking accepted = new Booking("PHONEBOOKED", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST999", null, null, List.of());
        stubBookings("SQCUST999", accepted);

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of(), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("scopes follow-up counting to the requested landing page slug and statsSince cutoff")
    void followUpRespectsSlugAndStatsSinceScope() {
        UUID otherPageId = UUID.randomUUID();
        UUID oldContactId = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(
                new RawContact(otherPageId, "(858) 555-0300", "Other", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "home", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, "SQCUST_OTHERPAGE", null, null, null, null, null, null,
                        Instant.parse("2026-07-05T00:00:00Z"), Instant.parse("2026-07-05T00:00:00Z")),
                new RawContact(oldContactId, "(858) 555-0400", "Old", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "mani", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, "SQCUST_OLD", null, null, null, null, null, null,
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))
        ));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant(
                "mani", Instant.parse("2026-07-01T00:00:00Z"), null, java.util.Set.of(), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(byVariant).isEmpty();
        verify(bookingMirrorRepository, never()).findByBusinessIdAndSquareCustomerIdAndStartAtAfter(any(), eq("SQCUST_OTHERPAGE"), any());
        verify(bookingMirrorRepository, never()).findByBusinessIdAndSquareCustomerIdAndStartAtAfter(any(), eq("SQCUST_OLD"), any());
    }

    @Test
    @DisplayName("followUpAppointments excludes an already-converted customer's other real appointment")
    void followUpAppointmentsExcludesAlreadyConvertedCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking futureRebooking = new Booking("FUTUREBOOK", "ACCEPTED", "2026-08-01T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", futureRebooking);

        List<MarketingContactsService.FollowUpAppointment> appointments = service.followUpAppointments(
                "mani", null, java.util.Set.of("OTHERBOOK"), java.util.Set.of("SQCUST123"), TrafficSourceSql.ALL);

        assertThat(appointments).isEmpty();
    }

    @Test
    @DisplayName("followUpAppointments returns the real appointment record (plus its customer id), not just a count")
    void followUpAppointmentsReturnsFullRecord() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", accepted);

        List<MarketingContactsService.FollowUpAppointment> appointments =
                service.followUpAppointments("mani", null, java.util.Set.of("OTHERBOOK"), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(appointments).hasSize(1);
        assertThat(appointments.get(0).customerId()).isEqualTo("SQCUST123");
        assertThat(appointments.get(0).appointment().bookingId()).isEqualTo("NEWBOOK");
    }

    @Test
    @DisplayName("followUpAppointments never includes a booking already in the attributed set — no double-counting")
    void followUpAppointmentsExcludesAlreadyAttributed() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking tracked = new Booking("TRACKEDBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", tracked);

        List<MarketingContactsService.FollowUpAppointment> appointments =
                service.followUpAppointments("mani", null, java.util.Set.of("TRACKEDBOOK"), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(appointments).isEmpty();
    }

    @Test
    @DisplayName("followUpAppointments excludes cancelled bookings")
    void followUpAppointmentsExcludesCancelled() {
        UUID id = UUID.randomUUID();
        when(repository.listAllForBusiness(1L)).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking cancelled = new Booking("CANCELLEDBOOK", "CANCELLED_BY_SELLER", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        stubBookings("SQCUST123", cancelled);

        List<MarketingContactsService.FollowUpAppointment> appointments =
                service.followUpAppointments("mani", null, java.util.Set.of(), java.util.Set.of(), TrafficSourceSql.ALL);

        assertThat(appointments).isEmpty();
    }

    @Test
    @DisplayName("Phase 3.8/3.9: invalidateCache() drops only the calling business's own cached contacts, "
            + "never another business's — same TtlCache instance serves every business's requests")
    void invalidateCacheOnlyDropsCallingBusinesssOwnEntry() {
        com.salonreview.config.CurrentBusinessContext ctx =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        com.salonreview.square.SquareClientProvider clientProvider =
                mock(com.salonreview.square.SquareClientProvider.class);
        when(clientProvider.forBusiness(any())).thenReturn(square);
        MarketingContactsService twoTenantService = new MarketingContactsService(repository, squareLinks,
                clientProvider, bookingMirrorRepository, paymentMatcher, ctx, syncStatus,
                new RebookingProperties(), smsMessageLogService, providerVisits, customerLookup, 4);
        when(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(any())).thenReturn(List.of());
        when(repository.listAllForBusiness(1L)).thenReturn(List.of());

        when(ctx.id()).thenReturn(1L);
        twoTenantService.contacts();
        when(ctx.id()).thenReturn(2L);
        twoTenantService.contacts();
        verify(repository, times(2)).listAllForBusiness(org.mockito.ArgumentMatchers.anyLong()); // both businesses computed once each, now cached

        when(ctx.id()).thenReturn(1L);
        twoTenantService.invalidateCache(); // only business 1's entry should drop

        twoTenantService.contacts(); // business 1: cache miss -> recomputes (3rd call)
        when(ctx.id()).thenReturn(2L);
        twoTenantService.contacts(); // business 2: still cached -> must NOT recompute

        verify(repository, times(3)).listAllForBusiness(org.mockito.ArgumentMatchers.anyLong());
    }
}
