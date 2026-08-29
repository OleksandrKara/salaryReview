package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.OwnerCustomer;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient.AppliedDiscount;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Invoice;
import com.salonreview.square.SquareClient.Money;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderDiscount;
import com.salonreview.square.SquareClient.OrderLineItem;
import com.salonreview.square.SquareClient.Payment;
import com.salonreview.square.SquareClient.PaymentRequest;
import com.salonreview.square.SquareClient.Tender;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end characterization tests for {@link SquareMonthAggregator#aggregate} — pinning down
 * today's exact behavior with a fake {@link SquareClient} before Phase 2f swaps its raw-data
 * source from live Square calls to the local mirror. Before this file, the only coverage of this
 * ~1200-line class was 13 tests against two static helpers ({@code paymentsByBookingId},
 * {@code detectOrphanPayments}) — the core matching/cash-note/discount/comp/suspicious/cancellation
 * logic had zero regression tests. Not exhaustive of every edge case in the class, but covers one
 * representative scenario per major behavior so a data-source swap that silently changes behavior
 * gets caught here first.
 */
class SquareMonthAggregatorCharacterizationTest {

    private static final Long BUSINESS_ID = 1L;
    private static final BigDecimal CUTOFF = new BigDecimal("60.00");

    private SquareClient square;
    private OwnerCustomerRepository ownerCustomers;
    private SalonConfigRepository salonConfigRepo;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        CashNoteParser cashNotes = new CashNoteParser(); // pure logic, no dependencies — use the real thing
        ownerCustomers = mock(OwnerCustomerRepository.class);
        when(ownerCustomers.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of());
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        salonConfigRepo = mock(SalonConfigRepository.class);
        setSalonConfig(false, null, null);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(
                new SquareClient.TeamMember("TM1", "Susan", "A.", "ACTIVE", false, null, null),
                new SquareClient.TeamMember("TM2", "Dana", "B.", "ACTIVE", false, null, null)));
        when(square.canonicalCustomerIds(any())).thenAnswer(inv -> {
            Set<String> ids = inv.getArgument(0);
            Map<String, String> identity = new HashMap<>();
            for (String id : ids) identity.put(id, id);
            return identity;
        });
        when(square.bookings(any(), any())).thenReturn(List.of());
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.payments(any(), any())).thenReturn(List.of());
        when(square.catalogPrices(any())).thenReturn(Map.of());
        when(square.catalogNames(any())).thenReturn(Map.of());
        when(square.customerNames(any())).thenReturn(Map.of());

        aggregator = new SquareMonthAggregator(squareClientProvider, cashNotes, ownerCustomers,
                currentBusinessContext, salonConfigRepo,
                mock(com.salonreview.repo.SquareBookingMirrorRepository.class), mock(com.salonreview.repo.SquareOrderMirrorRepository.class),
                mock(com.salonreview.repo.SquarePaymentMirrorRepository.class));
    }

    private void setSalonConfig(boolean restrictDiscountCoverage, String coveredDiscountNames, BigDecimal noShowFeeAmount) {
        SalonConfig sc = SalonConfig.builder().businessId(BUSINESS_ID).ownerShortName("o")
                .tierServiceThreshold(0).servicePriceCutoff(CUTOFF)
                .baseCommissionRate(BigDecimal.ZERO).tierCommissionRate(BigDecimal.ZERO)
                .cardTipFeeRate(BigDecimal.ZERO).tierEnabled(false)
                .restrictDiscountCoverage(restrictDiscountCoverage).coveredDiscountNames(coveredDiscountNames)
                .noShowFeeAmount(noShowFeeAmount).build();
        when(salonConfigRepo.findByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(sc));
    }

    // ---------------------------------------------------------------- fixture builders

    private static Booking booking(String id, String status, String customerId, String startAt, String updatedAt,
                                    String sellerNote, AppointmentSegment... segments) {
        return new Booking(id, status, startAt, startAt, updatedAt, "LOC1", customerId, sellerNote, null, List.of(segments));
    }

    private static Booking booking(String id, String status, String customerId, String startAt, String sellerNote,
                                    AppointmentSegment... segments) {
        return booking(id, status, customerId, startAt, startAt, sellerNote, segments);
    }

    private static Money money(BigDecimal dollars) {
        return dollars == null ? null : new Money(dollars.movePointRight(2).longValueExact(), "USD");
    }

    private static Order order(String id, String customerId, String closedAt, BigDecimal tip,
                                List<Tender> tenders, List<OrderDiscount> discounts, OrderLineItem... items) {
        return new Order(id, "LOC1", customerId, "COMPLETED", closedAt, closedAt, List.of(items),
                money(tip), null, tenders, null, discounts);
    }

    private static OrderLineItem lineItem(String catalogId, String name, BigDecimal grossPrice, BigDecimal netPrice,
                                          List<AppliedDiscount> appliedDiscounts) {
        BigDecimal discountAmount = grossPrice.subtract(netPrice);
        return new OrderLineItem(catalogId + "-uid", name, "1", catalogId,
                money(grossPrice), money(grossPrice), money(netPrice),
                discountAmount.signum() > 0 ? money(discountAmount) : null, appliedDiscounts);
    }

    private static OrderLineItem lineItem(String catalogId, String name, BigDecimal price) {
        return lineItem(catalogId, name, price, price, null);
    }

    private static ProviderMonth providerMonth(MonthAggregation agg, String providerId) {
        return agg.providers().stream().filter(p -> p.providerId().equals(providerId)).findFirst()
                .orElseThrow(() -> new AssertionError("No ProviderMonth for " + providerId + " in " + agg.providers()));
    }

    // ---------------------------------------------------------------- standard matching

    @Test
    @DisplayName("a booking checked out by card matches its order line and is attributed to the provider as CARD revenue")
    void standardCardOrderMatching() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk1", "ACCEPTED", "CUST1", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord1", "CUST1", "2026-07-10T15:30:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("100.00")))), null,
                        lineItem("VAR1", "Manicure", new BigDecimal("100.00")))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        ProviderMonth pm = providerMonth(agg, "TM1");
        assertThat(pm.firstHalf().cardRevenue()).isEqualByComparingTo("100.00");
        assertThat(pm.firstHalf().countedServices()).isEqualTo(1);
        assertThat(agg.services()).hasSize(1);
        AttributedService line = agg.services().get(0);
        assertThat(line.channel()).isEqualTo("CARD");
        assertThat(line.gross()).isEqualByComparingTo("100.00");
        assertThat(line.net()).isEqualByComparingTo("100.00");
        assertThat(line.bookingId()).isEqualTo("bk1");
    }

    @Test
    @DisplayName("a booking checked out by cash tender is attributed as cash (gross vs. collected split)")
    void cashOrderCheckedOutInSquareIsChannelCash() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk2", "ACCEPTED", "CUST2", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord2", "CUST2", "2026-07-10T15:30:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CASH", money(new BigDecimal("90.00")))), null,
                        lineItem("VAR1", "Manicure", new BigDecimal("100.00"), new BigDecimal("90.00"), null))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        ProviderMonth pm = providerMonth(agg, "TM1");
        assertThat(pm.firstHalf().cashGross()).isEqualByComparingTo("100.00");
        assertThat(pm.firstHalf().cashCollected()).isEqualByComparingTo("90.00");
        assertThat(agg.services().get(0).channel()).isEqualTo("CASH");
    }

    // ---------------------------------------------------------------- cash-note parsing

    @Test
    @DisplayName("a 'cashew $nn' note with no matching order is folded in as a CASH-NOTE line at the written amount")
    void cashNoteCashewStyleWithExplicitAmount() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk3", "ACCEPTED", "CUST3", "2026-07-10T15:00:00Z",
                        "cashew $80", new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.services()).hasSize(1);
        AttributedService line = agg.services().get(0);
        assertThat(line.channel()).isEqualTo("CASH-NOTE");
        assertThat(line.gross()).isEqualByComparingTo("100.00"); // menu price, salon absorbs the gap by default
        assertThat(line.net()).isEqualByComparingTo("80.00");    // what was actually collected
        ProviderMonth pm = providerMonth(agg, "TM1");
        assertThat(pm.firstHalf().cashCollected()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("a Russian 'наличные' note with no amount falls back to the appointment's catalog total")
    void cashNoteNalichStyleFallsBackToCatalogTotal() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk4", "ACCEPTED", "CUST4", "2026-07-10T15:00:00Z",
                        "наличные", new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("75.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        AttributedService line = agg.services().get(0);
        assertThat(line.gross()).isEqualByComparingTo("75.00");
        assertThat(line.net()).isEqualByComparingTo("75.00"); // no written amount -> collected = catalog total, no gap
    }

    @Test
    @DisplayName("a cash note amount exceeding the catalog price is capped at the catalog price, not trusted as an overpayment")
    void cashNoteAmountCappedAtCatalogPrice() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk5", "ACCEPTED", "CUST5", "2026-07-10T15:00:00Z",
                        "cashew $500", new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("80.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        AttributedService line = agg.services().get(0);
        assertThat(line.net()).isEqualByComparingTo("80.00"); // capped, not 500
        assertThat(line.service()).contains("capped");
    }

    @Test
    @DisplayName("a cash-note gap is reclassified from an unmatched same-customer order line within 2 days, not lost as unattributed sales")
    void cashNoteGapMatchReclassifiesUnmatchedLine() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk6", "ACCEPTED", "CUST6", "2026-07-10T15:00:00Z",
                        "cashew $50", new AppointmentSegment("TM1", "VAR1", 60))));
        // An order for the same customer, one day later, for a DIFFERENT service (no booking segment
        // exists for VAR-OTHER) — match() can't tie it to a booking, so it starts out "unmatched".
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord6", "CUST6", "2026-07-11T15:00:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("50.00")))), null,
                        lineItem("VAR-OTHER", "Add-on", new BigDecimal("50.00")))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.unmatched()).isEmpty(); // reclassified, not left as unattributed
        // Two lines for the same visit: the cash-note portion + the auto-matched gap portion.
        assertThat(agg.services()).hasSize(2);
        BigDecimal totalGross = agg.services().stream().map(AttributedService::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalGross).isEqualByComparingTo("100.00"); // full catalog price accounted for, nothing lost
        AttributedService gapLine = agg.services().stream()
                .filter(s -> s.service().contains("auto-matched to cash-note gap")).findFirst().orElseThrow();
        assertThat(gapLine.channel()).isEqualTo("CARD");
        assertThat(gapLine.gross()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("a cash note referencing a real PAID Square invoice credits that portion as a separate CARD deposit line")
    void cashNoteInvoiceLinkingCreditsCardDeposit() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk7", "ACCEPTED", "CUST7", "2026-07-12T15:00:00Z",
                        "cashew $50 invoice #001365 paid", new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("150.00")));
        when(square.invoicesForCustomer("CUST7")).thenReturn(List.of(
                new Invoice("inv1", "001365", "Deposit", "PAID", "2026-07-01T00:00:00Z",
                        List.of(new PaymentRequest(money(new BigDecimal("100.00")))), null)));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.services()).hasSize(2);
        AttributedService cashLine = agg.services().stream().filter(s -> "CASH-NOTE".equals(s.channel())).findFirst().orElseThrow();
        assertThat(cashLine.net()).isEqualByComparingTo("50.00");
        AttributedService depositLine = agg.services().stream()
                .filter(s -> s.service().equals("Deposit invoice (auto-matched)")).findFirst().orElseThrow();
        assertThat(depositLine.channel()).isEqualTo("CARD");
        assertThat(depositLine.gross()).isEqualByComparingTo("100.00");
        BigDecimal totalGross = agg.services().stream().map(AttributedService::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalGross).isEqualByComparingTo("150.00"); // cash-note (50) + deposit (100) = full catalog price
    }

    // ---------------------------------------------------------------- discount-coverage policy

    @Test
    @DisplayName("discount-coverage off (default): the salon absorbs every discount, provider is paid the full menu price")
    void discountAbsorbedByDefaultWhenCoverageOff() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk8", "ACCEPTED", "CUST8", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord8", "CUST8", "2026-07-10T15:30:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("80.00")))), null,
                        lineItem("VAR1", "Manicure", new BigDecimal("100.00"), new BigDecimal("80.00"), null))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        AttributedService line = agg.services().get(0);
        assertThat(line.gross()).isEqualByComparingTo("100.00"); // full menu price — commission basis unaffected
        assertThat(line.discount()).isEqualByComparingTo("20.00");
        assertThat(line.net()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("discount-coverage on, discount name IS covered: same effect as coverage off — salon still absorbs it")
    void discountAbsorbedWhenCoverageOnAndNameCovered() {
        setSalonConfig(true, "first time", null);
        var applied = new AppliedDiscount("ad1", "d1", money(new BigDecimal("20.00")));
        var orderDiscount = new OrderDiscount("d1", "First Time Client Discount", money(new BigDecimal("20.00")));
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk9", "ACCEPTED", "CUST9", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord9", "CUST9", "2026-07-10T15:30:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("80.00")))), List.of(orderDiscount),
                        lineItem("VAR1", "Manicure", new BigDecimal("100.00"), new BigDecimal("80.00"), List.of(applied)))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        AttributedService line = agg.services().get(0);
        assertThat(line.gross()).isEqualByComparingTo("100.00");
        assertThat(line.discount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("discount-coverage on, discount name is NOT covered: it reduces the provider's own commission basis")
    void discountReducesBasisWhenCoverageOnAndNameNotCovered() {
        setSalonConfig(true, "deposit", null); // configured covered name doesn't match the discount below
        var applied = new AppliedDiscount("ad1", "d1", money(new BigDecimal("20.00")));
        var orderDiscount = new OrderDiscount("d1", "Holiday Promo", money(new BigDecimal("20.00")));
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk10", "ACCEPTED", "CUST10", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord10", "CUST10", "2026-07-10T15:30:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("80.00")))), List.of(orderDiscount),
                        lineItem("VAR1", "Manicure", new BigDecimal("100.00"), new BigDecimal("80.00"), List.of(applied)))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        AttributedService line = agg.services().get(0);
        assertThat(line.gross()).isEqualByComparingTo("80.00"); // provider paid only on what was actually collected
        assertThat(line.discount()).isEqualByComparingTo("0.00");
        ProviderMonth pm = providerMonth(agg, "TM1");
        assertThat(pm.firstHalf().cardRevenue()).isEqualByComparingTo("80.00");
    }

    // ---------------------------------------------------------------- owner/family comps

    @Test
    @DisplayName("a service rendered to an owner/family customer with no matching order is credited to the provider at the catalog menu price")
    void ownerCompCreditedWhenNoMatchingOrder() {
        when(ownerCustomers.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                OwnerCustomer.builder().businessId(BUSINESS_ID).squareCustomerId("OWNER1").build()));
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk11", "ACCEPTED", "OWNER1", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));
        when(square.catalogNames(any())).thenReturn(Map.of("VAR1", "Manicure"));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.services()).hasSize(1);
        AttributedService line = agg.services().get(0);
        assertThat(line.channel()).isEqualTo("COMP");
        assertThat(line.gross()).isEqualByComparingTo("90.00");
        assertThat(line.net()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("an owner booking that WAS actually paid via a real order is never also credited as a comp (no double payout)")
    void ownerCompSkippedWhenBookingActuallyPaid() {
        when(ownerCustomers.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                OwnerCustomer.builder().businessId(BUSINESS_ID).squareCustomerId("OWNER2").build()));
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk12", "ACCEPTED", "OWNER2", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord12", "OWNER2", "2026-07-10T15:30:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("90.00")))), null,
                        lineItem("VAR1", "Manicure", new BigDecimal("90.00")))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.services()).hasSize(1); // the real CARD line only, no extra COMP line
        assertThat(agg.services().get(0).channel()).isEqualTo("CARD");
    }

    @Test
    @DisplayName("a future owner booking (appointment hasn't happened yet) is not credited early")
    void futureOwnerBookingNotYetCredited() {
        when(ownerCustomers.findAllByBusinessId(BUSINESS_ID)).thenReturn(List.of(
                OwnerCustomer.builder().businessId(BUSINESS_ID).squareCustomerId("OWNER3").build()));
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk13", "ACCEPTED", "OWNER3", "2099-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2099, 7, CUTOFF);

        assertThat(agg.services()).isEmpty();
    }

    // ---------------------------------------------------------------- suspicious bookings

    @Test
    @DisplayName("a past appointment with no order, no cash note, and no owner exemption is flagged suspicious")
    void suspiciousBookingFlaggedWithNoMoneyTrail() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk14", "ACCEPTED", "CUST14", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.suspicious()).hasSize(1);
        var candidate = agg.suspicious().get(0);
        assertThat(candidate.bookingId()).isEqualTo("bk14");
        assertThat(candidate.providerId()).isEqualTo("TM1");
        assertThat(candidate.customerId()).isEqualTo("CUST14");
    }

    @Test
    @DisplayName("a past appointment is NOT flagged suspicious when the customer has any completed order within 2 days")
    void suspiciousBookingSuppressedByNearbyOrder() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk15", "ACCEPTED", "CUST15", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        // A same-customer order for an unrelated SKU, one day later — no line match, but its mere
        // existence within 2 days is a payment trail.
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord15", "CUST15", "2026-07-11T15:00:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("40.00")))), null,
                        lineItem("VAR-OTHER", "Add-on", new BigDecimal("40.00")))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.suspicious()).isEmpty();
    }

    // ---------------------------------------------------------------- cancelled appointments

    @Test
    @DisplayName("a booking cancelled by the seller AFTER its start time is flagged for owner review")
    void cancelledAfterStartTimeIsFlagged() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk16", "CANCELLED_BY_SELLER", "CUST16",
                        "2026-07-10T15:00:00Z", "2026-07-10T20:00:00Z", (String) null,
                        new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.cancellations()).hasSize(1);
        assertThat(agg.cancellations().get(0).bookingId()).isEqualTo("bk16");
    }

    @Test
    @DisplayName("a cancelled-after-start booking is NOT flagged when a matching cancellation fee was already charged")
    void cancelledSuppressedByNoShowFee() {
        setSalonConfig(false, null, new BigDecimal("25.00"));
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk17", "CANCELLED_BY_SELLER", "CUST17",
                        "2026-07-10T15:00:00Z", "2026-07-10T20:00:00Z", (String) null,
                        new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ordFee17", "CUST17", "2026-07-10T20:05:00Z", BigDecimal.ZERO,
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("25.00")))), null,
                        lineItem("FEE", "Cancelation Policy Fee", new BigDecimal("25.00")))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.cancellations()).isEmpty();
    }

    // ---------------------------------------------------------------- orphan payments

    @Test
    @DisplayName("a completed payment with no linked order is surfaced as an orphan payment with a suggested nearby booking")
    void orphanPaymentDetectedWithSuggestedBooking() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk18", "ACCEPTED", "CUST18", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60))));
        when(square.payments(any(), any())).thenReturn(List.of(
                new Payment("pay18", null, "CUST18", "COMPLETED", "2026-07-11T10:00:00Z",
                        money(new BigDecimal("45.00")), null)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("90.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(agg.orphanPayments()).hasSize(1);
        var orphan = agg.orphanPayments().get(0);
        assertThat(orphan.amount()).isEqualByComparingTo("45.00");
        assertThat(orphan.customerId()).isEqualTo("CUST18");
        assertThat(orphan.suggestedBookingId()).isEqualTo("bk18");
        assertThat(orphan.suggestedProviderId()).isEqualTo("TM1");
    }

    // ---------------------------------------------------------------- tip splitting

    @Test
    @DisplayName("an order tip is split equally across the distinct providers on the ticket")
    void tipSplitAcrossTwoProvidersOnSameOrder() {
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bk19", "ACCEPTED", "CUST19", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM1", "VAR1", 60)),
                booking("bk20", "ACCEPTED", "CUST19", "2026-07-10T15:00:00Z",
                        null, new AppointmentSegment("TM2", "VAR2", 60))));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                order("ord19", "CUST19", "2026-07-10T15:30:00Z", new BigDecimal("20.00"),
                        List.of(new Tender("t1", "CARD", money(new BigDecimal("200.00")))), null,
                        lineItem("VAR1", "Manicure", new BigDecimal("100.00")),
                        lineItem("VAR2", "Pedicure", new BigDecimal("100.00")))));
        when(square.catalogPrices(any())).thenReturn(Map.of(
                "VAR1", new BigDecimal("100.00"), "VAR2", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, CUTOFF);

        assertThat(providerMonth(agg, "TM1").firstHalf().cardTips()).isEqualByComparingTo("10.00");
        assertThat(providerMonth(agg, "TM2").firstHalf().cardTips()).isEqualByComparingTo("10.00");
    }
}
