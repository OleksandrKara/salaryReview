package com.salonreview.sms;

import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationFollowUpSchedulerTest {

    private static final Long BIZ = 2L;
    /** Tuesday 11:00 Pacific. */
    private static final Instant NOW = Instant.parse("2026-10-06T18:00:00Z");
    private static final String CONSULT_VAR = "var-consult";
    private static final String PROC_VAR = "var-combo";
    private static final String TM = "tm-ana";
    private static final String CUST = "cust1";
    private static final String PHONE = "+16195550111";

    private SquareBookingMirrorRepository mirror;
    private ConsultationFollowUpRepository repo;
    private SquareClientProvider squareProvider;
    private SquareClient square;
    private SmsAutomationService automations;
    private ProviderRepository providers;
    private TwilioSmsService sms;
    private SmsMessageRepository smsMessages;
    private MailchimpConfigRepository mailchimpConfigs;
    private MailchimpEmailService mailchimp;
    private MailchimpEmailTemplateService templates;
    private PreVisitNurtureContent content;
    private TelegramNotificationService telegram;
    private ConsultationFollowUpLinks links;
    private PromoConfigService promos;
    private SameDayRebookingGroupMembershipRepository memberships;
    private MailchimpConfig mcConfig;

    @BeforeEach
    void setUp() {
        mirror = mock(SquareBookingMirrorRepository.class);
        repo = mock(ConsultationFollowUpRepository.class);
        squareProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        automations = mock(SmsAutomationService.class);
        providers = mock(ProviderRepository.class);
        sms = mock(TwilioSmsService.class);
        smsMessages = mock(SmsMessageRepository.class);
        mailchimpConfigs = mock(MailchimpConfigRepository.class);
        mailchimp = mock(MailchimpEmailService.class);
        templates = mock(MailchimpEmailTemplateService.class);
        content = mock(PreVisitNurtureContent.class);
        telegram = mock(TelegramNotificationService.class);
        links = mock(ConsultationFollowUpLinks.class);
        promos = mock(PromoConfigService.class);
        memberships = mock(SameDayRebookingGroupMembershipRepository.class);

        mcConfig = MailchimpConfig.builder().businessId(BIZ).apiKey("k-us1").audienceId("a").fromName("Anna Kara")
                .fromEmail("anna@pmu-annakara.com").replyToEmail("anna@pmu-annakara.com").build();
        when(mailchimpConfigs.findAll()).thenReturn(List.of(mcConfig));
        when(squareProvider.forBusiness(BIZ)).thenReturn(square);
        when(automations.isEnabled(BIZ, "consultation_follow_up")).thenReturn(true);
        when(square.catalogNames(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            Map<String, String> m = new java.util.HashMap<>();
            for (String id : ids) m.put(id, CONSULT_VAR.equals(id) ? "Online Consultation" : "Combo Technique");
            return m;
        });
        when(square.customerGivenNames(List.of(CUST))).thenReturn(Map.of(CUST, "sarah"));
        when(square.customerPhone(CUST)).thenReturn(PHONE);
        when(square.customerEmail(CUST)).thenReturn("sarah@example.com");
        when(square.bookingsForCustomer(eq(CUST), any())).thenReturn(List.of());
        when(providers.findAllByBusinessId(BIZ)).thenReturn(List.of(
                Provider.builder().id(21L).displayName("Anastasiia Makarenko").squareTeamMemberIds(Set.of(TM)).build()));
        when(sms.sendTemplated(eq(BIZ), anyString(), anyString(), any())).thenReturn(new TwilioSmsService.SmsSendResult(true, null));
        when(templates.render(eq(BIZ), anyString(), any())).thenReturn(Optional.of("<html></html>"));
        when(content.artist(eq(BIZ), any())).thenReturn(Optional.empty());
        when(content.availabilityVariationId(BIZ)).thenReturn(Optional.of(PROC_VAR));
        when(links.stopUrl(anyLong())).thenReturn("https://salon/stop?id=1&sig=x");
    }

    private ConsultationFollowUpScheduler at(Instant now) {
        return new ConsultationFollowUpScheduler(mirror, repo, squareProvider, automations, providers, sms, smsMessages,
                mailchimpConfigs, mailchimp, templates, content, telegram, links, promos, memberships, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static SquareBookingMirror consultation(Instant start) {
        return SquareBookingMirror.builder().id(1L).businessId(BIZ).squareBookingId("bk-c").squareCustomerId(CUST)
                .status("ACCEPTED").startAt(start).createdAt(start.minus(Duration.ofDays(3)))
                .appointmentSegments(List.of(new SquareBookingMirror.Segment(TM, CONSULT_VAR, 30))).build();
    }

    private static ConsultationFollowUp row(Instant consultStart) {
        return ConsultationFollowUp.builder().id(7L).businessId(BIZ).squareBookingId("bk-c").squareCustomerId(CUST)
                .teamMemberId(TM).artistName("Anastasiia").visitKind("consultation_online").consultationStartAt(consultStart)
                .customerName("Sarah").phoneNumber(PHONE).staffAlertSentAt(consultStart.plus(Duration.ofDays(1)))
                .createdAt(consultStart.plus(Duration.ofDays(1))).build();
    }

    @Test
    @DisplayName("a consultation from yesterday is enrolled with the artist and client; nothing is sent yet")
    void enrollsYesterdaysConsultation() {
        SquareBookingMirror c = consultation(NOW.minus(Duration.ofHours(26)));
        when(mirror.findByBusinessIdAndStartAtBetween(eq(BIZ), any(), any())).thenReturn(List.of(c));
        when(mirror.findByBusinessIdAndSquareCustomerId(BIZ, CUST)).thenReturn(List.of(c));

        at(NOW).run();

        ArgumentCaptor<ConsultationFollowUp> saved = ArgumentCaptor.forClass(ConsultationFollowUp.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getArtistName()).isEqualTo("Anastasiia");
        assertThat(saved.getValue().getCustomerName()).isEqualTo("Sarah");
        assertThat(saved.getValue().getVisitKind()).isEqualTo("consultation_online");
        assertThat(saved.getValue().getStopReason()).isNull();
        verifyNoInteractions(sms, mailchimp);
    }

    @Test
    @DisplayName("a returning client (had a procedure before) is recorded as not eligible, never messaged")
    void returningClientNotEligible() {
        SquareBookingMirror c = consultation(NOW.minus(Duration.ofHours(26)));
        SquareBookingMirror earlier = SquareBookingMirror.builder().id(2L).businessId(BIZ).squareBookingId("bk-old")
                .squareCustomerId(CUST).status("ACCEPTED").startAt(NOW.minus(Duration.ofDays(300)))
                .appointmentSegments(List.of(new SquareBookingMirror.Segment(TM, PROC_VAR, 180))).build();
        when(mirror.findByBusinessIdAndStartAtBetween(eq(BIZ), any(), any())).thenReturn(List.of(c));
        when(mirror.findByBusinessIdAndSquareCustomerId(BIZ, CUST)).thenReturn(List.of(c, earlier));

        at(NOW).run();

        ArgumentCaptor<ConsultationFollowUp> saved = ArgumentCaptor.forClass(ConsultationFollowUp.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStopReason()).isEqualTo(ConsultationFollowUp.STOP_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("first tick for a new row: staff Telegram alert with the stop button, no text yet")
    void staffAlertFirst() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(1)));
        r.setStaffAlertSentAt(null);
        at(NOW).advance(r, square, mcConfig, NOW);
        verify(telegram).sendConsultationFollowUpAlert(eq(BIZ), contains("Sarah"), eq("Anastasiia"), any(), eq("https://salon/stop?id=1&sig=x"));
        assertThat(r.getStaffAlertSentAt()).isEqualTo(NOW);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("day 2: thank-you text from the artist with the booking link")
    void day2Thanks() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(2)).minus(Duration.ofHours(1)));
        at(NOW).advance(r, square, mcConfig, NOW);
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(BIZ), eq("consultation_follow_up_thanks"), eq(PHONE), vars.capture());
        assertThat(vars.getValue()).containsEntry("name", "Sarah").containsEntry("artist", "Anastasiia");
        assertThat(r.getThanksSmsState()).isEqualTo(ConsultationFollowUp.STATE_SENT);
    }

    @Test
    @DisplayName("booked a procedure (asked of Square itself): the sequence stops, nothing sent")
    void bookedStops() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(2)).minus(Duration.ofHours(1)));
        when(square.bookingsForCustomer(eq(CUST), any())).thenReturn(List.of(new SquareClient.Booking("bk-p", "ACCEPTED",
                NOW.plus(Duration.ofDays(9)).toString(), NOW.minus(Duration.ofHours(3)).toString(), null, null, CUST, null, null,
                List.of(new SquareClient.AppointmentSegment(TM, PROC_VAR, 180)))));
        at(NOW).advance(r, square, mcConfig, NOW);
        assertThat(r.getStopReason()).isEqualTo(ConsultationFollowUp.STOP_BOOKED);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("replied to a text: the sequence stops")
    void repliedStops() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(5)).minus(Duration.ofHours(1)));
        r.setThanksSmsState(ConsultationFollowUp.STATE_SENT);
        when(smsMessages.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(eq(BIZ), eq(PHONE), eq("INBOUND"), any()))
                .thenReturn(true);
        at(NOW).advance(r, square, mcConfig, NOW);
        assertThat(r.getStopReason()).isEqualTo(ConsultationFollowUp.STOP_REPLIED);
        verifyNoInteractions(mailchimp);
    }

    @Test
    @DisplayName("a step that is days overdue (after an outage) is skipped, not sent late")
    void overdueSkipped() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(9)));
        at(NOW).advance(r, square, mcConfig, NOW);
        assertThat(r.getThanksSmsState()).isEqualTo(ConsultationFollowUp.STATE_SKIPPED_LATE);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("day 21: check-in text names the artist's real openings from Square")
    void day21WithOpenings() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(21)).minus(Duration.ofHours(1)));
        r.setThanksSmsState("SENT");
        r.setInfoEmailState("SENT");
        when(square.availableSlotStarts(eq(TM), eq(PROC_VAR), any(), any())).thenReturn(List.of(
                Instant.parse("2026-10-14T17:00:00Z"), Instant.parse("2026-10-14T20:00:00Z"), Instant.parse("2026-10-16T17:00:00Z")));
        at(NOW).advance(r, square, mcConfig, NOW);
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(BIZ), eq("consultation_follow_up_checkin"), eq(PHONE), vars.capture());
        assertThat(vars.getValue().get("openingsClause")).isEqualTo(" I have openings on Wed, Oct 14 and Fri, Oct 16.");
    }

    @Test
    @DisplayName("day 45: client joins the READY75 group, offer email and SMS go out with the 7-day deadline")
    void day45Offer() throws Exception {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(45)).minus(Duration.ofHours(1)));
        r.setThanksSmsState("SENT"); r.setInfoEmailState("SENT"); r.setCheckinSmsState("SENT");
        when(promos.get(BIZ, "READY75")).thenReturn(Optional.of(new PromoConfigService.PromoTerms(7500, 50000L, "grp-75", true)));
        at(NOW).advance(r, square, mcConfig, NOW);
        verify(square).addCustomerToGroup(CUST, "grp-75");
        verify(memberships).save(any(SameDayRebookingGroupMembership.class));
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(sms).sendTemplated(eq(BIZ), eq("consultation_follow_up_offer"), eq(PHONE), vars.capture());
        assertThat(vars.getValue().get("expires")).isEqualTo("Tuesday, October 13");
        verify(mailchimp).sendWinbackEmail(eq(mcConfig), eq("sarah@example.com"), startsWith("Last chance: $75 OFF"), any(), any(), any());
        assertThat(r.getOfferState()).isEqualTo(ConsultationFollowUp.STATE_SENT);
        assertThat(r.getOfferExpiresAt()).isEqualTo(Instant.parse("2026-10-14T06:59:59Z"));
    }

    @Test
    @DisplayName("day 45 with no marketing consent and no email: a plain check-in, no discount")
    void day45NoConsentNoEmail() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(45)).minus(Duration.ofHours(1)));
        r.setThanksSmsState("SENT"); r.setInfoEmailState("SKIPPED_NO_EMAIL"); r.setCheckinSmsState("SENT");
        when(square.customerEmail(CUST)).thenReturn(null);
        when(promos.get(BIZ, "READY75")).thenReturn(Optional.of(new PromoConfigService.PromoTerms(7500, 50000L, "grp-75", true)));
        when(sms.sendTemplated(eq(BIZ), eq("consultation_follow_up_offer"), anyString(), any()))
                .thenReturn(new TwilioSmsService.SmsSendResult(false, "no_consent"));
        at(NOW).advance(r, square, mcConfig, NOW);
        verify(sms).sendTemplated(eq(BIZ), eq("consultation_follow_up_last_checkin"), eq(PHONE), any());
        assertThat(r.getOfferState()).isEqualTo(ConsultationFollowUp.STATE_SKIPPED);
    }

    @Test
    @DisplayName("offer window closed: booked inside it keeps the discount until 2 days after the visit")
    void offerExtendedForBookedVisit() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(53)));
        r.setOfferState("SENT");
        r.setOfferExpiresAt(NOW.minus(Duration.ofHours(2)));
        when(repo.findByBusinessIdAndOfferStateAndOfferExtendedAtIsNullAndOfferExpiresAtBefore(eq(BIZ), eq("SENT"), any()))
                .thenReturn(List.of(r));
        when(promos.get(BIZ, "READY75")).thenReturn(Optional.of(new PromoConfigService.PromoTerms(7500, 50000L, "grp-75", true)));
        SameDayRebookingGroupMembership m = SameDayRebookingGroupMembership.builder().squareCustomerId(CUST).groupId("grp-75")
                .expiresAt(NOW.plus(Duration.ofDays(100))).build();
        when(memberships.findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(BIZ, CUST, "grp-75"))
                .thenReturn(Optional.of(m));
        Instant visit = NOW.plus(Duration.ofDays(12));
        when(square.bookingsForCustomer(eq(CUST), any())).thenReturn(List.of(new SquareClient.Booking("bk-p", "ACCEPTED",
                visit.toString(), NOW.minus(Duration.ofDays(3)).toString(), null, null, CUST, null, null,
                List.of(new SquareClient.AppointmentSegment(TM, PROC_VAR, 180)))));
        at(NOW).closeExpiredOffers(BIZ, square, NOW);
        assertThat(m.getExpiresAt()).isEqualTo(visit.plus(Duration.ofDays(2)));
        assertThat(r.getOfferExtendedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("offer window closed without a booking: the discount ends now")
    void offerEndsWithoutBooking() {
        ConsultationFollowUp r = row(NOW.minus(Duration.ofDays(53)));
        r.setOfferState("SENT");
        r.setOfferExpiresAt(NOW.minus(Duration.ofHours(2)));
        when(repo.findByBusinessIdAndOfferStateAndOfferExtendedAtIsNullAndOfferExpiresAtBefore(eq(BIZ), eq("SENT"), any()))
                .thenReturn(List.of(r));
        when(promos.get(BIZ, "READY75")).thenReturn(Optional.of(new PromoConfigService.PromoTerms(7500, 50000L, "grp-75", true)));
        SameDayRebookingGroupMembership m = SameDayRebookingGroupMembership.builder().squareCustomerId(CUST).groupId("grp-75")
                .expiresAt(NOW.plus(Duration.ofDays(100))).build();
        when(memberships.findFirstByBusinessIdAndSquareCustomerIdAndGroupIdAndRemovedAtIsNullOrderByCreatedAtDesc(BIZ, CUST, "grp-75"))
                .thenReturn(Optional.of(m));
        at(NOW).closeExpiredOffers(BIZ, square, NOW);
        assertThat(m.getExpiresAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("at night nothing is sent, and a disabled automation does nothing at all")
    void nightAndDisabled() {
        ConsultationFollowUp r = row(Instant.parse("2026-10-04T10:00:00Z"));
        when(repo.findByBusinessIdAndStopReasonIsNullAndConsultationStartAtAfter(eq(BIZ), any())).thenReturn(List.of(r));
        at(Instant.parse("2026-10-06T10:00:00Z")).run(); // 3:00 AM Pacific
        verifyNoInteractions(sms, telegram);

        clearInvocations(mirror);
        when(automations.isEnabled(BIZ, "consultation_follow_up")).thenReturn(false);
        at(NOW).run();
        verifyNoInteractions(sms, telegram);
        verify(mirror, never()).findByBusinessIdAndStartAtBetween(any(), any(), any());
    }
}
