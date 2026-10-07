package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsultationOfferServiceTest {

    private static final Long BIZ = 2L;
    private static final Instant NOW = Instant.parse("2026-10-07T18:00:00Z");

    private ConsultationFollowUpLinks links;
    private ConsultationFollowUpRepository repo;
    private SameDayRebookingGroupMembershipRepository memberships;
    private SquareClient square;
    private ConsultationOfferService service;
    private ConsultationFollowUp row;

    @BeforeEach
    void setUp() {
        RebookingProperties props = new RebookingProperties();
        props.setPromoSecret("test-secret-test-secret-test-secret");
        links = new ConsultationFollowUpLinks(new RebookingPromoSigner(props), "https://salon");
        repo = mock(ConsultationFollowUpRepository.class);
        memberships = mock(SameDayRebookingGroupMembershipRepository.class);
        PromoConfigService promos = mock(PromoConfigService.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        when(provider.forBusiness(BIZ)).thenReturn(square);
        when(promos.get(BIZ, PromoConfigService.CONSULTATION_OFFER_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(7500, 50000L, "GROUP-75", true)));
        row = ConsultationFollowUp.builder().id(41L).businessId(BIZ).squareCustomerId("c-sent").customerName("Sarah")
                .offerState(ConsultationFollowUp.STATE_SENT).offerExpiresAt(NOW.plus(Duration.ofDays(3)))
                .consultationStartAt(NOW.minus(Duration.ofDays(300))).squareBookingId("bk").build();
        when(repo.findByIdAndBusinessId(41L, BIZ)).thenReturn(Optional.of(row));
        service = new ConsultationOfferService(links, repo, promos, memberships, provider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token() {
        return links.offerBookUrl(41L).replaceAll(".*offer=", "");
    }

    @Test
    void aLivePersonalLinkShowsTheDiscount() {
        ConsultationOfferService.Offer offer = service.check(BIZ, token());
        assertThat(offer.valid()).isTrue();
        assertThat(offer.discountCents()).isEqualTo(7500);
        assertThat(offer.minSpendCents()).isEqualTo(50000);
        assertThat(offer.firstName()).isEqualTo("Sarah");
    }

    @Test
    void aTamperedExpiredOrUnsentLinkShowsNothing() {
        assertThat(service.check(BIZ, "41.forged").valid()).isFalse();
        assertThat(service.check(BIZ, "42." + token().split("\\.")[1]).valid()).isFalse();
        assertThat(service.check(BIZ, null).valid()).isFalse();
        row.setOfferExpiresAt(NOW.minusSeconds(1));
        assertThat(service.check(BIZ, token()).valid()).isFalse();
        row.setOfferExpiresAt(NOW.plus(Duration.ofDays(1)));
        row.setOfferState(ConsultationFollowUp.STATE_SEND_FAILED);
        assertThat(service.check(BIZ, token()).valid()).isFalse();
    }

    @Test
    void aBookingOnANewProfileGetsTheDiscountUntilTwoDaysAfterTheVisit() {
        Instant visit = NOW.plus(Duration.ofDays(20));
        when(memberships.findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(BIZ, "c-new", "GROUP-75"))
                .thenReturn(Optional.empty());

        assertThat(service.claim(BIZ, token(), "c-new", visit)).isTrue();

        verify(square).addCustomerToGroup("c-new", "GROUP-75");
        ArgumentCaptor<SameDayRebookingGroupMembership> saved = ArgumentCaptor.forClass(SameDayRebookingGroupMembership.class);
        verify(memberships).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(visit.plus(Duration.ofDays(2)));
    }

    @Test
    void aBookingOnTheSameProfileKeepsTheExistingMembershipLongEnough() {
        Instant visit = NOW.plus(Duration.ofDays(200));
        SameDayRebookingGroupMembership m = SameDayRebookingGroupMembership.builder().businessId(BIZ).squareCustomerId("c-sent")
                .groupId("GROUP-75").expiresAt(NOW.plus(Duration.ofDays(120))).build();
        when(memberships.findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(BIZ, "c-sent", "GROUP-75"))
                .thenReturn(Optional.of(m));

        assertThat(service.claim(BIZ, token(), "c-sent", visit)).isTrue();

        verify(square, never()).addCustomerToGroup(anyString(), anyString());
        assertThat(m.getExpiresAt()).isEqualTo(visit.plus(Duration.ofDays(2)));
    }

    @Test
    void anInvalidLinkClaimsNothing() {
        assertThat(service.claim(BIZ, "41.forged", "c-new", NOW.plus(Duration.ofDays(5)))).isFalse();
        verifyNoInteractions(square);
        verify(memberships, never()).save(any());
    }
}
