package com.salonreview.marketing;

import com.salonreview.domain.AdSpend;
import com.salonreview.domain.SalonConfig;
import com.salonreview.marketing.MarketingContactsRepository.AdsAttributedContact;
import com.salonreview.repo.AdSpendRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingAnalyticsServiceTest {

    private MarketingContactsRepository contactsRepository;
    private SquareMonthAggregator aggregator;
    private SquareClient square;
    private SalonConfigRepository salonConfig;
    private AdSpendRepository adSpendRepository;
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
        adSpendRepository = mock(AdSpendRepository.class);
        when(salonConfig.findById(1)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));
        // No square_customer_id ever collides with another contact's phone in these tests, so an
        // unstubbed customerIdsForPhone (any phone not explicitly given a duplicate) just contributes
        // nothing extra beyond the stored square_customer_id.
        when(square.customerIdsForPhone(anyString())).thenReturn(List.of());
        service = new MarketingAnalyticsService(
                contactsRepository, aggregator, square, salonConfig, adSpendRepository, FIXED_CLOCK);
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
        when(square.bookingsForCustomer("cust-merged")).thenReturn(List.of(onlyBooking));
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
        when(square.bookingsForCustomer("cust-old")).thenReturn(List.of(oldBooking));
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
        when(square.bookingsForCustomer("cust-1")).thenReturn(List.of(future, past, cancelled));
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
        when(square.bookingsForCustomer("cust-1")).thenReturn(List.of(earlierToday));
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
        when(square.bookingsForCustomer("cust-1")).thenReturn(List.of(paidToday));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.upcoming()).isEmpty();
        assertThat(dto.all().customerCount()).isEqualTo(1);
        assertThat(dto.all().grossRevenue()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("ad spend defaults to zero when nothing has been entered for the current month")
    void adSpendDefaultsToZero() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of());
        when(adSpendRepository.findByYearAndMonth(2026, 7)).thenReturn(Optional.empty()); // FIXED_CLOCK's "today"

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.adSpendThisMonth()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("ad spend reflects whatever was entered for the current month")
    void adSpendReflectsStoredValue() {
        when(contactsRepository.findAdsAttributedContacts(TrafficSourceSql.ADS_ONLY)).thenReturn(List.of());
        when(adSpendRepository.findByYearAndMonth(2026, 7)).thenReturn(Optional.of( // FIXED_CLOCK's "today"
                AdSpend.builder().id(1L).year(2026).month(7).amountSpent(new BigDecimal("250.00")).build()));

        MarketingAnalyticsDto dto = service.analytics(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), TrafficSourceSql.ADS_ONLY);

        assertThat(dto.adSpendThisMonth()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("saveAdSpend upserts and rounds to two decimal places")
    void saveAdSpendUpserts() {
        when(adSpendRepository.findByYearAndMonth(2026, 7)).thenReturn(Optional.empty());

        BigDecimal saved = service.saveAdSpend(2026, 7, new BigDecimal("199.999"), "owner1");

        assertThat(saved).isEqualByComparingTo("200.00");
        org.mockito.Mockito.verify(adSpendRepository).save(org.mockito.ArgumentMatchers.argThat(a ->
                a.getYear() == 2026 && a.getMonth() == 7
                        && a.getAmountSpent().compareTo(new BigDecimal("200.00")) == 0
                        && "owner1".equals(a.getUpdatedBy())));
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
}
