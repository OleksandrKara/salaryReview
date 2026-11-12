package com.salonreview.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.Role;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.telegram.TelegramNotificationService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class NeedsReplyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T20:00:00Z");
    private static final String PHONE = "+15550001111";

    @Autowired private BusinessRepository businesses;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    private ManagerMetricsService metrics;
    private NeedsReplyService service;
    private long b;
    private AppUser anna;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("NR").shortCode("nr-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        anna = users.save(AppUser.builder().businessId(b).username("anna-" + System.nanoTime()).passwordHash("x").role(Role.MANAGER).active(true).build());
        LeadFollowUpService leads = mock(LeadFollowUpService.class);
        when(leads.listFor(anyLong(), any())).thenReturn(List.of());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        metrics = new ManagerMetricsService(jdbc, users, leads, clock);
        service = new NeedsReplyService(metrics, jdbc, clock);
        shift("2026-11-11T16:00:00Z", null);
    }

    private void shift(String start, String end) {
        jdbc.update("INSERT INTO manager_time_entry (user_id, work_date, start_at, end_at) VALUES (?, ?::date, ?, ?)",
                anna.getId(), start.substring(0, 10), Timestamp.from(Instant.parse(start)), end == null ? null : Timestamp.from(Instant.parse(end)));
    }

    private long sms(long business, String phone, String dir, String at, String body, String automationKey, boolean read) {
        return jdbc.queryForObject("""
                INSERT INTO sms_message (business_id, direction, phone_number, body, status, created_at, automation_key, read_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, business, dir, phone, body, "INBOUND".equals(dir) ? "RECEIVED" : "SENT", Timestamp.from(Instant.parse(at)),
                automationKey, read ? Timestamp.from(Instant.parse(at)) : null);
    }

    private void in(String at, String body) { sms(b, PHONE, "INBOUND", at, body, null, false); }

    private long lead(String status, String created) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, created_at, legacy)
                VALUES (?, 'SMS', 'test', ?, 'CREATED', ?, ?, ?) RETURNING id
                """, Long.class, b, PHONE, status, Timestamp.from(Instant.parse(created)), "OUTDATED".equals(status));
    }

    private void event(long lead, String type, String text, String at) {
        jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by, created_at) VALUES (?, ?, ?, ?, 'anna', ?)",
                lead, b, type, text, Timestamp.from(Instant.parse(at)));
    }

    @Test
    void lindaCaseQuestionAfterOldAutomationWithOutdatedLeadAndNoEventsIsRaised() {
        long lead = lead("OUTDATED", "2026-10-01T10:00:00Z");
        sms(b, PHONE, "OUTBOUND", "2026-11-08T10:00:00Z", "Time to rebook!", "rebook_nudge", false);
        in("2026-11-11T18:00:00Z", "Hi, can I move my appointment to Friday?");

        var q = service.queue(b);

        assertThat(q.count()).isEqualTo(1);
        var it = q.items().get(0);
        assertThat(it.leadId()).isEqualTo(lead);
        assertThat(it.leadStatus()).isEqualTo("OUTDATED");
        assertThat(it.ask()).isTrue();
        assertThat(it.waitSeconds()).isEqualTo(2 * 3600);
    }

    @Test
    void chatWithoutAnyLeadIsRaisedWithNoLeadId() {
        in("2026-11-11T19:00:00Z", "do you take walk-ins?");
        var q = service.queue(b);
        assertThat(q.items()).hasSize(1);
        assertThat(q.items().get(0).leadId()).isNull();
    }

    @Test
    void stopAndThankYouAreNotRaised() {
        in("2026-11-11T18:00:00Z", "STOP");
        sms(b, "+15550002222", "INBOUND", "2026-11-11T18:05:00Z", "Thank you so much!", null, false);
        assertThat(service.queue(b).count()).isZero();
    }

    @Test
    void viewingClearsAStatementButNotAQuestion() {
        sms(b, PHONE, "INBOUND", "2026-11-11T18:00:00Z", "I am running late", null, false);
        sms(b, "+15550002222", "INBOUND", "2026-11-11T18:00:00Z", "Can you do 3pm?", null, false);
        assertThat(service.queue(b).count()).isEqualTo(2);

        jdbc.update("UPDATE sms_message SET read_at = now()");

        var q = service.queue(b);
        assertThat(q.count()).isEqualTo(1);
        assertThat(q.items().get(0).ask()).isTrue();
        assertThat(q.items().get(0).phone()).isEqualTo("+15550002222");
    }

    @Test
    void automationAfterQuestionDoesNotClearButIsNoted() {
        in("2026-11-11T18:00:00Z", "Where do I park?");
        sms(b, PHONE, "OUTBOUND", "2026-11-11T18:01:00Z", "Thanks for your message", "auto_ack", false);
        var q = service.queue(b);
        assertThat(q.count()).isEqualTo(1);
        assertThat(q.items().get(0).automationKey()).isEqualTo("auto_ack");
    }

    @Test
    void shortAnswerToAutomationWithinDayIsNotRaisedButQuestionIs() {
        sms(b, PHONE, "OUTBOUND", "2026-11-11T10:00:00Z", "Reply YES to confirm", "appt_reminder", false);
        sms(b, PHONE, "INBOUND", "2026-11-11T10:05:00Z", "see you there", "appt_reminder", false);
        assertThat(service.queue(b).count()).isZero();

        sms(b, PHONE, "INBOUND", "2026-11-11T19:00:00Z", "actually can I come later?", "appt_reminder", false);
        assertThat(service.queue(b).count()).isEqualTo(1);
    }

    @Test
    void humanTextClearsQueue() {
        in("2026-11-11T18:00:00Z", "Do you have anything today?");
        sms(b, PHONE, "OUTBOUND", "2026-11-11T18:10:00Z", "Yes, at 3", null, false);
        assertThat(service.queue(b).count()).isZero();
    }

    @Test
    void crmCallAttemptReachedOutOrLostAfterTheQuestionClear() {
        for (String[] ev : new String[][]{{"CALL", null}, {"ATTEMPT", null}, {"REACHED_OUT", null}, {"STATUS", "LOST"}, {"STATUS", "SPAM"}}) {
            jdbc.update("DELETE FROM lead_events");
            jdbc.update("DELETE FROM lead_intake");
            jdbc.update("DELETE FROM sms_message");
            long lead = lead("CONTACTED", "2026-11-10T10:00:00Z");
            in("2026-11-11T18:00:00Z", "Is the price still $80?");
            assertThat(service.queue(b).count()).as("before " + ev[0]).isEqualTo(1);
            event(lead, ev[0], ev[1], "2026-11-11T18:30:00Z");
            assertThat(service.queue(b).count()).as(ev[0] + " " + ev[1]).isZero();
        }
    }

    @Test
    void crmEventBeforeTheQuestionDoesNotClear() {
        long lead = lead("LOST", "2026-11-01T10:00:00Z");
        event(lead, "STATUS", "LOST", "2026-11-05T10:00:00Z");
        in("2026-11-11T18:00:00Z", "Hi, are you still open on Sundays?");
        assertThat(service.queue(b).count()).isEqualTo(1);
    }

    @Test
    void waitsOlderThanSevenDaysDropOut() {
        in("2026-11-02T18:00:00Z", "Can I book?");
        in("2026-11-10T18:00:00Z", "ignored: same wait, the run started on the 2nd");
        assertThat(service.queue(b).count()).isZero();

        sms(b, "+15550002222", "INBOUND", "2026-11-05T18:00:00Z", "Can I book?", null, false);
        assertThat(service.queue(b).count()).isEqualTo(1);
    }

    @Test
    void otherBusinessIsNotMixedIn() {
        long other = businesses.save(Business.builder().name("Other").shortCode("o-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        sms(other, PHONE, "INBOUND", "2026-11-11T18:00:00Z", "Can I book?", null, false);
        assertThat(service.queue(b).count()).isZero();
        assertThat(service.queue(other).count()).isEqualTo(1);
    }

    @Test
    void waitPausesOffShift() {
        jdbc.update("DELETE FROM manager_time_entry");
        shift("2026-11-11T08:00:00Z", "2026-11-11T10:00:00Z");
        in("2026-11-11T09:50:00Z", "Can I book?");
        var q = service.queue(b);
        assertThat(q.count()).isEqualTo(1);
        assertThat(q.items().get(0).waitSeconds()).isEqualTo(600);
    }

    private long leadFor(String phone, String status, String created, String channel, String customer) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, created_at, square_customer_id, legacy)
                VALUES (?, ?, 'test', ?, 'CREATED', ?, ?, ?, ?) RETURNING id
                """, Long.class, b, channel, phone, status, Timestamp.from(Instant.parse(created)), customer, "OUTDATED".equals(status));
    }

    private void booking(String customer, String createdAt) {
        jdbc.update("INSERT INTO square_booking (business_id, square_booking_id, square_customer_id, status, start_at, created_at) "
                + "VALUES (?, ?, ?, 'ACCEPTED', ?, ?)", b, "bk-" + System.nanoTime(), customer,
                Timestamp.from(Instant.parse("2026-11-25T17:00:00Z")), Timestamp.from(Instant.parse(createdAt)));
    }

    @Test
    void devynCaseClientWhoBookedAfterTheTextIsNotRaisedDespiteDuplicateNewLead() {
        leadFor(PHONE, "BOOKED", "2026-11-11T15:00:00Z", "SMS", "CUST1");
        leadFor(PHONE, "NEW", "2026-11-11T19:00:00Z", "Form", "CUST1");
        in("2026-11-11T18:20:00Z", "What do you need from me to book the appointment");
        booking("CUST1", "2026-11-11T18:40:00Z");

        assertThat(service.queue(b).count()).isZero();
        assertThat(metrics.leaderboard(b, "today").unansweredNow()).isZero();
    }

    @Test
    void bookingBeforeTheTextDoesNotClearAQuestion() {
        leadFor(PHONE, "BOOKED", "2026-11-01T15:00:00Z", "SMS", "CUST1");
        booking("CUST1", "2026-11-05T18:40:00Z");
        in("2026-11-11T18:20:00Z", "Can I move my appointment?");
        assertThat(service.queue(b).count()).isEqualTo(1);
    }

    @Test
    void rebeccaCaseSpamPersonNeverAppearsAnywhere() {
        leadFor(PHONE, "SPAM", "2026-11-04T05:16:00Z", "Academy", null);
        leadFor(PHONE, "NEW", "2026-11-04T05:16:30Z", "Form", null);
        leadFor(PHONE, "NEW", "2026-11-11T12:13:00Z", "Academy", null);
        in("2026-11-11T18:00:00Z", "Hello, can you call me back?");

        assertThat(service.queue(b).count()).isZero();
        assertThat(metrics.leaderboard(b, "today").unansweredNow()).isZero();
        assertThat(metrics.shiftSummary(b, anna.getId(), anna.getUsername()).unansweredCount()).isZero();
    }

    @Test
    void failedOutboundIsNotAnAnswer() {
        in("2026-11-11T18:00:00Z", "Can I book?");
        jdbc.update("INSERT INTO sms_message (business_id, direction, phone_number, body, status, created_at) VALUES (?, 'OUTBOUND', ?, 'Sure', 'FAILED', ?)",
                b, PHONE, Timestamp.from(Instant.parse("2026-11-11T18:05:00Z")));
        assertThat(service.queue(b).count()).isEqualTo(1);
    }

    @Test
    void robynCaseNumberReplyIsNotRaisedEvenOutsideTheDayWindowOrWithoutKey() {
        sms(b, PHONE, "OUTBOUND", "2026-11-10T10:00:00Z", "How did we do? Reply 1-5", "checkout_review_request", false);
        sms(b, PHONE, "INBOUND", "2026-11-11T18:00:00Z", "5", null, false);
        sms(b, "+15550002222", "OUTBOUND", "2026-11-11T17:00:00Z", "How did we do? Reply 1-5", "checkout_review_request", false);
        sms(b, "+15550002222", "INBOUND", "2026-11-11T18:00:00Z", "5!", "checkout_review_request", false);
        assertThat(service.queue(b).count()).isZero();

        sms(b, "+15550003333", "INBOUND", "2026-11-11T18:00:00Z", "5", null, false);
        assertThat(service.queue(b).count()).as("a number from a chat we never texted automatically is a real message").isEqualTo(1);
    }

    @Test
    void maryamCaseComplaintInsideARatingReplyIsRaisedAndViewingDoesNotClearIt() {
        leadFor(PHONE, "OUTDATED", "2026-07-07T17:31:00Z", "Square", null);
        sms(b, PHONE, "OUTBOUND", "2026-11-11T17:00:00Z", "How did we do? Reply 1-5", "checkout_review_request", false);
        sms(b, PHONE, "INBOUND", "2026-11-11T17:30:00Z",
                "I love my nails, however I started at 9:47 and did not finish until 12:51, the delay messed up my plans", "checkout_review_request", true);
        sms(b, PHONE, "OUTBOUND", "2026-11-11T17:30:01Z", "Yay, so happy to hear that!", "checkout_review_request", false);

        var q = service.queue(b);
        assertThat(q.count()).isEqualTo(1);
        assertThat(q.items().get(0).ask()).isTrue();
        assertThat(q.items().get(0).leadStatus()).isEqualTo("OUTDATED");
    }

    @Test
    void queueLinksToThePersonsFirstLeadSoTheCardOpens() {
        long first = leadFor(PHONE, "CONTACTED", "2026-11-04T21:00:00Z", "Academy", null);
        leadFor(PHONE, "NEW", "2026-11-05T05:00:00Z", "Academy", null);
        leadFor(PHONE, "NEW", "2026-11-05T05:30:00Z", "Form", null);
        in("2026-11-11T18:00:00Z", "Can I book?");
        var it = service.queue(b).items().get(0);
        assertThat(it.leadId()).isEqualTo(first);
        assertThat(it.leadStatus()).isEqualTo("CONTACTED");
    }

    // ---- escalation ----

    private TelegramNotificationService telegram;
    private NeedsReplyEscalation escalation;

    private void escalationSetup() {
        telegram = mock(TelegramNotificationService.class);
        when(telegram.sendHtmlAlert(anyLong(), any(), any(), any())).thenReturn(true);
        when(telegram.sendOwnerHtmlAlert(anyLong(), any())).thenReturn(true);
        jdbc.update("UPDATE needs_reply_escalation_start SET started_at = '2026-01-01T00:00:00Z'");
        escalation = new NeedsReplyEscalation(service, telegram, jdbc, "https://x.test", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void managerAlertedAfter15MinutesOnceAndOwnerAfter30() {
        escalationSetup();
        in("2026-11-11T19:50:00Z", "Can I book?");
        escalation.process(b);
        verify(telegram, never()).sendHtmlAlert(anyLong(), any(), any(), any());

        jdbc.update("DELETE FROM sms_message");
        in("2026-11-11T19:40:00Z", "Can I book?");
        escalation.process(b);
        escalation.process(b);
        verify(telegram, times(1)).sendHtmlAlert(eq(b), contains("···1111"), any(), any());
        verify(telegram, never()).sendOwnerHtmlAlert(anyLong(), any());

        jdbc.update("DELETE FROM sms_message");
        in("2026-11-11T19:20:00Z", "Can I book?");
        escalation.process(b);
        escalation.process(b);
        verify(telegram, times(2)).sendHtmlAlert(eq(b), any(), any(), any());
        verify(telegram, times(1)).sendOwnerHtmlAlert(eq(b), any());
    }

    @Test
    void nothingFiresOffShiftAndBacklogBeforeGoLiveIsNotAlerted() {
        escalationSetup();
        jdbc.update("DELETE FROM manager_time_entry");
        shift("2026-11-11T08:00:00Z", "2026-11-11T09:00:00Z");
        in("2026-11-11T08:55:00Z", "Can I book?");
        escalation.process(b);
        verify(telegram, never()).sendHtmlAlert(anyLong(), any(), any(), any());
        verify(telegram, never()).sendOwnerHtmlAlert(anyLong(), any());

        jdbc.update("DELETE FROM manager_time_entry");
        shift("2026-11-11T16:00:00Z", null);
        jdbc.update("UPDATE needs_reply_escalation_start SET started_at = '2026-11-11T19:55:00Z'");
        jdbc.update("DELETE FROM sms_message");
        in("2026-11-11T19:00:00Z", "Can I book?");
        escalation.process(b);
        verify(telegram, never()).sendHtmlAlert(anyLong(), any(), any(), any());
    }

    @Test
    void shiftStartDigestSentOncePerShift() {
        escalationSetup();
        jdbc.update("DELETE FROM manager_time_entry");
        shift("2026-11-11T19:55:00Z", null);
        jdbc.update("UPDATE needs_reply_escalation_start SET started_at = '2026-11-11T19:56:00Z'");
        sms(b, PHONE, "INBOUND", "2026-11-11T07:00:00Z", "Can I book?", null, false);
        escalation.process(b);
        escalation.process(b);
        verify(telegram, times(1)).sendHtmlAlert(eq(b), contains("Начало смены: ждут ответа чатов 1"), any(), any());
    }

    @Test
    void failedSendIsRetried() {
        escalationSetup();
        when(telegram.sendHtmlAlert(anyLong(), any(), any(), any())).thenReturn(false, true);
        in("2026-11-11T19:40:00Z", "Can I book?");
        escalation.process(b);
        escalation.process(b);
        verify(telegram, times(2)).sendHtmlAlert(eq(b), any(), any(), any());
    }
}
