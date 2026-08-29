package com.salonreview.square;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real production case found live 2026-08-28 (Charmaine Bruan, business 2): a $500 appointment with
 * no checkout at all — just a booking note reading {@code "Invoice: 001365 ($100) paid \nPaid $350
 * cash "}. The $100 was a real Square Invoice, paid by card five months earlier as a deposit; the
 * $350 was the actual cash collected at the visit. Before this fix, {@link CashNoteParser} read the
 * invoice number itself as the cash amount (capped down to the $500 catalog price), booking the whole
 * visit as 100% cash and leaving the $100 deposit permanently stuck in an unrelated month's unmatched
 * list, never paid to anyone.
 *
 * <p>The fix resolves the invoice reference against Square's own invoice record (never guesses from
 * another digit in the note) and credits it as CARD — independent of {@link
 * SalonConfig#restrictDiscountCoverage}, since correctly channel-splitting real revenue doesn't change
 * the provider's total commission by itself. Only a genuinely unexplained residual (here, the $50
 * neither the invoice nor the note's own cash figure accounts for) is affected by that config: absorbed
 * by default (legacy), or excluded from the commission basis when the business opted into restricting
 * discount coverage — see {@link DiscountCoverageTest} for that same policy's checkout-order sibling.
 */
class CashNoteInvoiceLinkingTest {

    private static final String CUST = "C1";
    private static final String TM = "TM1";

    private SquareClient square;
    private SalonConfigRepository salonConfigRepo;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext = mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(2L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(2L)).thenReturn(square);
        OwnerCustomerRepository ownerRepo = mock(OwnerCustomerRepository.class);
        salonConfigRepo = mock(SalonConfigRepository.class);
        aggregator = new SquareMonthAggregator(squareClientProvider, new CashNoteParser(), ownerRepo,
                currentBusinessContext, salonConfigRepo,
                mock(com.salonreview.repo.SquareBookingMirrorRepository.class), mock(com.salonreview.repo.SquareOrderMirrorRepository.class),
                mock(com.salonreview.repo.SquarePaymentMirrorRepository.class), mock(com.salonreview.config.SquareMirrorProperties.class));

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember(TM, "Anastasiia", "M.", "ACTIVE", false, null, null)));
        when(ownerRepo.findAllByBusinessId(2L)).thenReturn(List.of());
        when(square.payments(any(), any())).thenReturn(List.of());
        when(square.customerNames(any())).thenReturn(Map.of());
        when(square.completedOrders(any(), any())).thenReturn(List.of());

        var booking = new Booking("bkCharmaine", "ACCEPTED", "2026-08-03T20:30:00Z", null, null, "LOC", CUST,
                "Invoice: 001365 ($100) paid \nPaid $350 cash ", null,
                List.of(new AppointmentSegment(TM, "REGULAR", 60)));
        when(square.bookings(any(), any())).thenReturn(List.of(booking));
        when(square.catalogPrices(any())).thenReturn(Map.of("REGULAR", new BigDecimal("500.00")));
    }

    private static Invoice paidInvoice(String number, String amount) {
        Money m = new Money(new BigDecimal(amount).movePointRight(2).longValueExact(), "USD");
        return new Invoice("inv1", number, "Deposit", "PAID", "2026-03-08T00:00:00Z",
                List.of(new PaymentRequest(m)), null);
    }

    private static AttributedService line(MonthAggregation agg, String channel) {
        return agg.services().stream().filter(s -> channel.equals(s.channel())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("legacy default (restrictDiscountCoverage false): deposit invoice credited as CARD, "
            + "remainder as CASH, total commission basis still the full $500 (unaffected, only the "
            + "channel split changes)")
    void legacyDefaultCreditsInvoiceAsCardKeepsFullCommission() {
        when(square.invoicesForCustomer(CUST)).thenReturn(List.of(paidInvoice("001365", "100.00")));
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.empty());

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("30.00"));

        AttributedService cardLine = line(agg, "CARD");
        assertThat(cardLine.gross()).isEqualByComparingTo("100.00");
        assertThat(cardLine.service()).contains("Deposit invoice");

        AttributedService cashLine = line(agg, "CASH-NOTE");
        assertThat(cashLine.net()).isEqualByComparingTo("350.00"); // the real cash collected, not the invoice number
        assertThat(cashLine.gross()).isEqualByComparingTo("400.00"); // 500 - 100 card; residual $50 still absorbed
        assertThat(cashLine.discount()).isEqualByComparingTo("50.00"); // shown, but not deducted from commission

        var half = agg.providers().get(0).firstHalf();
        assertThat(half.cardRevenue().add(half.cashGross())).isEqualByComparingTo("500.00");
        assertThat(half.cashCollected()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("restrictDiscountCoverage=true: same deposit still credited as CARD, but the "
            + "unexplained $50 residual now reduces the commission basis instead of being absorbed")
    void restrictedCoverageExcludesUnexplainedResidual() {
        when(square.invoicesForCustomer(CUST)).thenReturn(List.of(paidInvoice("001365", "100.00")));
        SalonConfig cfg = SalonConfig.builder().businessId(2L).restrictDiscountCoverage(true)
                .coveredDiscountNames("deposit").build();
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.of(cfg));

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("30.00"));

        AttributedService cardLine = line(agg, "CARD");
        assertThat(cardLine.gross()).isEqualByComparingTo("100.00");

        AttributedService cashLine = line(agg, "CASH-NOTE");
        assertThat(cashLine.gross()).isEqualByComparingTo("350.00"); // 400 - the $50 residual, now excluded
        assertThat(cashLine.net()).isEqualByComparingTo("350.00");

        // $100 card + $350 cash = $450 — the $50 residual is paid to neither the provider nor booked
        // as cash; exactly the salon's own expectation for an uncovered amount.
        var half = agg.providers().get(0).firstHalf();
        assertThat(half.cardRevenue().add(half.cashGross())).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("invoice number in the note is never mistaken for the cash amount, even with no "
            + "linked invoice found at all (e.g. unpaid, or for a different customer)")
    void unresolvedInvoiceReferenceStillReadsRealCashFigure() {
        when(square.invoicesForCustomer(CUST)).thenReturn(List.of()); // nothing found for this customer
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.empty());

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("30.00"));

        assertThat(agg.services()).hasSize(1); // no separate CARD line — nothing was linked
        AttributedService cashLine = line(agg, "CASH-NOTE");
        assertThat(cashLine.net()).isEqualByComparingTo("350.00"); // still the real cash figure, not 001365
        assertThat(cashLine.gross()).isEqualByComparingTo("500.00"); // fully absorbed, same as pre-existing gap behavior
        assertThat(cashLine.discount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("an invoice reference that doesn't match any PAID invoice for this customer is ignored")
    void unpaidInvoiceIsNotCredited() {
        when(square.invoicesForCustomer(CUST)).thenReturn(List.of(
                new Invoice("inv1", "001365", "Deposit", "UNPAID", "2026-03-08T00:00:00Z",
                        List.of(new PaymentRequest(new Money(10000L, "USD"))), null)));
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.empty());

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("30.00"));

        assertThat(agg.services()).hasSize(1);
        assertThat(line(agg, "CASH-NOTE").gross()).isEqualByComparingTo("500.00");
    }

    // --- Real failures found live 2026-08-28, after the first version of this fix shipped ---

    @Test
    @DisplayName("nu13pdulf5449r: the invoice number written BEFORE the keyword ('001821 invoice sent "
            + "$100') is still found, not just the after-keyword phrasing")
    void invoiceNumberBeforeKeywordIsStillFound() {
        var booking = new Booking("nu13pdulf5449r", "ACCEPTED", "2026-08-03T20:30:00Z", null, null, "LOC", CUST,
                "001821 invoice sent $100 \nprecare aftercare sent \nPaid $450 cash ", null,
                List.of(new AppointmentSegment(TM, "REGULAR", 60)));
        when(square.bookings(any(), any())).thenReturn(List.of(booking));
        when(square.catalogPrices(any())).thenReturn(Map.of("REGULAR", new BigDecimal("600.00")));
        when(square.invoicesForCustomer(CUST)).thenReturn(List.of(paidInvoice("001821", "100.00")));
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.empty());

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("30.00"));

        assertThat(line(agg, "CARD").gross()).isEqualByComparingTo("100.00");
        AttributedService cashLine = line(agg, "CASH-NOTE");
        assertThat(cashLine.net()).isEqualByComparingTo("450.00");
        assertThat(cashLine.gross()).isEqualByComparingTo("500.00"); // 600 - 100 card
    }

    @Test
    @DisplayName("7ptd9x14kxboht/u476xpsswlwfra: a deleted/missing catalog item (Square 404s the "
            + "variation) no longer erases the linked invoice's headroom — gross falls back to cash "
            + "collected PLUS the invoice, not just the cash figure alone")
    void missingCatalogPriceStillLeavesRoomForLinkedInvoice() {
        var booking = new Booking("7ptd9x14kxboht", "ACCEPTED", "2026-08-14T20:30:00Z", null, null, "LOC", CUST,
                "invoice 001803 100$ paid\npre and after-care sent on the email 14/08\nPaid $450 cash ", null,
                List.of(new AppointmentSegment(TM, "DELETED-VARIATION", 60)));
        when(square.bookings(any(), any())).thenReturn(List.of(booking));
        when(square.catalogPrices(any())).thenReturn(Map.of()); // the variation 404s — nothing resolves
        when(square.invoicesForCustomer(CUST)).thenReturn(List.of(paidInvoice("001803", "100.00")));
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.empty());

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("30.00"));

        // Before this fix: gross fell back to just the $450 cash figure, leaving zero headroom, so
        // the correctly-found $100 invoice got capped to $0 and silently vanished.
        AttributedService cardLine = line(agg, "CARD");
        assertThat(cardLine.gross()).isEqualByComparingTo("100.00");
        AttributedService cashLine = line(agg, "CASH-NOTE");
        assertThat(cashLine.net()).isEqualByComparingTo("450.00");
        assertThat(cashLine.gross()).isEqualByComparingTo("450.00"); // (450+100) - 100 card

        var half = agg.providers().get(0).firstHalf();
        assertThat(half.cardRevenue().add(half.cashGross())).isEqualByComparingTo("550.00");
    }
}
