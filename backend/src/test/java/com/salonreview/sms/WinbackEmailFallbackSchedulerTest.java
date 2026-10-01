package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.MailchimpConfigRepository;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Evening email follow-up shared by lapsed/repeat_customer_winback and (2026-09-04)
 * same_day_rebooking_discount — see WinbackEmailFallbackScheduler's own class doc. */
class WinbackEmailFallbackSchedulerTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String PHONE = "+15551234567";
    private static final String CUSTOMER_ID = "cust1";

    private SmsMessageRepository smsMessageRepository;
    private WinbackEmailSendRepository winbackEmailSendRepository;
    private MailchimpConfigRepository mailchimpConfigRepository;
    private MailchimpEmailService mailchimpEmailService;
    private MailchimpEmailTemplateService templateService;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private SmsAutomationService automationService;
    private PromoConfigService promoConfigService;
    private ProviderVisitRepository providerVisitRepository;
    private WinbackEmailFallbackScheduler scheduler;

    @BeforeEach
    void setUp() {
        smsMessageRepository = mock(SmsMessageRepository.class);
        winbackEmailSendRepository = mock(WinbackEmailSendRepository.class);
        mailchimpConfigRepository = mock(MailchimpConfigRepository.class);
        mailchimpEmailService = mock(MailchimpEmailService.class);
        templateService = mock(MailchimpEmailTemplateService.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        automationService = mock(SmsAutomationService.class);
        promoConfigService = mock(PromoConfigService.class);
        providerVisitRepository = mock(ProviderVisitRepository.class);
        scheduler = new WinbackEmailFallbackScheduler(smsMessageRepository, winbackEmailSendRepository,
                mailchimpConfigRepository, mailchimpEmailService, templateService, squareClientProvider,
                automationService, promoConfigService, providerVisitRepository, "https://salon.akluxnails.com");

        MailchimpConfig config = MailchimpConfig.builder().businessId(BUSINESS_ID)
                .apiKey("k-us1").audienceId("a1").fromName("Lucy").fromEmail("lucy@akluxnails.com")
                .replyToEmail("lucy@akluxnails.com").build();
        when(mailchimpConfigRepository.findAll()).thenReturn(List.of(config));
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(automationService.isEnabled(eq(BUSINESS_ID), anyString())).thenReturn(true);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of(CUSTOMER_ID));
        when(square.customerEmail(CUSTOMER_ID)).thenReturn("jane@example.com");
        when(square.customerGivenNames(List.of(CUSTOMER_ID))).thenReturn(Map.of(CUSTOMER_ID, "Jane"));
        when(providerVisitRepository.findByBusinessIdAndCustomerIdOrderByServiceDateDesc(eq(BUSINESS_ID), eq(CUSTOMER_ID), any()))
                .thenReturn(List.of());
        when(templateService.render(eq(BUSINESS_ID), anyString(), any())).thenReturn(Optional.of("<html></html>"));
        when(winbackEmailSendRepository.existsBySmsMessageId(any())).thenReturn(false);
    }

    private static SmsMessage candidate(Long id, String automationKey, String clickToken) {
        return SmsMessage.builder().id(id).businessId(BUSINESS_ID).automationKey(automationKey)
                .phoneNumber(PHONE).clickToken(clickToken).direction("OUTBOUND").status("SENT")
                .body("").createdAt(Instant.now()).build();
    }

    @Test
    @DisplayName("same_day_rebooking_discount candidate: uses REBOOK10 (not WINBACK5) for the DISCOUNT token")
    void sameDayRebookingUsesItsOwnPromoCode() {
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any()))
                .thenReturn(List.of(candidate(1L, SameDayRebookingScheduler.AUTOMATION_KEY, "tok1")));
        when(smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("INBOUND"), any())).thenReturn(false);
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, 9900L, "GROUP1", true)));

        scheduler.sendDueFollowUps();

        verify(promoConfigService).get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE);
        verify(promoConfigService, never()).get(BUSINESS_ID, PromoConfigService.WINBACK_PROMO_CODE);
        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq(SameDayRebookingScheduler.AUTOMATION_KEY), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("DISCOUNT", "$10");
    }

    @Test
    @DisplayName("lapsed_customer_winback candidate: still uses WINBACK5, unaffected by same-day rebooking's own promo code")
    void lapsedWinbackStillUsesWinbackPromoCode() {
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any()))
                .thenReturn(List.of(candidate(2L, LapsedCustomerWinbackScheduler.AUTOMATION_KEY, "tok2")));
        when(smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("INBOUND"), any())).thenReturn(false);
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.WINBACK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(500, 9900L, "GROUP2", true)));

        scheduler.sendDueFollowUps();

        verify(promoConfigService).get(BUSINESS_ID, PromoConfigService.WINBACK_PROMO_CODE);
        verify(promoConfigService, never()).get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE);
        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq(BUSINESS_ID), eq(LapsedCustomerWinbackScheduler.AUTOMATION_KEY), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("DISCOUNT", "$5");
    }

    @Test
    @DisplayName("candidate query now includes same_day_rebooking_discount alongside both winbacks")
    void candidateQueryIncludesAllThreeAutomationKeys() {
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any()))
                .thenReturn(List.of());

        scheduler.sendDueFollowUps();

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(smsMessageRepository).findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), keysCaptor.capture(), eq("OUTBOUND"), eq("SENT"), any(), any());
        assertThat(keysCaptor.getValue()).containsExactlyInAnyOrder(
                LapsedCustomerWinbackScheduler.AUTOMATION_KEY, RepeatCustomerWinbackScheduler.AUTOMATION_KEY,
                SameDayRebookingScheduler.AUTOMATION_KEY);
    }

    @Test
    @DisplayName("real send: resolves email/name, renders the template, sends via Mailchimp, saves STATE_SENT")
    void sendsAndSavesSentState() {
        SmsMessage sms = candidate(3L, SameDayRebookingScheduler.AUTOMATION_KEY, "tok3");
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("INBOUND"), any())).thenReturn(false);
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, 9900L, "GROUP1", true)));
        when(providerVisitRepository.findByBusinessIdAndCustomerIdOrderByServiceDateDesc(eq(BUSINESS_ID), eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(ProviderVisit.builder().businessId(BUSINESS_ID).customerId(CUSTOMER_ID)
                        .providerName("Lesya Petrova").build()));

        try {
            when(mailchimpEmailService.sendWinbackEmail(any(), eq("jane@example.com"), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn("campaign123");
        } catch (Exception ignored) {
            // mock setup only, sendWinbackEmail's checked Exception never actually thrown here
        }

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        WinbackEmailSend saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(WinbackEmailSend.STATE_SENT);
        assertThat(saved.getEmailAddress()).isEqualTo("jane@example.com");
        assertThat(saved.getMailchimpCampaignId()).isEqualTo("campaign123");
        assertThat(saved.getAutomationKey()).isEqualTo(SameDayRebookingScheduler.AUTOMATION_KEY);
    }

    @Test
    @DisplayName("candidate already replied → SKIPPED_REPLIED, no email sent")
    void alreadyRepliedSkipsWithoutSending() {
        SmsMessage sms = candidate(4L, SameDayRebookingScheduler.AUTOMATION_KEY, "tok4");
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any())).thenReturn(List.of(sms));
        when(smsMessageRepository.existsByBusinessIdAndPhoneNumberAndDirectionAndCreatedAtAfter(
                eq(BUSINESS_ID), eq(PHONE), eq("INBOUND"), any())).thenReturn(true);

        scheduler.sendDueFollowUps();

        ArgumentCaptor<WinbackEmailSend> captor = ArgumentCaptor.forClass(WinbackEmailSend.class);
        verify(winbackEmailSendRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(WinbackEmailSend.STATE_SKIPPED_REPLIED);
        org.mockito.Mockito.verifyNoInteractions(mailchimpEmailService);
    }

    // --- 2026-10-01: same-day rebooking texts sent after yesterday's 19:00 run ------------------

    @Test
    @DisplayName("promoStillValid: REBOOK link with a future expiry is valid, a past one isn't, other links always are")
    void promoStillValid() {
        Instant now = Instant.now();
        SmsMessage future = candidate(1L, SameDayRebookingScheduler.AUTOMATION_KEY, "t1");
        future.setLinkTarget("REBOOK:" + now.plusSeconds(3600).getEpochSecond());
        SmsMessage past = candidate(2L, SameDayRebookingScheduler.AUTOMATION_KEY, "t2");
        past.setLinkTarget("REBOOK:" + now.minusSeconds(60).getEpochSecond());
        SmsMessage other = candidate(3L, LapsedCustomerWinbackScheduler.AUTOMATION_KEY, "t3");
        other.setLinkTarget("WINBACK");

        assertThat(WinbackEmailFallbackScheduler.promoStillValid(future, now)).isTrue();
        assertThat(WinbackEmailFallbackScheduler.promoStillValid(past, now)).isFalse();
        assertThat(WinbackEmailFallbackScheduler.promoStillValid(other, now)).isTrue();
    }

    @Test
    @DisplayName("10:00 run: only same-day rebooking texts, yesterday 19:00 to midnight; a live offer is emailed, an expired one isn't")
    void morningRunForYesterdayEveningRebookingTexts() {
        java.time.ZoneId pt = java.time.ZoneId.of("America/Los_Angeles");
        java.time.LocalDate today = java.time.LocalDate.now(pt);
        Instant yesterday1930 = today.minusDays(1).atTime(19, 30).atZone(pt).toInstant();
        SmsMessage live = candidate(10L, SameDayRebookingScheduler.AUTOMATION_KEY, "a");
        live.setCreatedAt(yesterday1930);
        live.setLinkTarget("REBOOK:" + Instant.now().plusSeconds(3600).getEpochSecond());
        SmsMessage expired = candidate(11L, SameDayRebookingScheduler.AUTOMATION_KEY, "b");
        expired.setCreatedAt(yesterday1930);
        expired.setLinkTarget("REBOOK:" + Instant.now().minusSeconds(60).getEpochSecond());
        when(smsMessageRepository.findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), any(), eq("OUTBOUND"), eq("SENT"), any(), any()))
                .thenReturn(List.of(live, expired));

        scheduler.sendMorningRebookingFollowUps();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(smsMessageRepository).findByBusinessIdAndAutomationKeyInAndDirectionAndStatusAndClickedAtIsNullAndCreatedAtBetween(
                eq(BUSINESS_ID), keys.capture(), eq("OUTBOUND"), eq("SENT"), from.capture(), to.capture());
        assertThat(keys.getValue()).containsExactly(SameDayRebookingScheduler.AUTOMATION_KEY);
        assertThat(from.getValue()).isEqualTo(today.minusDays(1).atTime(19, 0).atZone(pt).toInstant());
        assertThat(to.getValue()).isEqualTo(today.atStartOfDay(pt).toInstant());
        verify(winbackEmailSendRepository).existsBySmsMessageId(10L);
        verify(winbackEmailSendRepository, never()).existsBySmsMessageId(11L);
    }
}
