package com.salonreview.square;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient.*;
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
 * Real production case found 2026-08-19/20 (Hala Warda, AK PMU business): a $600 appointment
 * carried two Square discounts — a genuine 10% "Discount July 4th" promo ($60) and a "Deposit "
 * discount ($100, applied at checkout to show the client their prior deposit payment on the
 * receipt). Historically the salon absorbed every discount into the provider's commission basis
 * (paid on the full $600). The owner then asked for the opposite for this business: ordinary promo
 * discounts should come out of the salon's own margin, not the provider's pay, while a deposit
 * discount specifically should still be paid in full (it's real money the client already paid, just
 * shown as a discount line for receipt clarity, not a real price cut). {@link SalonConfig
 * #restrictDiscountCoverage} plus {@link SalonConfig#coveredDiscountNames} makes this configurable
 * per business rather than hardcoding either policy — the default (false / not configured) keeps
 * every other business's existing "absorb everything" behavior byte-for-byte.
 */
class DiscountCoverageTest {

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

        var booking = new Booking("bkHala", "ACCEPTED", "2026-07-10T20:30:00Z", null, null, "LOC", CUST, null, null,
                List.of(new AppointmentSegment(TM, "POWDER-OMBRE", 60)));
        when(square.bookings(any(), any())).thenReturn(List.of(booking));
        when(square.catalogPrices(any())).thenReturn(Map.of("POWDER-OMBRE", new BigDecimal("600.00")));

        OrderDiscount promo = new OrderDiscount("promo-uid", "Discount July 4th", new Money(6000L, "USD"));
        OrderDiscount deposit = new OrderDiscount("deposit-uid", "Deposit ", new Money(10000L, "USD"));
        AppliedDiscount appliedPromo = new AppliedDiscount("ap1", "promo-uid", new Money(6000L, "USD"));
        AppliedDiscount appliedDeposit = new AppliedDiscount("ap2", "deposit-uid", new Money(10000L, "USD"));
        OrderLineItem lineItem = new OrderLineItem("li1", "Eyebrows Powder&Ombre Technique by Anastasiia", "1",
                "POWDER-OMBRE", new Money(60000L, "USD"), new Money(60000L, "USD"), new Money(44000L, "USD"),
                new Money(16000L, "USD"), List.of(appliedPromo, appliedDeposit));
        Order checkoutOrder = new Order("orderHala", "LOC", CUST, "COMPLETED", "2026-07-10T22:13:26Z",
                "2026-07-10T22:13:18Z", List.of(lineItem), new Money(8800L, "USD"), new Money(16000L, "USD"),
                List.of(new Tender("t1", "CARD", new Money(52800L, "USD"))),
                List.of(new Fulfillment("BOOKING", "COMPLETED")), List.of(promo, deposit));
        when(square.completedOrders(any(), any())).thenReturn(List.of(checkoutOrder));
    }

    @Test
    @DisplayName("default (restrictDiscountCoverage unset/false): every discount absorbed, gross stays the full $600 — legacy behavior, unaffected")
    void defaultCoversEveryDiscount() {
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.empty());

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("50.00"));

        assertThat(agg.services()).hasSize(1);
        var line = agg.services().get(0);
        assertThat(line.gross()).isEqualByComparingTo("600.00");
        assertThat(line.discount()).isEqualByComparingTo("160.00");
        assertThat(line.net()).isEqualByComparingTo("440.00");
    }

    @Test
    @DisplayName("restrictDiscountCoverage=true with coveredDiscountNames=\"deposit\": only the deposit discount is absorbed, the promo reduces the commission basis")
    void restrictedCoverageOnlyAbsorbsConfiguredNames() {
        SalonConfig cfg = SalonConfig.builder().businessId(2L).restrictDiscountCoverage(true)
                .coveredDiscountNames("deposit").build();
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.of(cfg));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("50.00"));

        assertThat(agg.services()).hasSize(1);
        var line = agg.services().get(0);
        // net ($440) + deposit discount ($100) = $540 — the $60 promo is NOT added back.
        assertThat(line.gross()).isEqualByComparingTo("540.00");
        assertThat(line.discount()).isEqualByComparingTo("100.00");
        assertThat(line.net()).isEqualByComparingTo("440.00");
    }

    @Test
    @DisplayName("restrictDiscountCoverage=true with no coveredDiscountNames configured: no discount is absorbed at all, commission basis is just net")
    void restrictedCoverageWithNoNamesConfiguredCoversNothing() {
        SalonConfig cfg = SalonConfig.builder().businessId(2L).restrictDiscountCoverage(true).build();
        when(salonConfigRepo.findByBusinessId(2L)).thenReturn(Optional.of(cfg));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("50.00"));

        var line = agg.services().get(0);
        assertThat(line.gross()).isEqualByComparingTo("440.00");
        assertThat(line.discount()).isEqualByComparingTo("0.00");
    }
}
