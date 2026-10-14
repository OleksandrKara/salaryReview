package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.marketing.MarketingContactsRepository;
import com.salonreview.repo.MailchimpConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademyLeadFunnelSchedulerTest {

    // 2026-10-14 18:00 UTC = 11:00 PT (inside SMS hours); 2026-10-15 06:00 UTC = 23:00 PT.
    private static final Instant DAY = Instant.parse("2026-10-14T18:00:00Z");
    private static final Instant NIGHT = Instant.parse("2026-10-15T06:00:00Z");

    private JdbcTemplate jdbc;
    private TwilioSmsService sms;
    private MailchimpEmailService mailchimp;
    private MailchimpEmailTemplateService templates;
    private MarketingContactsRepository contacts;
    private MailchimpConfig mc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sms = mock(TwilioSmsService.class);
        mailchimp = mock(MailchimpEmailService.class);
        templates = mock(MailchimpEmailTemplateService.class);
        contacts = mock(MarketingContactsRepository.class);
        mc = mock(MailchimpConfig.class);
        when(templates.render(eq(2L), anyString(), anyMap())).thenReturn(Optional.of("<html>"));
        when(jdbc.update(contains("INSERT INTO academy_lead_touch"), any(Object[].class))).thenReturn(1);
        when(sms.sendTemplated(anyLong(), anyString(), anyString(), anyMap())).thenReturn(TwilioSmsService.SmsSendResult.ok());
    }

    private AcademyLeadFunnelScheduler scheduler(Instant now) {
        return new AcademyLeadFunnelScheduler(jdbc, mock(SmsAutomationService.class), sms, mock(MailchimpConfigRepository.class),
                mailchimp, templates, contacts, mock(MailchimpClient.class), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void done(String... steps) {
        when(jdbc.queryForList(contains("SELECT step"), eq(String.class), any(Object[].class))).thenReturn(List.of(steps));
    }

    private static AcademyLeadFunnelScheduler.Lead lead(Instant created, boolean smsConsent) {
        return new AcademyLeadFunnelScheduler.Lead(7L, "jane doe", "+16195550100", "jane@example.com",
                "Brows Basic Course; Lips Basic Course", smsConsent, created);
    }

    @Test
    void newLeadGetsConfirmationEmailFirst() throws Exception {
        done();
        scheduler(DAY).advance(lead(DAY.minusSeconds(30), false), mc, DAY);
        verify(mailchimp).sendWinbackEmail(eq(mc), eq("jane@example.com"), eq("We got your request, Jane"), anyString(),
                contains("confirm_email"), eq("<html>"));
        verifyNoInteractions(sms);
    }

    @Test
    void confirmationTextAsksWhenToCall() {
        done("confirm_email");
        scheduler(DAY).advance(lead(DAY.minusSeconds(90), false), mc, DAY);
        verify(sms).sendTemplated(2L, "academy_request_confirmation", "+16195550100", Map.of("name", "Jane"));
    }

    @Test
    void textWaitsForTheMorning() {
        done("confirm_email");
        scheduler(NIGHT).advance(lead(NIGHT.minusSeconds(90), false), mc, NIGHT);
        verifyNoInteractions(sms);
        verify(jdbc, never()).update(contains("INSERT INTO academy_lead_touch"), any(Object[].class));
    }

    @Test
    void checkinIsAlwaysAnEmail() throws Exception {
        done("confirm_email", "confirm_sms", "reviews_email");
        when(mailchimp.sendWinbackEmail(any(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("c1");
        scheduler(DAY).advance(lead(DAY.minus(Duration.ofDays(3)).minusSeconds(60), true), mc, DAY);
        verifyNoInteractions(sms);
        verify(mailchimp).sendWinbackEmail(eq(mc), anyString(), eq("Any questions, Jane?"), anyString(), contains("checkin"), anyString());
        verify(jdbc).update(contains("SET campaign_id"), eq("c1"), eq(7L), eq("checkin"));
    }

    @Test
    void replyStopsTheFunnel() throws Exception {
        done("confirm_email", "confirm_sms");
        Instant created = DAY.minus(Duration.ofDays(1)).minusSeconds(60);
        when(contacts.textedUsSince("+16195550100", 2L, created)).thenReturn(true);
        scheduler(DAY).advance(lead(created, false), mc, DAY);
        verify(jdbc).update(contains("status = 'replied'"), eq(7L));
        verifyNoInteractions(mailchimp);
    }

    @Test
    void stepPastItsWindowIsSkippedNotSent() throws Exception {
        done("confirm_email", "confirm_sms", "reviews_email", "checkin");
        Instant created = DAY.minus(Duration.ofDays(9).plusHours(1));
        scheduler(DAY).advance(lead(created, false), mc, DAY);
        verify(jdbc).update(contains("INSERT INTO academy_lead_touch"), eq(7L), eq("payment_email"), eq("-"), eq("skipped_late"), isNull());
        verifyNoInteractions(mailchimp);
    }

    @Test
    void courseWording() {
        assertThat(AcademyLeadFunnelScheduler.coursesText(null)).isEqualTo("PMU training");
        assertThat(AcademyLeadFunnelScheduler.coursesText("A; B; C")).isEqualTo("A, B and C");
        assertThat(AcademyLeadFunnelScheduler.coursesHtml("Lips <Basic>")).contains("<b>Lips &lt;Basic&gt;</b>");
        assertThat(AcademyLeadFunnelScheduler.firstName("  ")).isEqualTo("there");
        assertThat(AcademyLeadFunnelScheduler.subject(AcademyLeadFunnelScheduler.STEPS.get(0), "there")).isEqualTo("We got your request");
    }
}
