package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SquarePaymentMirror;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** VIP rebooking perk eligibility (akluxnails.com/vip, 2026-10-01). Salon time throughout. */
class VipRebookEligibilityServiceTest {
    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    /** "Now": Sept 30, 2026, 19:50 salon time. */
    private static final Instant NOW = ZonedDateTime.of(2026, 9, 30, 19, 50, 0, 0, PT).toInstant();
    private static final Long BIZ = 1L;
    private static final String PHONE = "+17186834853";

    private SquareClient square;
    private SquarePaymentMirrorRepository payments;
    private ProviderVisitRepository visits;
    private ProviderRepository providers;
    private RebookingPromoSigner signer;
    private PromoConfigService promo;
    private VipRebookEligibilityService service;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(BIZ)).thenReturn(square);
        payments = mock(SquarePaymentMirrorRepository.class);
        visits = mock(ProviderVisitRepository.class);
        providers = mock(ProviderRepository.class);
        signer = mock(RebookingPromoSigner.class);
        when(signer.sign(eq("VIP10"), anyLong())).thenReturn("sigVIP");
        promo = mock(PromoConfigService.class);
        when(promo.get(BIZ, "VIP10")).thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, 9900L, "grp", true)));
        service = new VipRebookEligibilityService(provider, payments, visits, providers, signer, promo, Clock.fixed(NOW, PT));
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of("cust1"));
        when(square.customerGivenNames(any())).thenReturn(Map.of("cust1", "ina"));
        when(providers.findBySquareTeamMemberIdAndBusinessId("TM_LESYA", BIZ))
                .thenReturn(Optional.of(Provider.builder().displayName("Lesya Petrova").build()));
    }

    private static SquareClient.Booking booking(String status, ZonedDateTime start) {
        return new SquareClient.Booking("b1", status, start.toInstant().toString(), null, null, null, "cust1", null, null,
                List.of(new SquareClient.AppointmentSegment("TM_LESYA", "var1", 120)));
    }

    private void priorVisits(LocalDate... dates) {
        List<ProviderVisit> rows = java.util.Arrays.stream(dates)
                .map(d -> ProviderVisit.builder().businessId(BIZ).customerId("cust1").providerRef("p").serviceDate(d).build())
                .toList();
        when(visits.findByBusinessIdAndCustomerIdOrderByServiceDateDesc(eq(BIZ), eq("cust1"), any())).thenReturn(rows);
    }

    @Test
    @DisplayName("booking today with Lesya, returning client → eligible, signed VIP10 to midnight, 4-week latest start, artist offered again")
    void eligibleReturningClient() {
        when(square.bookingsForCustomer(eq("cust1"), any()))
                .thenReturn(List.of(booking("ACCEPTED", ZonedDateTime.of(2026, 9, 30, 17, 15, 0, 0, PT))));
        priorVisits(LocalDate.of(2026, 2, 10));

        VipRebookEligibilityService.Result r = service.check(BIZ, "(718) 683-4853");

        assertThat(r.eligible()).isTrue();
        assertThat(r.givenName()).isEqualTo("Ina");
        assertThat(r.technicianName()).isEqualTo("Lesya");
        assertThat(r.teamMemberId()).isEqualTo("TM_LESYA");
        assertThat(r.newClient()).isFalse();
        assertThat(r.signature()).isEqualTo("sigVIP");
        assertThat(Instant.ofEpochSecond(r.expEpochSeconds())).isEqualTo(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, PT).toInstant());
        assertThat(Instant.ofEpochSecond(r.latestStartEpochSeconds())).isEqualTo(ZonedDateTime.of(2026, 10, 29, 0, 0, 0, 0, PT).toInstant());
    }

    @Test
    @DisplayName("first visit (no earlier visits) → newClient")
    void newClient() {
        when(square.bookingsForCustomer(eq("cust1"), any()))
                .thenReturn(List.of(booking("ACCEPTED", ZonedDateTime.of(2026, 9, 30, 21, 0, 0, 0, PT))));
        priorVisits();

        assertThat(service.check(BIZ, PHONE).newClient()).isTrue();
    }

    @Test
    @DisplayName("today's booking cancelled and no payment today → not eligible")
    void cancelledToday() {
        when(square.bookingsForCustomer(eq("cust1"), any()))
                .thenReturn(List.of(booking("CANCELLED_BY_CUSTOMER", ZonedDateTime.of(2026, 9, 30, 17, 15, 0, 0, PT))));
        when(payments.findByBusinessIdAndCreatedAtBetween(eq(BIZ), any(), any())).thenReturn(List.of());

        VipRebookEligibilityService.Result r = service.check(BIZ, PHONE);
        assertThat(r.eligible()).isFalse();
        assertThat(r.reason()).isEqualTo("no_visit_today");
        assertThat(r.signature()).isNull();
    }

    @Test
    @DisplayName("only a booking tomorrow → not eligible (the perk is for today's visit)")
    void bookingTomorrowOnly() {
        when(square.bookingsForCustomer(eq("cust1"), any()))
                .thenReturn(List.of(booking("ACCEPTED", ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, PT))));
        when(payments.findByBusinessIdAndCreatedAtBetween(eq(BIZ), any(), any())).thenReturn(List.of());

        assertThat(service.check(BIZ, PHONE).eligible()).isFalse();
    }

    @Test
    @DisplayName("walk-in: no booking, but a completed payment today → eligible")
    void walkInPayment() {
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of());
        SquarePaymentMirror p = new SquarePaymentMirror();
        p.setSquareCustomerId("cust1");
        p.setStatus("COMPLETED");
        when(payments.findByBusinessIdAndCreatedAtBetween(eq(BIZ), any(), any())).thenReturn(List.of(p));
        priorVisits(LocalDate.of(2026, 8, 1));

        VipRebookEligibilityService.Result r = service.check(BIZ, PHONE);
        assertThat(r.eligible()).isTrue();
        assertThat(r.technicianName()).isNull();
    }

    @Test
    @DisplayName("unknown number → same 'no_visit_today' as a known number with no visit (reveals nothing)")
    void unknownNumber() {
        when(square.customerIdsForPhone(any())).thenReturn(List.of());

        assertThat(service.check(BIZ, "6195550100").reason()).isEqualTo("no_visit_today");
    }

    @Test
    @DisplayName("promo not configured for the business → not eligible")
    void notConfigured() {
        when(promo.get(BIZ, "VIP10")).thenReturn(Optional.empty());

        assertThat(service.check(BIZ, PHONE).reason()).isEqualTo("not_configured");
    }
}
