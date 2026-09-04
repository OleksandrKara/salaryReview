package com.salonreview.repo;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SameDayRebookingSend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SameDayRebookingSendRepository#countConvertedSince}'s native query (added
 * 2026-09-04 alongside same_day_rebooking_discount's own "returned" tracking) against a real
 * Postgres — mocked-repository scheduler tests never touch the actual SQL, and this is exactly the
 * kind of native-query typo/type-mismatch class of bug that only surfaces at runtime (see
 * SeoTechnicalIssueRepositoryTest's own doc for the precedent this follows).
 */
@SpringBootTest
class SameDayRebookingSendRepositoryTest {

    @Autowired
    private SameDayRebookingSendRepository sendRepository;
    @Autowired
    private ProviderVisitRepository providerVisitRepository;
    @Autowired
    private BusinessRepository businesses;

    @Test
    void countsOnlyCustomersWithARealVisitAfterTheSendsOwnCheckoutDate() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        // Well before every row's own createdAt below (2026-06-01), not "now" — these rows are
        // seeded with a fixed past date so the visit-date-vs-checkout-date comparison inside the
        // query is exercised deterministically, independent of when this test actually runs.
        Instant since = Instant.parse("2026-01-01T00:00:00Z");

        // Converted: checkout (this send row) on 2026-06-01, a NEW visit on 2026-06-10.
        SameDayRebookingSend converted = sendRepository.save(SameDayRebookingSend.builder()
                .businessId(businessId).phoneNumber("+15550000001").squareCustomerId("conv-cust")
                .squarePaymentId("pay-conv").sendDueAt(Instant.now()).promoExpiresAt(Instant.now())
                .state(SameDayRebookingSend.STATE_SENT)
                .createdAt(Instant.parse("2026-06-01T12:00:00Z")).build());
        providerVisitRepository.save(ProviderVisit.builder().businessId(businessId).customerId("conv-cust")
                .providerRef("prov1").providerName("Lesya").serviceDate(LocalDate.of(2026, 6, 10)).build());

        // Not converted: no later visit at all.
        sendRepository.save(SameDayRebookingSend.builder()
                .businessId(businessId).phoneNumber("+15550000002").squareCustomerId("noconv-cust")
                .squarePaymentId("pay-noconv").sendDueAt(Instant.now()).promoExpiresAt(Instant.now())
                .state(SameDayRebookingSend.STATE_SENT)
                .createdAt(Instant.parse("2026-06-01T12:00:00Z")).build());

        // Not converted: a visit exists, but dated the SAME day as the checkout, not after it —
        // this is the checkout itself, not a real rebooking.
        sendRepository.save(SameDayRebookingSend.builder()
                .businessId(businessId).phoneNumber("+15550000003").squareCustomerId("sameday-cust")
                .squarePaymentId("pay-sameday").sendDueAt(Instant.now()).promoExpiresAt(Instant.now())
                .state(SameDayRebookingSend.STATE_SENT)
                .createdAt(Instant.parse("2026-06-01T12:00:00Z")).build());
        providerVisitRepository.save(ProviderVisit.builder().businessId(businessId).customerId("sameday-cust")
                .providerRef("prov1").providerName("Lesya").serviceDate(LocalDate.of(2026, 6, 1)).build());

        // Not counted at all: state isn't SENT (e.g. SKIPPED_BOOKED), even with a later visit.
        SameDayRebookingSend skipped = sendRepository.save(SameDayRebookingSend.builder()
                .businessId(businessId).phoneNumber("+15550000004").squareCustomerId("skipped-cust")
                .squarePaymentId("pay-skipped").sendDueAt(Instant.now()).promoExpiresAt(Instant.now())
                .state(SameDayRebookingSend.STATE_SKIPPED_BOOKED)
                .createdAt(Instant.parse("2026-06-01T12:00:00Z")).build());
        providerVisitRepository.save(ProviderVisit.builder().businessId(businessId).customerId("skipped-cust")
                .providerRef("prov1").providerName("Lesya").serviceDate(LocalDate.of(2026, 6, 10)).build());

        long count = sendRepository.countConvertedSince(businessId, SameDayRebookingSend.STATE_SENT, since);

        assertThat(count).isEqualTo(1);
        assertThat(converted.getId()).isNotNull();
        assertThat(skipped.getState()).isEqualTo(SameDayRebookingSend.STATE_SKIPPED_BOOKED);
    }
}
