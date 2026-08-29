package com.salonreview.square;

import com.salonreview.domain.PrepaidPackage;
import com.salonreview.domain.PrepaidRedemption;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PrepaidPackageRepository;
import com.salonreview.repo.PrepaidRedemptionRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.PrepaidService.Candidate;
import com.salonreview.square.SquareClient.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Prepaid candidates span ALL providers the prepaid customer visited (not just one), across months —
 * the prepaid package is no longer tied to a single provider.
 */
class PrepaidServiceTest {

    private static Booking booking(String id, String start, String teamMemberId, String variationId) {
        return new Booking(id, "ACCEPTED", start, null, null, "LOC", "C1", null, null,
                List.of(new AppointmentSegment(teamMemberId, variationId, 60)));
    }

    @Test
    @DisplayName("Candidates include every provider the customer saw, across months")
    void candidatesSpanProvidersAndMonths() {
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);

        PrepaidService svc = new PrepaidService(squareClientProvider, providers, directory, salonConfig,
                currentBusinessContext, packages, redemptions, mock(SettlementPreviewService.class));

        PrepaidPackage pkg = PrepaidPackage.builder().id(1L).businessId(1L).customerId("C1").customerName("Alina")
                .paidDate(LocalDate.of(2026, 3, 1)).amount(new BigDecimal("300")).totalServices(3).build();
        when(packages.findByIdAndBusinessId(1L, 1L)).thenReturn(Optional.of(pkg));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(sc));

        when(square.locationTimeZone()).thenReturn("UTC");
        // Two visits, two providers, two months — neither paid through the till.
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bkApr", "2026-04-15T15:00:00Z", "TM1", "VAR1"),
                booking("bkMay", "2026-05-10T15:00:00Z", "TM2", "VAR2")));
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember("TM1", "Alice", "A", "ACTIVE", false, null, null),
                new TeamMember("TM2", "Bob", "B", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00"), "VAR2", new BigDecimal("80.00")));
        when(square.catalogNames(any())).thenReturn(Map.of("VAR1", "Mani", "VAR2", "Pedi"));
        when(redemptions.existsBySquareBookingIdAndServiceVariationId(any(), any())).thenReturn(false);

        List<Candidate> candidates = svc.candidates(1L);

        assertThat(candidates).hasSize(2);
        Candidate apr = candidates.stream().filter(c -> c.date().equals("2026-04-15")).findFirst().orElseThrow();
        Candidate may = candidates.stream().filter(c -> c.date().equals("2026-05-10")).findFirst().orElseThrow();
        assertThat(apr.providerName()).isEqualTo("Alice A");
        assertThat(apr.teamMemberId()).isEqualTo("TM1");
        assertThat(may.providerName()).isEqualTo("Bob B"); // the May visit with a DIFFERENT provider now shows
        assertThat(may.teamMemberId()).isEqualTo("TM2");
    }

    @Test
    @DisplayName("Invoice lookup maps the total (sum of payment requests) and the created date")
    void invoiceLookupMapsTotalAndDate() {
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(any())).thenReturn(square);
        PrepaidService svc = new PrepaidService(squareClientProvider, mock(ProviderRepository.class), mock(ProviderDirectory.class),
                mock(SalonConfigRepository.class), mock(com.salonreview.config.CurrentBusinessContext.class),
                mock(PrepaidPackageRepository.class), mock(PrepaidRedemptionRepository.class), mock(SettlementPreviewService.class));

        Invoice paid = new Invoice("inv1", "000089", "Prepaid", "PAID", "2026-05-29T10:00:00Z",
                List.of(new PaymentRequest(new Money(2500L, "USD")), new PaymentRequest(new Money(1500L, "USD"))), null);
        Invoice unpaid = new Invoice("inv2", "000090", "Pending", "UNPAID", "2026-05-30T10:00:00Z",
                List.of(new PaymentRequest(new Money(9900L, "USD"))), null);
        when(square.invoicesForCustomer("C1")).thenReturn(List.of(paid, unpaid));

        List<PrepaidService.InvoiceMatch> out = svc.invoices("C1");

        assertThat(out).hasSize(1);                                     // UNPAID is filtered out
        assertThat(out.get(0).number()).isEqualTo("000089");
        assertThat(out.get(0).date()).isEqualTo("2026-05-29");          // created_at date only
        assertThat(out.get(0).amount()).isEqualByComparingTo("40.00");  // 25.00 + 15.00
    }

    private static PrepaidService serviceWithBusiness(PrepaidPackageRepository packages,
                                                       PrepaidRedemptionRepository redemptions, Long businessId) {
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(businessId);
        return new PrepaidService(mock(SquareClientProvider.class), mock(ProviderRepository.class),
                mock(ProviderDirectory.class), mock(SalonConfigRepository.class), currentBusinessContext,
                packages, redemptions, mock(SettlementPreviewService.class));
    }

    // Package 5 genuinely EXISTS — just owned by business 2, not the caller's business 1. Stubbing
    // the OLD unscoped lookups (existsById/findById) to also find it is what makes these tests a
    // real proof: the pre-fix code path succeeds in finding the row via those calls and only the
    // NEW business-scoped lookup correctly misses it — a test that only stubs the new method would
    // pass even against the old code, since Mockito's unstubbed default (false / Optional.empty())
    // happens to look identical to "properly rejected" (confirmed by revert-testing before writing
    // this comment).
    private static PrepaidPackage anotherBusinessesPackage() {
        return PrepaidPackage.builder().id(5L).businessId(2L).customerName("Someone Else")
                .paidDate(LocalDate.of(2026, 3, 1)).amount(new BigDecimal("300")).totalServices(3).build();
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: delete() 404s for a package belonging to another "
            + "business, instead of deleting it by bare id")
    void deleteRejectsAnotherBusinessesPackage() {
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidService svc = serviceWithBusiness(packages, redemptions, 1L);
        when(packages.existsById(5L)).thenReturn(true); // old code's lookup — package DOES exist
        when(packages.findByIdAndBusinessId(5L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.delete(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such package");

        verify(packages, never()).delete(any());
        verify(packages, never()).deleteById(any());
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: redeem() 404s against another business's package")
    void redeemRejectsAnotherBusinessesPackage() {
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(sc));
        ProviderDirectory directory = mock(ProviderDirectory.class);
        when(directory.resolveOrCreate(any(), any()))
                .thenReturn(com.salonreview.domain.Provider.builder().id(1L).build());
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        PrepaidService svc = new PrepaidService(mock(SquareClientProvider.class), mock(ProviderRepository.class),
                directory, salonConfig, currentBusinessContext, packages, redemptions, mock(SettlementPreviewService.class));
        when(packages.findById(5L)).thenReturn(Optional.of(anotherBusinessesPackage())); // old lookup
        when(packages.findByIdAndBusinessId(5L, 1L)).thenReturn(Optional.empty());
        var req = new PrepaidService.RedeemRequest("bk1", "var1", "Mani",
                LocalDate.of(2026, 5, 1), new BigDecimal("80.00"), "TM1", "Alice");

        assertThatThrownBy(() -> svc.redeem(5L, req, "manager"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such package");

        verify(redemptions, never()).save(any());
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: candidates() 404s against another business's package")
    void candidatesRejectsAnotherBusinessesPackage() {
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidService svc = serviceWithBusiness(packages, redemptions, 1L);
        when(packages.findById(5L)).thenReturn(Optional.of(anotherBusinessesPackage())); // old lookup
        when(packages.findByIdAndBusinessId(5L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.candidates(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such package");
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: undoRedemption() 404s for a redemption belonging to "
            + "another business's package, instead of deleting it by bare id")
    void undoRedemptionRejectsAnotherBusinessesRedemption() {
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidService svc = serviceWithBusiness(packages, redemptions, 1L);
        when(redemptions.existsById(9L)).thenReturn(true); // old code's lookup — redemption DOES exist
        when(redemptions.findByIdAndBusinessId(9L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.undoRedemption(9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such redemption");

        verify(redemptions, never()).delete(any());
        verify(redemptions, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete()/undoRedemption() succeed for the caller's own business")
    void deleteAndUndoRedemptionSucceedForOwnBusiness() {
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        PrepaidService svc = serviceWithBusiness(packages, redemptions, 1L);
        PrepaidPackage pkg = PrepaidPackage.builder().id(5L).businessId(1L).customerName("Alina")
                .paidDate(LocalDate.of(2026, 3, 1)).amount(new BigDecimal("300")).totalServices(3).build();
        when(packages.findByIdAndBusinessId(5L, 1L)).thenReturn(Optional.of(pkg));
        PrepaidRedemption redemption = PrepaidRedemption.builder().id(9L).packageId(5L).providerId(1L)
                .squareBookingId("bk1").serviceVariationId("var1").serviceDate(LocalDate.of(2026, 5, 1))
                .menuPrice(new BigDecimal("80.00")).counts(true).build();
        when(redemptions.findByIdAndBusinessId(9L, 1L)).thenReturn(Optional.of(redemption));

        svc.delete(5L);
        svc.undoRedemption(9L);

        verify(packages).delete(pkg);
        verify(redemptions).delete(redemption);
    }

    @Test
    @DisplayName("unattributed(): returns PAID invoices with no matching package, resolves customer "
            + "names, and excludes non-PAID invoices")
    void unattributedListsUnmatchedPaidInvoices() {
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        PrepaidService svc = new PrepaidService(squareClientProvider, mock(ProviderRepository.class),
                mock(ProviderDirectory.class), mock(SalonConfigRepository.class), currentBusinessContext,
                packages, mock(PrepaidRedemptionRepository.class), mock(SettlementPreviewService.class));

        // One package already exists, referencing invoice "000089" — that invoice must be excluded.
        when(packages.findAllByBusinessIdOrderByPaidDateDesc(1L)).thenReturn(List.of(
                PrepaidPackage.builder().id(1L).businessId(1L).customerName("Alina")
                        .paidDate(LocalDate.of(2026, 5, 1)).amount(new BigDecimal("100"))
                        .totalServices(1).invoiceRef("000089").build()));

        Invoice alreadyAttributed = new Invoice("inv1", "000089", "Prepaid", "PAID", "2026-05-01T10:00:00Z",
                List.of(new PaymentRequest(new Money(10000L, "USD"))), new PrimaryRecipient("C1"));
        Invoice newDeposit = new Invoice("inv2", "000091", "Deposit", "PAID", "2026-05-10T10:00:00Z",
                List.of(new PaymentRequest(new Money(20000L, "USD"))), new PrimaryRecipient("C2"));
        Invoice unpaid = new Invoice("inv3", "000092", "Pending", "UNPAID", "2026-05-11T10:00:00Z",
                List.of(new PaymentRequest(new Money(30000L, "USD"))), new PrimaryRecipient("C3"));
        when(square.recentInvoices()).thenReturn(List.of(alreadyAttributed, newDeposit, unpaid));
        when(square.customerNames(List.of("C2"))).thenReturn(Map.of("C2", "Jane Doe"));

        List<PrepaidService.UnattributedInvoice> out = svc.unattributed();

        assertThat(out).hasSize(1);
        PrepaidService.UnattributedInvoice u = out.get(0);
        assertThat(u.id()).isEqualTo("inv2");
        assertThat(u.customerId()).isEqualTo("C2");
        assertThat(u.customerName()).isEqualTo("Jane Doe");
        assertThat(u.number()).isEqualTo("000091");
        assertThat(u.amount()).isEqualByComparingTo("200.00");
    }

    /** Real production case found 2026-08-19 (Hala Wrda, invoice 001688): a $100 deposit invoice
     * paid ahead of time, then applied as a checkout discount on the real $600 appointment (also
     * discounted 10% by an unrelated promo), leaving $440 + $88 tip collected via card that day. A
     * short-lived intermediate version of this method surfaced such visits as a candidate priced at
     * the $100 deposit credit — but that double-pays the provider: {@code SquareMonthAggregator}
     * already attributes this same order's line item on its full, pre-discount {@code
     * grossSalesMoney} ($600), regardless of any discount applied to it. Confirmed live: Anastasiia's
     * July 2026 trace showed BOTH a $600-gross CARD line and a $100-gross PREPAID line for this one
     * booking. Reverted to excluding any visit with a matching order, deposit-discounted or not.
     */
    @Test
    @DisplayName("A visit already checked out through the till — even at a reduced price via the "
            + "salon's own Deposit discount — is excluded (the order's own gross already covers it)")
    void depositDiscountedVisitStillExcluded() {
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(2L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(2L)).thenReturn(square);

        PrepaidService svc = new PrepaidService(squareClientProvider, providers, directory, salonConfig,
                currentBusinessContext, packages, redemptions, mock(SettlementPreviewService.class));

        PrepaidPackage pkg = PrepaidPackage.builder().id(9L).businessId(2L).customerId("C1").customerName("Hala Wrda")
                .paidDate(LocalDate.of(2026, 7, 2)).amount(new BigDecimal("100.00")).totalServices(1).build();
        when(packages.findByIdAndBusinessId(9L, 2L)).thenReturn(Optional.of(pkg));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.of(sc));

        when(square.locationTimeZone()).thenReturn("America/Los_Angeles");
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bkHala", "2026-07-10T22:13:18Z", "TM-ANASTASIIA", "POWDER-OMBRE")));
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember("TM-ANASTASIIA", "Anastasiia", "M.", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of("POWDER-OMBRE", new BigDecimal("600.00")));
        when(square.catalogNames(any())).thenReturn(Map.of("POWDER-OMBRE", "Eyebrows Powder&Ombre Technique"));
        when(redemptions.existsBySquareBookingIdAndServiceVariationId(any(), any())).thenReturn(false);

        OrderDiscount promo = new OrderDiscount("promo-uid", "Discount July 4th", new Money(6000L, "USD"));
        OrderDiscount deposit = new OrderDiscount("deposit-uid", "Deposit ", new Money(10000L, "USD"));
        AppliedDiscount appliedPromo = new AppliedDiscount("ap1", "promo-uid", new Money(6000L, "USD"));
        AppliedDiscount appliedDeposit = new AppliedDiscount("ap2", "deposit-uid", new Money(10000L, "USD"));
        OrderLineItem lineItem = new OrderLineItem("li1", "Eyebrows Powder&Ombre Technique by Anastasiia", "1",
                "POWDER-OMBRE", new Money(60000L, "USD"), new Money(60000L, "USD"), new Money(44000L, "USD"),
                new Money(16000L, "USD"), List.of(appliedPromo, appliedDeposit));
        Order checkoutOrder = new Order("orderHala", "LOC", "C1", "COMPLETED", "2026-07-10T22:13:26Z",
                "2026-07-10T22:13:18Z", List.of(lineItem), new Money(8800L, "USD"), new Money(16000L, "USD"),
                List.of(), List.of(new Fulfillment("BOOKING", "COMPLETED")), List.of(promo, deposit));
        when(square.completedOrders(any(), any())).thenReturn(List.of(checkoutOrder));

        List<Candidate> candidates = svc.candidates(9L);

        assertThat(candidates).isEmpty();
    }

    /** Real production case found 2026-08-19, discovered while manually verifying the deposit-discount
     * behavior against Hala Wrda's actual live data: the package's own stored customerId and her
     * booking both carried one Square customer id, but the real checkout order carried a DIFFERENT,
     * never-equal id (Square silently merges duplicate customer profiles — same issue
     * CustomerMergeAttributionTest documents for SquareMonthAggregator's own order-to-booking
     * matching). Before resolving canonical ids first, matchOrder never found the real order at all,
     * so the visit incorrectly surfaced as a candidate at the full $600 catalog price — money the
     * provider is already paid via the order's own gross. Canonical-id resolution (still in place;
     * only the deposit-credit part of the original fix was reverted) fixes the match itself, so the
     * visit is correctly excluded instead.
     */
    @Test
    @DisplayName("A visit is correctly excluded even when the booking and its checkout order carry "
            + "two different (Square-merged) customer ids")
    void visitExcludedAcrossMergedCustomerIds() {
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(2L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(2L)).thenReturn(square);

        PrepaidService svc = new PrepaidService(squareClientProvider, providers, directory, salonConfig,
                currentBusinessContext, packages, redemptions, mock(SettlementPreviewService.class));

        // The package (and her booking) carry the pre-merge id; the checkout order carries the
        // canonical post-merge id — exactly the real split found live.
        PrepaidPackage pkg = PrepaidPackage.builder().id(9L).businessId(2L).customerId("PRE-MERGE-ID").customerName("Hala Wrda")
                .paidDate(LocalDate.of(2026, 7, 2)).amount(new BigDecimal("100.00")).totalServices(1).build();
        when(packages.findByIdAndBusinessId(9L, 2L)).thenReturn(Optional.of(pkg));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findByBusinessId(2L)).thenReturn(Optional.of(sc));

        when(square.locationTimeZone()).thenReturn("America/Los_Angeles");
        when(square.bookings(any(), any())).thenReturn(List.of(
                new Booking("bkHala", "ACCEPTED", "2026-07-10T22:13:18Z", null, null, "LOC", "PRE-MERGE-ID", null, null,
                        List.of(new AppointmentSegment("TM-ANASTASIIA", "POWDER-OMBRE", 60)))));
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember("TM-ANASTASIIA", "Anastasiia", "M.", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of("POWDER-OMBRE", new BigDecimal("600.00")));
        when(square.catalogNames(any())).thenReturn(Map.of("POWDER-OMBRE", "Eyebrows Powder&Ombre Technique"));
        when(redemptions.existsBySquareBookingIdAndServiceVariationId(any(), any())).thenReturn(false);
        // Both the pre-merge id (package/booking) and the canonical id (order) resolve to the same
        // canonical customer — the exact resolution PrepaidService now performs up front.
        when(square.canonicalCustomerIds(any())).thenReturn(Map.of(
                "PRE-MERGE-ID", "CANONICAL-ID", "CANONICAL-ID", "CANONICAL-ID"));

        OrderDiscount deposit = new OrderDiscount("deposit-uid", "Deposit ", new Money(10000L, "USD"));
        AppliedDiscount appliedDeposit = new AppliedDiscount("ap2", "deposit-uid", new Money(10000L, "USD"));
        OrderLineItem lineItem = new OrderLineItem("li1", "Eyebrows Powder&Ombre Technique by Anastasiia", "1",
                "POWDER-OMBRE", new Money(60000L, "USD"), new Money(60000L, "USD"), new Money(50000L, "USD"),
                new Money(10000L, "USD"), List.of(appliedDeposit));
        Order checkoutOrder = new Order("orderHala", "LOC", "CANONICAL-ID", "COMPLETED", "2026-07-10T22:13:26Z",
                "2026-07-10T22:13:18Z", List.of(lineItem), new Money(8800L, "USD"), new Money(10000L, "USD"),
                List.of(), List.of(new Fulfillment("BOOKING", "COMPLETED")), List.of(deposit));
        when(square.completedOrders(any(), any())).thenReturn(List.of(checkoutOrder));

        List<Candidate> candidates = svc.candidates(9L);

        assertThat(candidates).isEmpty();
    }

    /** Sibling case to the deposit test above: a visit checked out at FULL price (no Deposit
     * discount at all) must still be excluded — the original anti-double-count protection, so a
     * visit genuinely unrelated to the prepaid package never gets drawn down against it.
     */
    @Test
    @DisplayName("A visit checked out at full price with no Deposit discount is still excluded (no double counting)")
    void fullyPaidVisitWithNoDepositDiscountStaysExcluded() {
        SquareClient square = mock(SquareClient.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        ProviderDirectory directory = mock(ProviderDirectory.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        PrepaidPackageRepository packages = mock(PrepaidPackageRepository.class);
        PrepaidRedemptionRepository redemptions = mock(PrepaidRedemptionRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);

        PrepaidService svc = new PrepaidService(squareClientProvider, providers, directory, salonConfig,
                currentBusinessContext, packages, redemptions, mock(SettlementPreviewService.class));

        PrepaidPackage pkg = PrepaidPackage.builder().id(1L).businessId(1L).customerId("C1").customerName("Alina")
                .paidDate(LocalDate.of(2026, 3, 1)).amount(new BigDecimal("300")).totalServices(3).build();
        when(packages.findByIdAndBusinessId(1L, 1L)).thenReturn(Optional.of(pkg));

        SalonConfig sc = mock(SalonConfig.class);
        when(sc.getServicePriceCutoff()).thenReturn(new BigDecimal("50.00"));
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(sc));

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any())).thenReturn(List.of(
                booking("bkApr", "2026-04-15T15:00:00Z", "TM1", "VAR1")));
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember("TM1", "Alice", "A", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));
        when(square.catalogNames(any())).thenReturn(Map.of("VAR1", "Mani"));
        when(redemptions.existsBySquareBookingIdAndServiceVariationId(any(), any())).thenReturn(false);

        OrderLineItem lineItem = new OrderLineItem("li1", "Mani", "1", "VAR1",
                new Money(10000L, "USD"), new Money(10000L, "USD"), new Money(10000L, "USD"), null, null);
        Order fullyPaidOrder = new Order("orderFull", "LOC", "C1", "COMPLETED", "2026-04-15T15:30:00Z",
                "2026-04-15T15:00:00Z", List.of(lineItem), new Money(0L, "USD"), null,
                List.of(), List.of(new Fulfillment("BOOKING", "COMPLETED")), null);
        when(square.completedOrders(any(), any())).thenReturn(List.of(fullyPaidOrder));

        List<Candidate> candidates = svc.candidates(1L);

        assertThat(candidates).isEmpty();
    }
}
