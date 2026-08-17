package com.salonreview.marketing;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.MarketingContactSquareLink;
import com.salonreview.domain.MarketingSyncStatus;
import com.salonreview.domain.SalonConfig;
import com.salonreview.marketing.MarketingContactsRepository.RawAppointmentSubmission;
import com.salonreview.marketing.MarketingContactsRepository.RawContact;
import com.salonreview.marketing.MarketingContactsRepository.RawSubmission;
import com.salonreview.domain.ProviderVisit;
import com.salonreview.repo.MarketingContactSquareLinkRepository;
import com.salonreview.repo.MarketingSyncStatusRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.square.SquareMonthAggregator;
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
    private SquareMonthAggregator aggregator;
    private SalonConfigRepository salonConfig;
    private MarketingSyncStatusRepository syncStatus;
    private com.salonreview.sms.SmsMessageLogService smsMessageLogService;
    private ProviderVisitRepository providerVisits;
    private MarketingContactsService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarketingContactsRepository.class);
        squareLinks = mock(MarketingContactSquareLinkRepository.class);
        square = mock(SquareClient.class);
        aggregator = mock(SquareMonthAggregator.class);
        salonConfig = mock(SalonConfigRepository.class);
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
        service = new MarketingContactsService(repository, squareLinks, squareClientProvider, aggregator, salonConfig,
                currentBusinessContext, syncStatus,
                new RebookingProperties(), smsMessageLogService, providerVisits, 4);
        when(repository.findSubmissionHistory(any())).thenReturn(List.of());
        when(repository.findSubmissionsByBookingIds(any())).thenReturn(Map.of());
        when(squareLinks.findByPhoneNumber(any())).thenReturn(Optional.empty());
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));
        when(syncStatus.getSingleton()).thenReturn(MarketingSyncStatus.builder().build());
        when(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(1L)).thenReturn(List.of());
        // No link engagement by default — individual tests override with a specific stub if they
        // care about the repeat-reviewer/click-status fields.
        when(smsMessageLogService.linkEngagement(any(), any(), any()))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(null, null));
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

    @Test
    @DisplayName("a contact's review-link engagement (sent/clicked for both Google review and feedback form) is surfaced on the Contact DTO")
    void contactSurfacesReviewLinkEngagement() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of());
        Instant googleSent = Instant.parse("2026-07-20T10:00:00Z");
        Instant googleClicked = Instant.parse("2026-07-20T10:05:00Z");
        when(smsMessageLogService.linkEngagement(1L, "(858) 555-0100", com.salonreview.sms.CheckoutReviewLinks.GOOGLE_REVIEW_TARGET))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(googleSent, googleClicked));
        when(smsMessageLogService.linkEngagement(1L, "(858) 555-0100", com.salonreview.sms.CheckoutReviewLinks.FEEDBACK_FORM_TARGET))
                .thenReturn(new com.salonreview.sms.SmsMessageLogService.LinkEngagement(null, null));

        Contact c = service.contacts().contacts().get(0);

        assertThat(c.googleReviewSentAt()).isEqualTo(googleSent);
        assertThat(c.googleReviewClickedAt()).isEqualTo(googleClicked);
        assertThat(c.feedbackFormSentAt()).isNull();
        assertThat(c.feedbackFormClickedAt()).isNull();
    }

    @Test
    @DisplayName("a contact with a known Square customer gets a profile link")
    void contactWithSquareCustomerHasProfileLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of());

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
        when(repository.listAll()).thenReturn(List.of(rawContact(vipId, "SQCUST_VIP"), rawContact(regularId, "SQCUST_REGULAR")));
        when(square.bookingsForCustomer(any(), any())).thenReturn(List.of());
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
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST_SAMEDAY")));
        when(square.bookingsForCustomer(any(), any())).thenReturn(List.of());
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
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isNull();
        assertThat(c.appointments()).isEmpty();
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.listAll()).thenThrow(new DataAccessResourceFailureException("relation \"marketing.contacts\" does not exist"));

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
    @DisplayName("submissions come from our own DB regardless of whether a Square customer is known")
    void submissionsAlwaysPopulated() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
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
    @DisplayName("appointments resolve Square bookings into service name, price, and provider name")
    void appointmentsResolveFromSquare() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(booking));
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember("TM1", "Susan", "A.", "ACTIVE", false, null, null)));
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of("VAR1", "Manicure"));
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of("VAR1", new BigDecimal("85.00")));

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.appointments()).hasSize(1);
        var appt = c.appointments().get(0);
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
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));

        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2026-07-31T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(booking));
        when(square.allTeamMembers()).thenReturn(List.of());
        when(square.catalogNames(List.of("VAR1"))).thenReturn(Map.of());
        when(square.catalogPrices(List.of("VAR1"))).thenReturn(Map.of());
        when(repository.findSubmissionsByBookingIds(List.of("SQBOOK1"))).thenReturn(Map.of(
                "SQBOOK1", new RawAppointmentSubmission("SQBOOK1", Instant.parse("2026-07-30T10:00:00Z"),
                        "google / cpc / promo", "mobile", "iOS", "17.5", "Mobile Safari")
        ));

        MarketingContactDto dto = service.contacts();

        var appt = dto.contacts().get(0).appointments().get(0);
        assertThat(appt.trafficSource()).isEqualTo("google / cpc / promo");
        assertThat(appt.deviceType()).isEqualTo("mobile");
        assertThat(appt.osName()).isEqualTo("iOS");
        assertThat(appt.submissionOccurredAt()).isEqualTo(Instant.parse("2026-07-30T10:00:00Z"));
    }

    @Test
    @DisplayName("contactByPhone falls back to a live Square phone lookup when there's no marketing.contacts row at all")
    void contactByPhoneFallsBackToLivePhoneLookupWhenNoTrackedRow() {
        when(repository.findByPhoneNumber("(863) 660-3063")).thenReturn(Optional.empty());
        when(square.customerIdsForPhone("(863) 660-3063")).thenReturn(List.of("SQCUST999"));
        when(square.customerGivenNames(List.of("SQCUST999"))).thenReturn(Map.of("SQCUST999", "Lily"));
        when(square.customerFamilyNames(List.of("SQCUST999"))).thenReturn(Map.of("SQCUST999", "Frei"));
        when(repository.findSubmissionHistory("(863) 660-3063")).thenReturn(List.of());

        Booking booking = new Booking("SQBOOK9", "ACCEPTED", "2026-08-10T17:00:00Z", null, null,
                "LOC1", "SQCUST999", null, null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
        when(square.bookingsForCustomer(eq("SQCUST999"), any())).thenReturn(List.of(booking));
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
        when(repository.findByPhoneNumber("(555) 000-0000")).thenReturn(Optional.empty());
        when(square.customerIdsForPhone("(555) 000-0000")).thenReturn(List.of());

        Optional<Contact> result = service.contactByPhone("(555) 000-0000");

        assertThat(result).isEmpty();
    }

    private static SquareMonthAggregator.MonthAggregation aggOf(int year, int month, List<SquareMonthAggregator.AttributedService> services) {
        return new SquareMonthAggregator.MonthAggregation(year, month, "UTC", List.of(),
                new SquareMonthAggregator.Diag(), services, List.of(), List.of());
    }

    @Test
    @DisplayName("a past appointment shows the real collected amount and channel when a matching payroll line is found")
    void appointmentShowsRealCollectedPayment() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2020-06-15T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(booking));
        var line = new SquareMonthAggregator.AttributedService("p1", "P", "2020-06-15", "FIRST", "Manicure",
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CASH", null, "SQBOOK1", "SQCUST123", null);
        when(aggregator.aggregate(2020, 6, new BigDecimal("60.00"))).thenReturn(aggOf(2020, 6, List.of(line)));

        MarketingContactDto dto = service.contacts();

        var appt = dto.contacts().get(0).appointments().get(0);
        assertThat(appt.paymentChannel()).isEqualTo("CASH");
        assertThat(appt.collectedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("a past appointment with no matching payroll line shows no payment info, without throwing")
    void appointmentWithNoMatchingPaymentShowsNull() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking booking = new Booking("SQBOOK1", "ACCEPTED", "2020-06-15T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(booking));
        when(aggregator.aggregate(2020, 6, new BigDecimal("60.00"))).thenReturn(aggOf(2020, 6, List.of()));

        MarketingContactDto dto = service.contacts();

        var appt = dto.contacts().get(0).appointments().get(0);
        assertThat(appt.paymentChannel()).isNull();
        assertThat(appt.collectedAmount()).isNull();
    }

    @Test
    @DisplayName("an upcoming appointment never triggers a payroll lookup")
    void upcomingAppointmentSkipsPaymentLookup() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking future = new Booking("SQBOOK1", "ACCEPTED", "2099-01-01T17:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(future));

        MarketingContactDto dto = service.contacts();

        var appt = dto.contacts().get(0).appointments().get(0);
        assertThat(appt.paymentChannel()).isNull();
        verify(aggregator, never()).aggregate(anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("yields an empty appointments list, not a thrown exception, when Square is unreachable")
    void toleratesSquareFailure() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenThrow(new RuntimeException("Square unreachable"));

        MarketingContactDto dto = service.contacts();

        assertThat(dto.available()).isTrue();
        assertThat(dto.contacts().get(0).appointments()).isEmpty();
    }

    @Test
    @DisplayName("a lead with no stored Square id, but a previously-cached phone link, shows appointments through it")
    void usesCachedLinkAsFallback() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(squareLinks.findByPhoneNumber("(858) 555-0100")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0100").squareCustomerId("SQCUST999")
                        .lastSyncedAt(Instant.now()).build()));
        when(square.bookingsForCustomer(eq("SQCUST999"), any())).thenReturn(List.of());

        MarketingContactDto dto = service.contacts();

        Contact c = dto.contacts().get(0);
        assertThat(c.squareProfileUrl()).isEqualTo("https://app.squareup.com/dashboard/customers/directory/customer/SQCUST999");
    }

    @Test
    @DisplayName("sync resolves an unlinked lead's Square customer by phone and caches it")
    void syncResolvesUnlinkedLeadByPhone() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(square.customerIdsForPhone("(858) 555-0100")).thenReturn(List.of("SQCUST777"));
        when(square.bookingsForCustomer(eq("SQCUST777"), any())).thenReturn(List.of());

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
        when(repository.listAll()).thenReturn(List.of(
                rawContact(linkedId, "SQCUST_STORED"),
                new RawContact(cachedId, "(858) 555-0200", "Ann", null,
                        "google_ads", "google_ads", "google_ads", "google", "cpc", "promo",
                        "mani", "Version_1", "mobile", "iOS", "17.5", "Mobile Safari", "17.5",
                        true, true, null, null, null, null, null, null, null,
                        Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"))
        ));
        when(square.bookingsForCustomer(any(), any())).thenReturn(List.of());
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
        when(repository.listAll()).thenReturn(List.of(rawContact(linkedId, "SQCUST_STORED")));
        when(square.bookingsForCustomer(any(), any())).thenReturn(List.of());
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
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of("OTHERBOOK"), java.util.Set.of());

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("counts one real client only once even if they left two separate lead-capture contact rows")
    void followUpCollapsesDuplicateContactRowsForSameCustomer() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id1, "SQCUST123"), rawContact(id2, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of("OTHERBOOK"), java.util.Set.of());

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("does not count an already-converted customer's later, unrelated real appointment as a follow-up")
    void followUpExcludesAlreadyConvertedCustomersOtherAppointment() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        // A brand-new booking (never attributed) for a customer who already converted on-page
        // through some other tracked booking — e.g. a normal future rebooking. Since SQCUST123 is
        // in convertedCustomerIds, this must not add a second, spurious follow-up for them.
        Booking futureRebooking = new Booking("FUTUREBOOK", "ACCEPTED", "2026-08-01T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(futureRebooking));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant(
                "mani", null, null, java.util.Set.of("OTHERBOOK"), java.util.Set.of("SQCUST123"));

        assertThat(byVariant).isEmpty();
        verify(square, never()).bookingsForCustomer(eq("SQCUST123"), any());
    }

    @Test
    @DisplayName("does not count a booking that's already in the attributed set")
    void followUpSkipsAlreadyAttributedBooking() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("TRACKEDBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of("TRACKEDBOOK"), java.util.Set.of());

        assertThat(byVariant).isEmpty();
    }

    @Test
    @DisplayName("does not count a cancelled booking as a follow-up conversion")
    void followUpIgnoresCancelledBookings() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking cancelled = new Booking("CANCELLEDBOOK", "CANCELLED_BY_SELLER", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(cancelled));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of(), java.util.Set.of());

        assertThat(byVariant).isEmpty();
    }

    @Test
    @DisplayName("a lead resolved only through the cached Sync link (never had a stored square_customer_id) also counts")
    void followUpCountsLeadResolvedThroughSyncLink() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, null)));
        when(squareLinks.findByPhoneNumber("(858) 555-0100")).thenReturn(Optional.of(
                MarketingContactSquareLink.builder().phoneNumber("(858) 555-0100").squareCustomerId("SQCUST999")
                        .lastSyncedAt(Instant.now()).build()));
        Booking accepted = new Booking("PHONEBOOKED", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST999", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST999"), any())).thenReturn(List.of(accepted));

        Map<String, Long> byVariant = service.countFollowUpBookingsByVariant("mani", null, null, java.util.Set.of(), java.util.Set.of());

        assertThat(byVariant).containsEntry("Version_1", 1L);
    }

    @Test
    @DisplayName("scopes follow-up counting to the requested landing page slug and statsSince cutoff")
    void followUpRespectsSlugAndStatsSinceScope() {
        UUID otherPageId = UUID.randomUUID();
        UUID oldContactId = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(
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
                "mani", Instant.parse("2026-07-01T00:00:00Z"), null, java.util.Set.of(), java.util.Set.of());

        assertThat(byVariant).isEmpty();
        verify(square, never()).bookingsForCustomer(eq("SQCUST_OTHERPAGE"), any());
        verify(square, never()).bookingsForCustomer(eq("SQCUST_OLD"), any());
    }

    @Test
    @DisplayName("followUpAppointments excludes an already-converted customer's other real appointment")
    void followUpAppointmentsExcludesAlreadyConvertedCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking futureRebooking = new Booking("FUTUREBOOK", "ACCEPTED", "2026-08-01T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(futureRebooking));

        List<MarketingContactsService.FollowUpAppointment> appointments = service.followUpAppointments(
                "mani", null, java.util.Set.of("OTHERBOOK"), java.util.Set.of("SQCUST123"));

        assertThat(appointments).isEmpty();
    }

    @Test
    @DisplayName("followUpAppointments returns the real appointment record (plus its customer id), not just a count")
    void followUpAppointmentsReturnsFullRecord() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking accepted = new Booking("NEWBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(accepted));

        List<MarketingContactsService.FollowUpAppointment> appointments =
                service.followUpAppointments("mani", null, java.util.Set.of("OTHERBOOK"), java.util.Set.of());

        assertThat(appointments).hasSize(1);
        assertThat(appointments.get(0).customerId()).isEqualTo("SQCUST123");
        assertThat(appointments.get(0).appointment().bookingId()).isEqualTo("NEWBOOK");
    }

    @Test
    @DisplayName("followUpAppointments never includes a booking already in the attributed set — no double-counting")
    void followUpAppointmentsExcludesAlreadyAttributed() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking tracked = new Booking("TRACKEDBOOK", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(tracked));

        List<MarketingContactsService.FollowUpAppointment> appointments =
                service.followUpAppointments("mani", null, java.util.Set.of("TRACKEDBOOK"), java.util.Set.of());

        assertThat(appointments).isEmpty();
    }

    @Test
    @DisplayName("followUpAppointments excludes cancelled bookings")
    void followUpAppointmentsExcludesCancelled() {
        UUID id = UUID.randomUUID();
        when(repository.listAll()).thenReturn(List.of(rawContact(id, "SQCUST123")));
        Booking cancelled = new Booking("CANCELLEDBOOK", "CANCELLED_BY_SELLER", "2026-07-07T21:00:00Z", null, null,
                "LOC1", "SQCUST123", null, null, List.of());
        when(square.bookingsForCustomer(eq("SQCUST123"), any())).thenReturn(List.of(cancelled));

        List<MarketingContactsService.FollowUpAppointment> appointments =
                service.followUpAppointments("mani", null, java.util.Set.of(), java.util.Set.of());

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
                clientProvider, aggregator, salonConfig, ctx, syncStatus,
                new RebookingProperties(), smsMessageLogService, providerVisits, 4);
        when(salonConfig.findByBusinessId(any())).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));
        when(providerVisits.findAllByBusinessIdOrderByServiceDateAsc(any())).thenReturn(List.of());
        when(repository.listAll()).thenReturn(List.of());

        when(ctx.id()).thenReturn(1L);
        twoTenantService.contacts();
        when(ctx.id()).thenReturn(2L);
        twoTenantService.contacts();
        verify(repository, times(2)).listAll(); // both businesses computed once each, now cached

        when(ctx.id()).thenReturn(1L);
        twoTenantService.invalidateCache(); // only business 1's entry should drop

        twoTenantService.contacts(); // business 1: cache miss -> recomputes (3rd call)
        when(ctx.id()).thenReturn(2L);
        twoTenantService.contacts(); // business 2: still cached -> must NOT recompute

        verify(repository, times(3)).listAll();
    }
}
