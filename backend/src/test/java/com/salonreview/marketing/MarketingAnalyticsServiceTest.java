package com.salonreview.marketing;

import com.salonreview.domain.AdSpendEntry;
import com.salonreview.domain.SalonConfig;
import com.salonreview.marketing.MarketingContactsRepository.AdsAttributedContact;
import com.salonreview.repo.AdSpendEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.web.dto.MarketingAdsReportDto;
import com.salonreview.web.dto.MarketingAdsReportDto.PeriodRow;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingAnalyticsServiceTest {

    private MarketingContactsRepository contactsRepository;
    private SquareMonthAggregator aggregator;
    private SquareClient square;
    private SalonConfigRepository salonConfig;
    private AdSpendEntryRepository adSpendEntryRepository;
    private MarketingContactsService contactsService;
    private MarketingDashboardRepository dashboardRepository;
    private MarketingAnalyticsService service;

    /** Fixes "today" to 2026-07-07 (mid-range of every test's fixture data), so the
     * current-month-to-date segment and ad spend lookup don't race the real clock. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        contactsRepository = mock(MarketingContactsRepository.class);
        aggregator = mock(SquareMonthAggregator.class);
        square = mock(SquareClient.class);
        salonConfig = mock(SalonConfigRepository.class);
        adSpendEntryRepository = mock(AdSpendEntryRepository.class);
        contactsService = mock(MarketingContactsService.class);
        dashboardRepository = mock(MarketingDashboardRepository.class);
        when(salonConfig.findById(1)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));
        // No square_customer_id ever collides with another contact's phone in these tests, so an
        // unstubbed customerIdsForPhone (any phone not explicitly given a duplicate) just contributes
        // nothing extra beyond the stored square_customer_id.
        when(square.customerIdsForPhone(anyString())).thenReturn(List.of());
        service = new MarketingAnalyticsService(
                contactsRepository, contactsService, dashboardRepository, aggregator, square, salonConfig,
                adSpendEntryRepository, FIXED_CLOCK);
    }

    private static AttributedService svc(String date, String customerId, String gross) {
        return new AttributedService("p1", "P", date, "FIRST", "Manicure", new BigDecimal(gross),
                BigDecimal.ZERO, new BigDecimal(gross), BigDecimal.ZERO, true, 1, 1, false, "CARD",
                null, "booking-1", customerId, "Customer");
    }

    private static MonthAggregation aggOf(int year, int month, List<AttributedService> services) {
        return new MonthAggregation(year, month, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                services, List.of(), List.of());
    }

    private static AdsAttributedContact contact(String phone, String customerId, Instant firstTouch, String platform) {
        return new AdsAttributedContact(phone, customerId, firstTouch, platform);
    }

    @Test
    @DisplayName("sums gross and counts distinct customers/services for ads-attributed customers only, within range")
    void aggregatesAdsAttributedServicesInRange() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-ads-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-ads-2", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-ads-1", "93.00"),
                svc("2026-07-10", "cust-ads-1", "45.00"), // same customer, second visit
                svc("2026-07-15", "cust-ads-2", "85.00"),
                svc("2026-07-20", "cust-organic", "70.00") // not ads-attributed — excluded
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().customerCount()).isEqualTo(2);
        assertThat(dto.all().serviceCount()).isEqualTo(3);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("223.00");
    }

    @Test
    @DisplayName("excludes services outside the requested date range even within the same month")
    void excludesServicesOutsideDateRange() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-ads-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-ads-1", "93.00"),
                svc("2026-07-20", "cust-ads-1", "85.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().serviceCount()).isEqualTo(1);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("93.00");
    }

    @Test
    @DisplayName("spans multiple calendar months for a custom range crossing a month boundary")
    void spansMultipleMonths() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-ads-1", Instant.parse("2026-06-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 6, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 6, List.of(
                svc("2026-06-28", "cust-ads-1", "93.00")
        )));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-02", "cust-ads-1", "85.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 6, 25), LocalDate.of(2026, 7, 5), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().serviceCount()).isEqualTo(2);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("178.00");
    }

    @Test
    @DisplayName("short-circuits with zeroed results, skipping Square entirely, when no contact is ads-attributed")
    void shortCircuitsWhenNoAdsAttributedContacts() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of());

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().customerCount()).isZero();
        assertThat(dto.all().serviceCount()).isZero();
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("0.00");
        org.mockito.Mockito.verifyNoInteractions(aggregator);
    }

    @Test
    @DisplayName("splits fresh-to-Square vs already-existing customers by comparing Square creation date to first ad touch")
    void splitsFreshFromReturningCustomers() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-fresh", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-returning", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads")));
        // Fresh: Square created the record right at the ad touch. Returning: Square had them a year earlier.
        when(square.customerCreatedAts(Set.of("cust-fresh", "cust-returning")))
                .thenReturn(Map.of(
                        "cust-fresh", Instant.parse("2026-07-05T12:05:00Z"),
                        "cust-returning", Instant.parse("2025-01-01T00:00:00Z")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-10", "cust-fresh", "100.00"),
                svc("2026-07-12", "cust-returning", "80.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().customerCount()).isEqualTo(2);
        assertThat(dto.fresh().customerCount()).isEqualTo(1);
        assertThat(dto.fresh().grossRevenue()).isEqualByComparingTo("100.00");
        assertThat(dto.returning().customerCount()).isEqualTo(1);
        assertThat(dto.returning().grossRevenue()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("treats an unresolvable Square creation date as returning, not fresh")
    void unknownCreationDateIsTreatedAsReturning() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-unknown", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-unknown"))).thenReturn(Map.of()); // lookup failed
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-10", "cust-unknown", "50.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.fresh().customerCount()).isZero();
        assertThat(dto.returning().customerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("treats a customer as fresh when their earliest booking is at/after the ad touch, even if Square's own created_at predates it (a merge artifact)")
    void treatsBookingHistoryAsAuthoritativeOverStaleCreatedAt() {
        // Mirrors a real case: Square merged this customer's profile with a second, separately-created
        // one for the same person, and the surviving record's created_at ended up predating her actual
        // first ad touch — even though her only real booking (and only real history) is from that touch.
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-merged", Instant.parse("2026-07-07T01:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-merged")))
                .thenReturn(Map.of("cust-merged", Instant.parse("2026-07-03T00:00:00Z"))); // predates the ad touch
        var onlyBooking = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "loc-1", "cust-merged", null, null, List.of());
        when(square.bookingsForCustomer(eq("cust-merged"), any())).thenReturn(List.of(onlyBooking));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-07", "cust-merged", "110.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.fresh().customerCount()).isEqualTo(1);
        assertThat(dto.fresh().grossRevenue()).isEqualByComparingTo("110.00");
        assertThat(dto.returning().customerCount()).isZero();
    }

    @Test
    @DisplayName("still treats a customer as returning when their earliest booking predates the ad touch")
    void bookingHistoryBeforeAdTouchIsStillReturning() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-old", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-old")))
                .thenReturn(Map.of("cust-old", Instant.parse("2026-07-05T12:05:00Z"))); // looks fresh...
        var oldBooking = new SquareClient.Booking("bk-0", "ACCEPTED", "2025-01-01T18:00:00Z", null, null,
                "loc-1", "cust-old", null, null, List.of()); // ...but real history predates the ad touch
        when(square.bookingsForCustomer(eq("cust-old"), any())).thenReturn(List.of(oldBooking));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-10", "cust-old", "60.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.returning().customerCount()).isEqualTo(1);
        assertThat(dto.fresh().customerCount()).isZero();
    }

    @Test
    @DisplayName("filters out a platform not in the requested sources")
    void filtersByRequestedSources() {
        when(contactsRepository.findAdsAttributedContacts(Set.of("meta_ads"))).thenReturn(List.of(
                contact("+16195550001", "cust-meta", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-meta", "50.00"),
                svc("2026-07-06", "cust-google", "70.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), Set.of("meta_ads"));

        assertThat(dto.all().customerCount()).isEqualTo(1);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("also resolves a contact's stale square_customer_id via a fresh phone-based lookup")
    void resolvesStaleCustomerIdViaPhone() {
        // The contact was originally linked to cust-old (e.g. from a since-cancelled request); a later
        // appointment got booked/matched against a different Square profile, cust-new, for the same phone.
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-old", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerIdsForPhone("+16195550001")).thenReturn(List.of("cust-new"));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-10", "cust-new", "60.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().customerCount()).isEqualTo(1);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("lists upcoming ads-attributed appointments, one row per booking with segments summed, tagged fresh/returning")
    void listsUpcomingAppointments() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-01-01T00:00:00Z")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of()));

        var seg1 = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var seg2 = new SquareClient.AppointmentSegment("team-1", "var-pedi", 60);
        var future = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-20T18:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg1, seg2));
        var past = new SquareClient.Booking("bk-0", "ACCEPTED", "2026-06-01T18:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg1));
        var cancelled = new SquareClient.Booking("bk-2", "CANCELLED_BY_CUSTOMER", "2026-07-25T18:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg1));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(future, past, cancelled));
        when(square.catalogPrices(List.of("var-mani", "var-pedi")))
                .thenReturn(Map.of("var-mani", new BigDecimal("50.00"), "var-pedi", new BigDecimal("70.00")));
        when(square.catalogNames(List.of("var-mani", "var-pedi")))
                .thenReturn(Map.of("var-mani", "Manicure", "var-pedi", "Pedicure"));
        when(square.customerNames(Set.of("cust-1"))).thenReturn(Map.of("cust-1", "Jane Doe"));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.upcoming()).hasSize(1);
        MarketingAnalyticsDto.UpcomingAppointment appt = dto.upcoming().get(0);
        assertThat(appt.customerName()).isEqualTo("Jane Doe");
        assertThat(appt.serviceName()).isEqualTo("Manicure + Pedicure");
        assertThat(appt.price()).isEqualByComparingTo("120.00");
        assertThat(appt.freshFromAds()).isTrue();
    }

    @Test
    @DisplayName("still shows a same-day appointment as upcoming even though its exact start time has already passed")
    void showsSameDayAppointmentEvenIfStartTimeAlreadyPassed() {
        // FIXED_CLOCK is 2026-07-07T12:00:00Z (noon) — this booking started at 2am the same day.
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of()));

        var seg = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var earlierToday = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-07T02:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(earlierToday));
        when(square.catalogPrices(List.of("var-mani"))).thenReturn(Map.of("var-mani", new BigDecimal("50.00")));
        when(square.catalogNames(List.of("var-mani"))).thenReturn(Map.of("var-mani", "Manicure"));
        when(square.customerNames(Set.of("cust-1"))).thenReturn(Map.of("cust-1", "Fowsiyo"));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.upcoming()).hasSize(1);
        assertThat(dto.upcoming().get(0).customerName()).isEqualTo("Fowsiyo");
    }

    @Test
    @DisplayName("excludes a same-day appointment from upcoming once it's already been paid this month")
    void excludesAlreadyPaidBookingFromUpcoming() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads")));
        // This month's aggregation already has a paid service tied to bk-1 — same booking as below.
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                new AttributedService("p1", "P", "2026-07-07", "FIRST", "Manicure", new BigDecimal("85.00"),
                        BigDecimal.ZERO, new BigDecimal("85.00"), BigDecimal.ZERO, true, 1, 1, false, "CARD",
                        null, "bk-1", "cust-1", "Customer")
        )));

        var seg = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var paidToday = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-07T02:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(paidToday));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.upcoming()).isEmpty();
        assertThat(dto.all().customerCount()).isEqualTo(1);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("ad spend is zero when no page (slug) is selected — pooled-pages view has nothing unambiguous to show")
    void adSpendDefaultsToZeroWithNoSlug() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of());

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.adSpendThisMonth()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("ad spend reflects whatever was entered for the current month, for the selected page")
    void adSpendReflectsStoredValue() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of());
        when(adSpendEntryRepository.findOverlapping("mani", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7)))
                .thenReturn(List.of(AdSpendEntry.builder().id(1L).landingPageSlug("mani")
                        .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 7))
                        .amountSpent(new BigDecimal("250.00")).build()));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY, "mani");

        assertThat(dto.adSpendThisMonth()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("createAdSpendEntry rounds to two decimal places and saves the entered-by owner")
    void createAdSpendEntryRoundsAmount() {
        when(adSpendEntryRepository.save(org.mockito.ArgumentMatchers.any(AdSpendEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AdSpendEntry saved = service.createAdSpendEntry(
                "mani", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), new BigDecimal("199.999"), "owner1");

        assertThat(saved.getAmountSpent()).isEqualByComparingTo("200.00");
        org.mockito.Mockito.verify(adSpendEntryRepository).save(org.mockito.ArgumentMatchers.argThat(e ->
                "mani".equals(e.getLandingPageSlug())
                        && e.getPeriodStart().equals(LocalDate.of(2026, 7, 1))
                        && e.getPeriodEnd().equals(LocalDate.of(2026, 7, 7))
                        && e.getAmountSpent().compareTo(new BigDecimal("200.00")) == 0
                        && "owner1".equals(e.getEnteredBy())));
    }

    @Test
    @DisplayName("updateAdSpendEntry edits an existing entry in place and rounds the new amount")
    void updateAdSpendEntryEditsInPlace() {
        AdSpendEntry existing = AdSpendEntry.builder().id(5L).landingPageSlug("mani")
                .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 7))
                .amountSpent(new BigDecimal("100.00")).enteredBy("owner1").build();
        when(adSpendEntryRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(adSpendEntryRepository.save(org.mockito.ArgumentMatchers.any(AdSpendEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        java.util.Optional<AdSpendEntry> result = service.updateAdSpendEntry(
                5L, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 14), new BigDecimal("249.999"));

        assertThat(result).isPresent();
        assertThat(result.get().getPeriodStart()).isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(result.get().getPeriodEnd()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(result.get().getAmountSpent()).isEqualByComparingTo("250.00");
        // landingPageSlug/enteredBy are untouched by an edit.
        assertThat(result.get().getLandingPageSlug()).isEqualTo("mani");
        assertThat(result.get().getEnteredBy()).isEqualTo("owner1");
    }

    @Test
    @DisplayName("updateAdSpendEntry returns empty for a non-existent id, doesn't call save")
    void updateAdSpendEntryMissingReturnsEmpty() {
        when(adSpendEntryRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThat(service.updateAdSpendEntry(99L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7),
                new BigDecimal("10.00"))).isEmpty();
        org.mockito.Mockito.verify(adSpendEntryRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("deleteAdSpendEntry deletes an existing entry and returns true")
    void deleteAdSpendEntryDeletesExisting() {
        when(adSpendEntryRepository.existsById(5L)).thenReturn(true);

        assertThat(service.deleteAdSpendEntry(5L)).isTrue();
        org.mockito.Mockito.verify(adSpendEntryRepository).deleteById(5L);
    }

    @Test
    @DisplayName("deleteAdSpendEntry returns false for a non-existent id, doesn't call deleteById")
    void deleteAdSpendEntryMissingReturnsFalse() {
        when(adSpendEntryRepository.existsById(99L)).thenReturn(false);

        assertThat(service.deleteAdSpendEntry(99L)).isFalse();
        org.mockito.Mockito.verify(adSpendEntryRepository, org.mockito.Mockito.never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("analytics() folds a manager-follow-up appointment into the completed list, same as adsReport (design.md D6)")
    void analyticsMergesFollowUpIntoCompletedList() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-07-01T00:05:00Z")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of()));

        java.util.UUID pageId = java.util.UUID.randomUUID();
        when(dashboardRepository.findLandingPageId("mani")).thenReturn(java.util.Optional.of(pageId));
        when(dashboardRepository.findAttributedBookingIds(pageId, null)).thenReturn(Set.of());
        var followUpAppt = new com.salonreview.web.dto.MarketingContactDto.Appointment(
                "bk-followup", "ACCEPTED", Instant.parse("2026-07-05T18:00:00Z"), "Manicure",
                new BigDecimal("85.00"), null, "CARD", new BigDecimal("85.00"),
                null, null, null, null, null, null);
        when(contactsService.followUpAppointments("mani", null, Set.of())).thenReturn(List.of(
                new MarketingContactsService.FollowUpAppointment("cust-1", followUpAppt)));
        when(square.customerNames(Set.of("cust-1"))).thenReturn(Map.of("cust-1", "Jane Doe"));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY, "mani");

        assertThat(dto.completed()).hasSize(1);
        assertThat(dto.completed().get(0).collected()).isEqualByComparingTo("85.00");
        assertThat(dto.completed().get(0).customerName()).isEqualTo("Jane Doe");
    }

    @Test
    @DisplayName("a follow-up appointment already counted by the tracked flow (no marketing.attribution row, "
            + "but already surfaced via collectServices) is not re-added — regression guard for the "
            + "same-customer-appears-twice breakdown bug")
    void analyticsDoesNotDoubleCountFollowUpAlreadyInTrackedFlow() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-07-01T00:05:00Z")));
        // The tracked flow already found this booking via SquareMonthAggregator/payroll matching —
        // it never depends on marketing.attribution having a row for it.
        AttributedService trackedService = new AttributedService("p1", "P", "2026-07-05", "FIRST", "Manicure",
                new BigDecimal("85.00"), BigDecimal.ZERO, new BigDecimal("85.00"), BigDecimal.ZERO, true, 1, 1,
                false, "CARD", null, "bk-shared", "cust-1", "Customer");
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00")))
                .thenReturn(aggOf(2026, 7, List.of(trackedService)));
        when(square.customerNames(Set.of("cust-1"))).thenReturn(Map.of("cust-1", "Jane Doe"));

        java.util.UUID pageId = java.util.UUID.randomUUID();
        when(dashboardRepository.findLandingPageId("mani")).thenReturn(java.util.Optional.of(pageId));
        // marketing.attribution has no row for this booking (e.g. attribution capture missed it) —
        // by that check alone, follow-up detection would treat it as "uncounted".
        when(dashboardRepository.findAttributedBookingIds(pageId, null)).thenReturn(Set.of());
        var sameBooking = new com.salonreview.web.dto.MarketingContactDto.Appointment(
                "bk-shared", "ACCEPTED", Instant.parse("2026-07-05T18:00:00Z"), "Manicure",
                new BigDecimal("85.00"), null, "CARD", new BigDecimal("85.00"),
                null, null, null, null, null, null);
        when(contactsService.followUpAppointments("mani", null, Set.of())).thenReturn(List.of(
                new MarketingContactsService.FollowUpAppointment("cust-1", sameBooking)));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY, "mani");

        assertThat(dto.completed()).hasSize(1);
        assertThat(dto.completed().get(0).collected()).isEqualByComparingTo("85.00");
        assertThat(dto.completed().get(0).customerName()).isEqualTo("Jane Doe");
    }

    @Test
    @DisplayName("default (no slug) call still uses the exact no-arg contacts query — regression guard for byte-identical default behavior")
    void defaultCallUsesNoArgContactsQuery() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-ads-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-ads-1", "93.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("93.00");
        org.mockito.Mockito.verify(contactsRepository).findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY);
        org.mockito.Mockito.verify(contactsRepository, org.mockito.Mockito.never())
                .findAdsAttributedContacts(org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("a slug is passed straight through to the ads-attributed-contacts query")
    void slugIsPassedToAdsQuery() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "home")).thenReturn(List.of(
                contact("+16195550001", "cust-home-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-home-1", "50.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY, "home");

        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("50.00");
        org.mockito.Mockito.verify(contactsRepository).findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "home");
    }

    @Test
    @DisplayName("ALL traffic mode includes organic/direct contacts a plain ads query would exclude")
    void allTrafficModeIncludesOrganicContacts() {
        when(contactsRepository.findAllAttributedContacts(null)).thenReturn(List.of(
                contact("+16195550001", "cust-ads-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-organic-1", Instant.parse("2026-07-01T00:00:00Z"), null)));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-ads-1", "93.00"),
                svc("2026-07-06", "cust-organic-1", "40.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), MarketingAnalyticsService.ALL_SOURCES);

        assertThat(dto.all().customerCount()).isEqualTo(2);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("133.00");
        org.mockito.Mockito.verify(contactsRepository, org.mockito.Mockito.never()).findAdsAttributedContacts(org.mockito.ArgumentMatchers.anySet());
        org.mockito.Mockito.verify(contactsRepository, org.mockito.Mockito.never())
                .findAdsAttributedContacts(org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("a phone-lookup failure for one contact doesn't fail the whole response — other contacts still aggregate")
    void phoneLookupFailureIsContained() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-broken", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-ok", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerIdsForPhone("+16195550001"))
                .thenThrow(new RuntimeException("429 Too Many Requests"));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-broken", "93.00"),
                svc("2026-07-06", "cust-ok", "40.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.all().customerCount()).isEqualTo(2);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("133.00");
    }

    @Test
    @DisplayName("a customerCreatedAts failure falls back to the booking-history freshness check")
    void customerCreatedAtsFailureFallsBackToBookingHistory() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1"))).thenThrow(new RuntimeException("429 Too Many Requests"));
        var onlyBooking = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-07T21:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of());
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(onlyBooking));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-07", "cust-1", "110.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.fresh().customerCount()).isEqualTo(1);
        assertThat(dto.returning().customerCount()).isZero();
    }

    @Test
    @DisplayName("a bookingsForCustomer failure during the freshness check falls back to created_at")
    void bookingsForCustomerFailureFallsBackToCreatedAt() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2025-01-01T00:00:00Z"))); // predates ad touch -> returning
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenThrow(new RuntimeException("429 Too Many Requests"));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-10", "cust-1", "60.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.returning().customerCount()).isEqualTo(1);
        assertThat(dto.fresh().customerCount()).isZero();
    }

    @Test
    @DisplayName("both freshness signals unavailable falls back to conservative 'returning', without throwing")
    void bothFreshnessSignalsUnavailableIsReturning() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-05T12:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1"))).thenThrow(new RuntimeException("429 Too Many Requests"));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenThrow(new RuntimeException("429 Too Many Requests"));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-10", "cust-1", "60.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.returning().customerCount()).isEqualTo(1);
        assertThat(dto.fresh().customerCount()).isZero();
    }

    @Test
    @DisplayName("an upcoming-appointments lookup failure for one customer excludes just that customer, not the whole list")
    void upcomingAppointmentsFailureIsPerCustomer() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-broken", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-ok", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of()));

        var seg = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var okFuture = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-20T18:00:00Z", null, null,
                "loc-1", "cust-ok", null, null, List.of(seg));
        when(square.bookingsForCustomer(eq("cust-broken"), any())).thenThrow(new RuntimeException("429 Too Many Requests"));
        when(square.bookingsForCustomer(eq("cust-ok"), any())).thenReturn(List.of(okFuture));
        when(square.catalogPrices(List.of("var-mani"))).thenReturn(Map.of("var-mani", new BigDecimal("50.00")));
        when(square.catalogNames(List.of("var-mani"))).thenReturn(Map.of("var-mani", "Manicure"));
        when(square.customerNames(any())).thenReturn(Map.of("cust-ok", "Jane Doe"));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.upcoming()).hasSize(1);
        assertThat(dto.upcoming().get(0).customerName()).isEqualTo("Jane Doe");
    }

    @Test
    @DisplayName("lists completed appointments within range with real collected amount and payment channel")
    void listsCompletedAppointmentsWithCollectedAmount() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-cash", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-card", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerNames(any())).thenReturn(Map.of("cust-cash", "Cash Customer", "cust-card", "Card Customer"));
        var cashLine = new AttributedService("p1", "P", "2026-07-05", "FIRST", "Manicure",
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CASH", null, "bk-cash", "cust-cash", null);
        var cardLine = new AttributedService("p1", "P", "2026-07-10", "FIRST", "Pedicure",
                new BigDecimal("60.00"), BigDecimal.ZERO, new BigDecimal("54.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "bk-card", "cust-card", null);
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(cashLine, cardLine)));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.completed()).hasSize(2);
        var byBooking = dto.completed().stream()
                .collect(java.util.stream.Collectors.toMap(MarketingAnalyticsDto.CompletedAppointment::customerName, c -> c));
        assertThat(byBooking.get("Cash Customer").paymentChannel()).isEqualTo("CASH");
        assertThat(byBooking.get("Cash Customer").collected()).isEqualByComparingTo("50.00");
        assertThat(byBooking.get("Card Customer").paymentChannel()).isEqualTo("CARD");
        assertThat(byBooking.get("Card Customer").collected()).isEqualByComparingTo("54.00");
    }

    @Test
    @DisplayName("sums a multi-service booking's lines into one completed-appointment row")
    void completedAppointmentsSumMultiServiceBooking() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerNames(any())).thenReturn(Map.of("cust-1", "Jane Doe"));
        var maniLine = new AttributedService("p1", "P", "2026-07-05", "FIRST", "Manicure",
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "bk-1", "cust-1", null);
        var pediLine = new AttributedService("p1", "P", "2026-07-05", "FIRST", "Pedicure",
                new BigDecimal("70.00"), BigDecimal.ZERO, new BigDecimal("63.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "bk-1", "cust-1", null);
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(maniLine, pediLine)));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.completed()).hasSize(1);
        assertThat(dto.completed().get(0).serviceName()).isEqualTo("Manicure + Pedicure");
        assertThat(dto.completed().get(0).collected()).isEqualByComparingTo("113.00");
    }

    @Test
    @DisplayName("excludes owner/family comps from the completed-appointments list")
    void completedAppointmentsExcludeComps() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        var compLine = new AttributedService("p1", "P", "2026-07-05", "FIRST", "owner comp",
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO,
                true, 1, 1, false, "COMP", null, "bk-comp", "cust-1", null);
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(compLine)));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.completed()).isEmpty();
    }

    @Test
    @DisplayName("ALL traffic mode combined with a slug scopes to that landing page")
    void allTrafficModeWithSlugScopesToPage() {
        when(contactsRepository.findAllAttributedContacts("home")).thenReturn(List.of(
                contact("+16195550002", "cust-organic-1", Instant.parse("2026-07-01T00:00:00Z"), null)));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-06", "cust-organic-1", "40.00"))));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), MarketingAnalyticsService.ALL_SOURCES, "home");

        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("40.00");
        org.mockito.Mockito.verify(contactsRepository).findAllAttributedContacts("home");
    }

    @Test
    @DisplayName("adsReport (monthly): one row per calendar month, most recent first, with real (non-estimated) ad spend")
    void adsReportMonthlyBucketsByCalendarMonth() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of(
                contact("+16195550001", "cust-june", Instant.parse("2026-06-01T00:00:00Z"), "meta_ads"),
                contact("+16195550002", "cust-july", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(any())).thenReturn(Map.of(
                "cust-june", Instant.parse("2026-06-01T00:05:00Z"),
                "cust-july", Instant.parse("2026-07-01T00:05:00Z")));
        var juneLine = new AttributedService("p1", "P", "2026-06-10", "FIRST", "Manicure",
                new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("80.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "booking-june", "cust-june", null);
        var julyLine = new AttributedService("p1", "P", "2026-07-10", "FIRST", "Manicure",
                new BigDecimal("120.00"), BigDecimal.ZERO, new BigDecimal("120.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "booking-july", "cust-july", null);
        when(aggregator.aggregate(2026, 6, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 6, List.of(juneLine)));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(julyLine)));
        when(adSpendEntryRepository.findOverlapping("mani", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of(AdSpendEntry.builder().id(1L).landingPageSlug("mani")
                        .periodStart(LocalDate.of(2026, 6, 1)).periodEnd(LocalDate.of(2026, 6, 30))
                        .amountSpent(new BigDecimal("200.00")).build()));
        when(adSpendEntryRepository.findOverlapping("mani", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of(AdSpendEntry.builder().id(2L).landingPageSlug("mani")
                        .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                        .amountSpent(new BigDecimal("310.00")).build()));

        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31),
                TrafficSourceSql.ADS_ONLY, "mani", MarketingAnalyticsService.PeriodKind.MONTH);

        assertThat(dto.periodType()).isEqualTo("MONTH");
        assertThat(dto.periods()).hasSize(2);
        PeriodRow july = dto.periods().get(0);
        PeriodRow june = dto.periods().get(1);
        assertThat(july.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(july.adSpend()).isEqualByComparingTo("310.00");
        assertThat(july.adSpendEstimated()).isFalse();
        assertThat(july.revenueCollected()).isEqualByComparingTo("120.00");
        assertThat(july.completedAppointments()).isEqualTo(1);
        assertThat(july.customersCreated()).isEqualTo(1);
        assertThat(june.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(june.adSpend()).isEqualByComparingTo("200.00");
        assertThat(june.revenueCollected()).isEqualByComparingTo("80.00");

        assertThat(dto.totals().adSpend()).isEqualByComparingTo("510.00");
        assertThat(dto.totals().revenueCollected()).isEqualByComparingTo("200.00");
        assertThat(dto.totals().completedAppointments()).isEqualTo(2);
    }

    @Test
    @DisplayName("adsReport (weekly): a week straddling two months blends each month's own prorated daily rate")
    void adsReportWeeklyProratesAcrossMonthBoundary() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of());
        when(adSpendEntryRepository.findOverlapping("mani", LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2)))
                .thenReturn(List.of(
                        // 31 days -> $10.00/day
                        AdSpendEntry.builder().id(1L).landingPageSlug("mani")
                                .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                                .amountSpent(new BigDecimal("310.00")).build(),
                        // 31 days -> $4.00/day
                        AdSpendEntry.builder().id(2L).landingPageSlug("mani")
                                .periodStart(LocalDate.of(2026, 8, 1)).periodEnd(LocalDate.of(2026, 8, 31))
                                .amountSpent(new BigDecimal("124.00")).build()));

        // 2026-07-27 is a Monday and 2026-08-02 is a Sunday, so this is exactly one aligned week:
        // 5 July days ($10/day) + 2 August days ($4/day) = 58.00.
        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2),
                TrafficSourceSql.ADS_ONLY, "mani", MarketingAnalyticsService.PeriodKind.WEEK);

        assertThat(dto.periodType()).isEqualTo("WEEK");
        assertThat(dto.periods()).hasSize(1);
        PeriodRow week = dto.periods().get(0);
        assertThat(week.periodStart()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(week.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(week.adSpend()).isEqualByComparingTo("58.00");
        assertThat(week.adSpendEstimated()).isTrue();
    }

    @Test
    @DisplayName("adsReport still shows ad spend for a period with zero ads-attributed customers")
    void adsReportShowsSpendEvenWithNoMatchedCustomers() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of());
        when(adSpendEntryRepository.findOverlapping("mani", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of(AdSpendEntry.builder().id(1L).landingPageSlug("mani")
                        .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                        .amountSpent(new BigDecimal("310.00")).build()));

        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                TrafficSourceSql.ADS_ONLY, "mani", MarketingAnalyticsService.PeriodKind.MONTH);

        assertThat(dto.periods()).hasSize(1);
        PeriodRow row = dto.periods().get(0);
        assertThat(row.adSpend()).isEqualByComparingTo("310.00");
        assertThat(row.revenueCollected()).isEqualByComparingTo("0.00");
        assertThat(row.completedAppointments()).isZero();
        assertThat(row.customersCreated()).isZero();
    }

    @Test
    @DisplayName("adsReport (month-to-date): exactly one row for [1st-of-month, today] — a service dated after today doesn't leak in")
    void adsReportMonthToDateDoesNotLeakPastToday() {
        // FIXED_CLOCK's "today" is 2026-07-07.
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(any()))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-07-01T00:05:00Z")));
        var inMonthToDate = new AttributedService("p1", "P", "2026-07-05", "FIRST", "Manicure",
                new BigDecimal("70.00"), BigDecimal.ZERO, new BigDecimal("70.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "booking-1", "cust-1", null);
        var afterToday = new AttributedService("p1", "P", "2026-07-10", "FIRST", "Manicure",
                new BigDecimal("90.00"), BigDecimal.ZERO, new BigDecimal("90.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "booking-2", "cust-1", null);
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00")))
                .thenReturn(aggOf(2026, 7, List.of(inMonthToDate, afterToday)));

        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                TrafficSourceSql.ADS_ONLY, null, MarketingAnalyticsService.PeriodKind.MONTH_TO_DATE);

        assertThat(dto.periodType()).isEqualTo("MONTH_TO_DATE");
        assertThat(dto.periods()).hasSize(1);
        PeriodRow row = dto.periods().get(0);
        assertThat(row.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(row.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(row.revenueCollected()).isEqualByComparingTo("70.00");
        assertThat(row.completedAppointments()).isEqualTo(1);
    }

    @Test
    @DisplayName("adsReport buckets a still-upcoming appointment's anticipated revenue into the period it's scheduled in")
    void adsReportBucketsAnticipatedRevenue() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-01-01T00:00:00Z")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of()));

        var seg = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var future = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-20T18:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(future));
        when(square.catalogPrices(List.of("var-mani"))).thenReturn(Map.of("var-mani", new BigDecimal("85.00")));
        when(square.catalogNames(List.of("var-mani"))).thenReturn(Map.of("var-mani", "Manicure"));
        when(square.customerNames(any())).thenReturn(Map.of("cust-1", "Jane Doe"));

        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                TrafficSourceSql.ADS_ONLY, null, MarketingAnalyticsService.PeriodKind.MONTH);

        assertThat(dto.periods()).hasSize(1);
        assertThat(dto.periods().get(0).anticipatedRevenue()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("newCustomerBookedAhead sums a period's new-customer cohort's future bookings, "
            + "even when those bookings fall in a later period than anticipatedRevenue counts")
    void adsReportComputesNewCustomerBookedAheadAcrossPeriods() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-07-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-07-01T00:05:00Z")));
        // cust-1's first (fresh, tracked-flow) visit lands in July — this is exactly the
        // customersCreated cohort newCustomerBookedAhead is scoped to.
        var julyLine = new AttributedService("p1", "P", "2026-07-10", "FIRST", "Manicure",
                new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("80.00"), BigDecimal.ZERO,
                true, 1, 1, false, "CARD", null, "booking-july", "cust-1", null);
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(julyLine)));

        // Their only upcoming appointment is in August — outside July's own date range, so it
        // contributes nothing to July's anticipatedRevenue, but it's still this cohort's booked-ahead value.
        var seg = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var augustBooking = new SquareClient.Booking("bk-august", "ACCEPTED", "2026-08-05T18:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(augustBooking));
        when(square.catalogPrices(List.of("var-mani"))).thenReturn(Map.of("var-mani", new BigDecimal("150.00")));
        when(square.catalogNames(List.of("var-mani"))).thenReturn(Map.of("var-mani", "Manicure"));
        when(square.customerNames(any())).thenReturn(Map.of("cust-1", "Jane Doe"));

        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                TrafficSourceSql.ADS_ONLY, "mani", MarketingAnalyticsService.PeriodKind.MONTH);

        assertThat(dto.periods()).hasSize(1);
        PeriodRow july = dto.periods().get(0);
        assertThat(july.customersCreated()).isEqualTo(1);
        assertThat(july.anticipatedRevenue()).isEqualByComparingTo("0.00");
        assertThat(july.newCustomerBookedAhead()).isEqualByComparingTo("150.00");
        assertThat(dto.totals().newCustomerBookedAhead()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("adsReport() does not double-count a follow-up appointment already found by the "
            + "upcoming-appointments scan — regression guard for the same-customer-twice-in-Upcoming bug")
    void adsReportDoesNotDoubleCountFollowUpAlreadyUpcoming() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY, "mani")).thenReturn(List.of(
                contact("+16195550001", "cust-1", Instant.parse("2026-01-01T00:00:00Z"), "meta_ads")));
        when(square.customerCreatedAts(Set.of("cust-1")))
                .thenReturn(Map.of("cust-1", Instant.parse("2026-01-01T00:00:00Z")));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of()));

        var seg = new SquareClient.AppointmentSegment("team-1", "var-mani", 60);
        var future = new SquareClient.Booking("bk-1", "ACCEPTED", "2026-07-20T18:00:00Z", null, null,
                "loc-1", "cust-1", null, null, List.of(seg));
        when(square.bookingsForCustomer(eq("cust-1"), any())).thenReturn(List.of(future));
        when(square.catalogPrices(List.of("var-mani"))).thenReturn(Map.of("var-mani", new BigDecimal("85.00")));
        when(square.catalogNames(List.of("var-mani"))).thenReturn(Map.of("var-mani", "Manicure"));
        when(square.customerNames(any())).thenReturn(Map.of("cust-1", "Jane Doe"));

        java.util.UUID pageId = java.util.UUID.randomUUID();
        when(dashboardRepository.findLandingPageId("mani")).thenReturn(java.util.Optional.of(pageId));
        // marketing.attribution has no row for this booking, same gap as the completed-side bug —
        // by that check alone, follow-up detection would treat "bk-1" as undiscovered.
        when(dashboardRepository.findAttributedBookingIds(pageId, null)).thenReturn(Set.of());
        var sameBooking = new com.salonreview.web.dto.MarketingContactDto.Appointment(
                "bk-1", "ACCEPTED", Instant.parse("2026-07-20T18:00:00Z"), "Manicure",
                new BigDecimal("85.00"), null, null, null, null, null, null, null, null, null);
        when(contactsService.followUpAppointments("mani", null, Set.of())).thenReturn(List.of(
                new MarketingContactsService.FollowUpAppointment("cust-1", sameBooking)));

        MarketingAdsReportDto dto = service.adsReport(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                TrafficSourceSql.ADS_ONLY, "mani", MarketingAnalyticsService.PeriodKind.MONTH);

        assertThat(dto.periods()).hasSize(1);
        assertThat(dto.periods().get(0).anticipatedRevenue()).isEqualByComparingTo("85.00");
    }
}
