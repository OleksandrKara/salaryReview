package com.salonreview.sms;

import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.domain.SquarePaymentMirror;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsultationReengageOneOffServiceTest {

    private static final Long BIZ = 2L;
    private static final Instant NOW = Instant.parse("2026-10-07T18:00:00Z");
    private static final String CONSULT = "VAR-CONSULT";
    private static final String PROC = "VAR-PROC";
    private static final String TM_ANASTASIIA = "TM-A";
    private static final String TM_FORMER = "TM-GONE";

    private SquareBookingMirrorRepository bookings;
    private SquarePaymentMirrorRepository payments;
    private SquareCustomerMirrorRepository customers;
    private ConsultationFollowUpRepository followUps;
    private SquareClient square;
    private MailchimpClient mailchimpClient;
    private MailchimpEmailService mailchimp;
    private PromoConfigService promos;
    private SameDayRebookingGroupMembershipRepository memberships;
    private ConsultationFollowUpLinks links;
    private ConsultationReengageOneOffService service;
    private final List<SquareBookingMirror> all = new ArrayList<>();
    private final Map<String, SquareCustomerMirror> customerRows = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        bookings = mock(SquareBookingMirrorRepository.class);
        payments = mock(SquarePaymentMirrorRepository.class);
        customers = mock(SquareCustomerMirrorRepository.class);
        followUps = mock(ConsultationFollowUpRepository.class);
        ProviderRepository providers = mock(ProviderRepository.class);
        SquareClientProvider squareProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        MailchimpConfigRepository mailchimpConfigs = mock(MailchimpConfigRepository.class);
        mailchimpClient = mock(MailchimpClient.class);
        mailchimp = mock(MailchimpEmailService.class);
        promos = mock(PromoConfigService.class);
        memberships = mock(SameDayRebookingGroupMembershipRepository.class);
        links = mock(ConsultationFollowUpLinks.class);
        when(links.offerBookUrl(anyLong())).thenAnswer(inv -> "https://pmu-annakara.com/?book=procedure&offer=" + inv.getArgument(0) + ".sig");

        when(squareProvider.forBusiness(BIZ)).thenReturn(square);
        when(bookings.findByBusinessIdAndStartAtBetween(eq(BIZ), any(), any())).thenReturn(all);
        when(square.catalogNames(any())).thenReturn(Map.of(CONSULT, "Online Consultation", PROC, "Ombre Powder Brows"));
        when(payments.findByBusinessIdAndCreatedAtBetween(eq(BIZ), any(), any())).thenReturn(List.of());
        when(customers.findByBusinessIdAndSquareCustomerId(eq(BIZ), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(customerRows.get(inv.<String>getArgument(1))));
        when(followUps.findAll()).thenReturn(List.of());
        when(followUps.save(any())).thenAnswer(inv -> {
            ConsultationFollowUp r = inv.getArgument(0);
            if (r.getId() == null) r.setId(41L);
            return r;
        });
        when(providers.findAllByBusinessId(BIZ)).thenReturn(List.of(
                Provider.builder().id(1L).displayName("Anastasiia Makarenko").squareTeamMemberIds(Set.of(TM_ANASTASIIA)).build()));
        when(mailchimpConfigs.findByBusinessId(BIZ)).thenReturn(Optional.of(MailchimpConfig.builder().businessId(BIZ)
                .apiKey("k-us1").audienceId("a").fromName("Anna Kara").fromEmail("a@x.com").replyToEmail("a@x.com").build()));
        when(mailchimpClient.fetchUndeliverableEmails(any())).thenReturn(Set.of("gone@example.com"));
        when(promos.get(BIZ, PromoConfigService.CONSULTATION_OFFER_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(7500, 50000L, "GROUP-75", true)));

        // Real templates and artist content: the rendered email is part of what's tested.
        service = new ConsultationReengageOneOffService(bookings, payments, customers, followUps, providers, squareProvider,
                mailchimpConfigs, mailchimpClient, mailchimp, new MailchimpEmailTemplateService(), new PreVisitNurtureContent(),
                promos, memberships, links, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void consultation(String customer, String tm, int daysAgo, String email) {
        all.add(booking(customer, tm, CONSULT, NOW.minus(Duration.ofDays(daysAgo))));
        customerRows.put(customer, SquareCustomerMirror.builder().businessId(BIZ).squareCustomerId(customer)
                .givenName("sarah").emailAddress(email).build());
    }

    private static SquareBookingMirror booking(String customer, String tm, String variation, Instant start) {
        return SquareBookingMirror.builder().businessId(BIZ).squareBookingId("bk-" + customer + "-" + variation + "-" + start.getEpochSecond())
                .squareCustomerId(customer).status("ACCEPTED").startAt(start)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment(tm, variation, 30))).build();
    }

    @Test
    void onlyClientsWhoNeverCameBackAreCounted_byWaveAndArtist() throws Exception {
        consultation("c-recent-year", TM_ANASTASIIA, 200, "a@example.com");      // wave 1, Anastasiia
        consultation("c-two-years", TM_FORMER, 500, "b@example.com");            // wave 2, Anna (former artist)
        consultation("c-old", TM_ANASTASIIA, 900, "c@example.com");              // wave 3
        consultation("c-booked", TM_ANASTASIIA, 300, "d@example.com");
        all.add(booking("c-booked", TM_ANASTASIIA, PROC, NOW.minus(Duration.ofDays(250))));
        consultation("c-paid", TM_ANASTASIIA, 300, "e@example.com");
        when(payments.findByBusinessIdAndCreatedAtBetween(eq(BIZ), any(), any())).thenReturn(List.of(
                SquarePaymentMirror.builder().businessId(BIZ).squareCustomerId("c-paid").status("COMPLETED").totalMoney(new BigDecimal("450")).build()));
        consultation("c-this-week", TM_ANASTASIIA, 2, "f@example.com");
        consultation("c-unsub", TM_ANASTASIIA, 200, "gone@example.com");
        consultation("c-no-email", TM_ANASTASIIA, 200, null);

        ConsultationReengageOneOffService.PreviewResult p = service.preview();

        assertThat(p.consultationClients()).isEqualTo(8);
        assertThat(p.excludedHadProcedure()).isEqualTo(1);
        assertThat(p.excludedPaid()).isEqualTo(1);
        assertThat(p.excludedRecentOrInSequence()).isEqualTo(1);
        assertThat(p.excludedUndeliverable()).isEqualTo(1);
        assertThat(p.excludedNoEmail()).isEqualTo(1);
        assertThat(p.waves()).extracting(ConsultationReengageOneOffService.WavePreview::recipients).containsExactly(1, 1, 1);
        assertThat(p.waves().get(0).byArtist()).containsEntry("Anastasiia", 1);
        assertThat(p.waves().get(1).byArtist()).containsEntry("Anna (artist no longer here or unknown)", 1);
        verifyNoInteractions(mailchimp);
        verify(square, never()).addCustomerToGroup(anyString(), anyString());
    }

    @Test
    void sendingAWaveAddsTheDiscountAndRecordsTheOfferForTheExpiryScheduler() throws Exception {
        consultation("c1", TM_ANASTASIIA, 200, "a@example.com");
        consultation("c2", TM_FORMER, 300, "b@example.com");
        consultation("c3", TM_ANASTASIIA, 900, "c@example.com"); // wave 3, not sent

        ConsultationReengageOneOffService.SendResult result = service.send(1);

        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.expires()).isEqualTo("Wednesday, October 14");
        verify(square).addCustomerToGroup("c1", "GROUP-75");
        verify(square).addCustomerToGroup("c2", "GROUP-75");
        verify(square, never()).addCustomerToGroup(eq("c3"), anyString());

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mailchimp).sendWinbackEmail(any(), eq("a@example.com"), eq("Sarah, still thinking about it?"), anyString(), anyString(), html.capture());
        assertThat(html.getValue()).contains("It&#39;s Anastasiia from Anna Kara&#39;s PMU Studio.")
                .contains("We met at your consultation a while ago").contains("Wednesday, October 14")
                .contains("anastasiia-ring").contains("book=procedure&amp;offer=41.sig").doesNotContain("{{").doesNotContain("—");
        verify(mailchimp).sendWinbackEmail(any(), eq("b@example.com"), anyString(), anyString(), anyString(), html.capture());
        assertThat(html.getValue()).contains("It&#39;s Anna, owner of Anna Kara&#39;s PMU Studio.")
                .contains("You had a consultation with us a while ago").contains("anna-ring");

        ArgumentCaptor<ConsultationFollowUp> rows = ArgumentCaptor.forClass(ConsultationFollowUp.class);
        verify(followUps, atLeastOnce()).save(rows.capture());
        ConsultationFollowUp last = rows.getAllValues().get(rows.getAllValues().size() - 1);
        assertThat(last.getStopReason()).isEqualTo(ConsultationFollowUp.STOP_REENGAGE);
        assertThat(last.getOfferState()).isEqualTo(ConsultationFollowUp.STATE_SENT);
        assertThat(last.getOfferExpiresAt()).isNotNull();
    }

    @Test
    void aClientAlreadyReachedIsNeverSentAgain() throws Exception {
        consultation("c1", TM_ANASTASIIA, 200, "a@example.com");
        when(followUps.findAll()).thenReturn(List.of(ConsultationFollowUp.builder().businessId(BIZ).squareCustomerId("c1")
                .squareBookingId("x").visitKind(ConsultationReengageOneOffService.VISIT_KIND).consultationStartAt(NOW).build()));

        assertThat(service.send(1).sent()).isZero();
        verifyNoInteractions(mailchimp);
    }
}
