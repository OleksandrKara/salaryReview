package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.domain.Business;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.BusinessRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Clients who book or rebook must land in BOOKED, never linger in NEW; runs on a real Postgres and rolls back. */
@SpringBootTest
@Transactional
class LeadAutoBookTest {

    private static final String CONSULT = "CONSULT_VAR";

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    private LeadFollowUpService service;
    private long b;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("AutoBook").shortCode("ab-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        LeadIntakeService intake = mock(LeadIntakeService.class);
        when(intake.enabledBusinesses()).thenReturn(Set.of(b));
        ConsultationCatalog catalog = mock(ConsultationCatalog.class);
        when(catalog.ids(b)).thenReturn(Set.of(CONSULT));
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(b);
        service = new LeadFollowUpService(jdbc, ctx, null, null, null, intake, catalog, null, null, null, null, null, "https://x");
    }

    private long lead(String phone, String status, String customer, String createdAgo, boolean legacy) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, square_customer_id, created_at, legacy)
                VALUES (?, 'Form', 'Other', ?, 'MATCHED', ?, ?, now() - ?::interval, ?) RETURNING id
                """, Long.class, b, phone, status, customer, createdAgo, legacy);
    }

    private void booking(String customer, String status, String variation, String createdAgo, String startsIn) {
        jdbc.update("""
                INSERT INTO square_booking (business_id, square_booking_id, square_customer_id, status, start_at, created_at, appointment_segments_json)
                VALUES (?, ?, ?, ?, now() + ?::interval, now() - ?::interval, ?::jsonb)
                """, b, "BK" + System.nanoTime(), customer, status, startsIn, createdAgo,
                "[{\"serviceVariationId\":\"" + variation + "\"}]");
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM lead_intake WHERE id = ?", String.class, id);
    }

    @Test
    void newLeadWithFreshProcedureBookingBecomesBooked() {
        long id = lead("+16195550001", "NEW", "C1", "10 minutes", false);
        booking("C1", "ACCEPTED", "PROC", "2 minutes", "3 days");
        assertThat(service.autoBook()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lead_events WHERE lead_id = ? AND text = 'BOOKED'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void rebookOfLostOrContactedLeadMovesToBookedButOldFutureVisitDoesNotReviveLost() {
        long lost = lead("+16195550002", "LOST", "C2", "5 days", false);
        long contacted = lead("+16195550003", "CONTACTED", "C3", "5 days", false);
        long oldLost = lead("+16195550004", "LOST", "C4", "5 days", false);
        booking("C2", "ACCEPTED", "PROC", "1 hour", "10 days");
        booking("C3", "ACCEPTED", "PROC", "1 hour", "10 days");
        booking("C4", "ACCEPTED", "PROC", "40 days", "10 days");
        service.autoBook();
        assertThat(status(lost)).isEqualTo("BOOKED");
        assertThat(status(contacted)).isEqualTo("BOOKED");
        assertThat(status(oldLost)).isEqualTo("LOST");
    }

    @Test
    void consultationsAndCancelledBookingsDoNotBook() {
        long consult = lead("+16195550005", "NEW", "C5", "1 hour", false);
        long cancelled = lead("+16195550006", "NEW", "C6", "1 hour", false);
        booking("C5", "ACCEPTED", CONSULT, "1 minute", "2 days");
        booking("C6", "CANCELLED_BY_CUSTOMER", "PROC", "1 minute", "2 days");
        service.autoBook();
        assertThat(status(consult)).isEqualTo("NEW");
        assertThat(status(cancelled)).isEqualTo("NEW");
    }

    @Test
    void archivedClientWhoBooksNowLeavesArchiveButStaleBookingKeepsThemThere() {
        long fresh = lead("+16195550007", "OUTDATED", "C7", "900 days", true);
        long stale = lead("+16195550008", "OUTDATED", "C8", "900 days", true);
        booking("C7", "ACCEPTED", "PROC", "30 minutes", "6 days");
        booking("C8", "ACCEPTED", "PROC", "200 days", "6 days");
        service.autoBook();
        assertThat(status(fresh)).isEqualTo("BOOKED");
        assertThat(status(stale)).isEqualTo("OUTDATED");
    }

    @Test
    void bookingWebhookBooksOnlyThatCustomerImmediately() {
        long mine = lead("+16195550009", "NEW", "C9", "10 minutes", false);
        long other = lead("+16195550010", "NEW", "C10", "10 minutes", false);
        booking("C9", "ACCEPTED", "PROC", "1 minute", "4 days");
        booking("C10", "ACCEPTED", "PROC", "1 minute", "4 days");
        assertThat(service.autoBookCustomer(b, "C9")).isEqualTo(1);
        assertThat(status(mine)).isEqualTo("BOOKED");
        assertThat(status(other)).isEqualTo("NEW");
        assertThat(service.autoBookCustomer(b, null)).isZero();
    }

    @Test
    void restoredArchiveLeadWithAnyVisitBooksButReturningTouchKeepsItNew() {
        long visited = lead("+16195550011", "NEW", "C11", "20 days", true);
        long never = lead("+16195550012", "NEW", "C12", "20 days", true);
        long touched = lead("+16195550013", "NEW", "C13", "20 days", true);
        booking("C11", "ACCEPTED", "PROC", "19 days", "-18 days");
        booking("C12", "CANCELLED_BY_CUSTOMER", "PROC", "19 days", "-18 days");
        booking("C13", "ACCEPTED", "PROC", "19 days", "-18 days");
        jdbc.update("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, square_customer_id, created_at)
                VALUES (?, 'Call', 'Direct', '+16195550013', 'MATCHED', 'NEW', 'C13', now() - interval '1 day')
                """, b);
        service.autoBook();
        assertThat(status(visited)).isEqualTo("BOOKED");
        assertThat(status(never)).isEqualTo("NEW");
        assertThat(status(touched)).isEqualTo("NEW");
    }

    @Test
    void lastVisitIsTrackedOnlyForNailsAndSkipsClientsWithAnUpcomingVisit() {
        long old = lead("+16195550031", "NEW", "C31", "100 days", false);
        long upcoming = lead("+16195550032", "NEW", "C32", "100 days", false);
        long consultOnly = lead("+16195550033", "NEW", "C33", "100 days", false);
        booking("C31", "ACCEPTED", "PROC", "100 days", "-90 days");
        booking("C32", "ACCEPTED", "PROC", "100 days", "-90 days");
        booking("C32", "ACCEPTED", "PROC", "1 day", "5 days");
        booking("C33", "ACCEPTED", CONSULT, "100 days", "-90 days");
        assertThat(service.listFor(b, null)).extracting(LeadFollowUpService.LeadView::lastVisitAt).containsOnlyNulls();
        LeadFollowUpService nails = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(true).when(nails).tracksLastVisit(b);
        var rows = nails.listFor(b, null).stream().collect(java.util.stream.Collectors.toMap(LeadFollowUpService.LeadView::id, v -> java.util.Optional.ofNullable(v.lastVisitAt())));
        assertThat(rows.get(old)).isPresent();
        assertThat(rows.get(upcoming)).isEmpty();
        assertThat(rows.get(consultOnly)).isEmpty();
    }

    @Test
    void lapsedClientsComeBackAsCallbacksOnceAndOnlyANewBookingBooksThemAgain() {
        long lapsed = lead("+16195550041", "BOOKED", "C41", "100 days", false);
        long recent = lead("+16195550042", "BOOKED", "C42", "100 days", false);
        long upcoming = lead("+16195550043", "BOOKED", "C43", "100 days", false);
        booking("C41", "ACCEPTED", "PROC", "99 days", "-40 days");
        booking("C42", "ACCEPTED", "PROC", "99 days", "-10 days");
        booking("C43", "ACCEPTED", "PROC", "99 days", "-40 days");
        booking("C43", "ACCEPTED", "PROC", "1 day", "5 days");
        LeadFollowUpService nails = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(true).when(nails).tracksLastVisit(b);

        assertThat(nails.reopenLapsed(b, java.time.Instant.now())).isEqualTo(1);
        assertThat(status(lapsed)).isEqualTo("CONTACTED");
        assertThat(status(recent)).isEqualTo("BOOKED");
        assertThat(status(upcoming)).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT callback_at IS NOT NULL AND callback_reminded FROM lead_intake WHERE id = ?", Boolean.class, lapsed)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lead_events WHERE lead_id = ? AND type = 'LAPSED'", Integer.class, lapsed)).isEqualTo(1);

        nails.autoBook();
        assertThat(status(lapsed)).isEqualTo("CONTACTED");
        assertThat(nails.reopenLapsed(b, java.time.Instant.now())).isZero();

        booking("C41", "ACCEPTED", "PROC", "0 seconds", "3 days");
        nails.autoBook();
        assertThat(status(lapsed)).isEqualTo("BOOKED");
    }

    @Test
    void reachedOutClearsTheReminderResetsAttemptsAndSchedulesTheNextCheckIn() {
        long fresh = lead("+16195550051", "NEW", null, "3 days", false);
        service.recordAttempt(fresh, "CALL", "tester");
        service.recordAttempt(fresh, "CALL", "tester");
        jdbc.update("UPDATE lead_intake SET callback_at = now() - interval '1 hour' WHERE id = ?", fresh);
        jdbc.update("UPDATE lead_events SET created_at = now() - interval '2 hours' WHERE lead_id = ? AND type = 'ATTEMPT'", fresh);
        assertThat(service.find(fresh).attempts()).isEqualTo(2);

        service.reachedOut(fresh, "tester");
        var after = service.find(fresh);
        assertThat(after.status()).isEqualTo("CONTACTED");
        assertThat(after.attempts()).isZero();
        assertThat(after.closeReady()).isFalse();
        assertThat(java.time.Instant.parse(after.callbackAt())).isBetween(
                java.time.Instant.now().plus(java.time.Duration.ofDays(6)), java.time.Instant.now().plus(java.time.Duration.ofDays(8)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lead_events WHERE lead_id = ? AND type = 'REACHED_OUT' AND text = '7'", Integer.class, fresh)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT callback_reminded FROM lead_intake WHERE id = ?", Boolean.class, fresh)).isFalse();

        jdbc.update("UPDATE lead_events SET created_at = now() - interval '1 minute' WHERE lead_id = ? AND type = 'REACHED_OUT'", fresh);
        service.recordAttempt(fresh, "CALL", "tester");
        assertThat(service.find(fresh).attempts()).isEqualTo(1);
    }

    @Test
    void reachedOutPicksAMonthForLapsedClientsAndRefusesClosedLeads() {
        java.time.Instant now = java.time.Instant.now();
        assertThat(LeadFollowUpService.reachedOutDays(now.minus(java.time.Duration.ofDays(45)).toString(), now)).isEqualTo(30);
        assertThat(LeadFollowUpService.reachedOutDays(now.minus(java.time.Duration.ofDays(10)).toString(), now)).isEqualTo(7);
        assertThat(LeadFollowUpService.reachedOutDays(null, now)).isEqualTo(7);

        long lost = lead("+16195550052", "LOST", null, "3 days", false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reachedOut(lost, "tester"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(status(lost)).isEqualTo("LOST");
    }

    @Test
    void statusChangesNeverMakeALeadLookFresher() {
        long old = lead("+16195550021", "NEW", "C21", "30 days", false);
        long recent = lead("+16195550022", "CONTACTED", "C22", "2 days", false);
        jdbc.update("UPDATE lead_intake SET status = 'CONTACTED', updated_by = 'x', updated_at = now() WHERE id = ?", old);
        var rows = service.listFor(b, "CONTACTED");
        assertThat(rows).extracting(LeadFollowUpService.LeadView::id).containsExactly(recent, old);
    }

    @Test
    void sameEmailWithoutPhoneJoinsTheRowThatHasOne() {
        jdbc.update("INSERT INTO lead_intake (business_id, channel, source, phone_number, email, outcome, created_at) VALUES (?, 'Form', 'Other', '+995591418548', 'N@Example.com', 'MATCHED', now() - interval '2 days')", b);
        jdbc.update("INSERT INTO lead_intake (business_id, channel, source, email, outcome, created_at) VALUES (?, 'Academy', 'AI search', 'n@example.com', 'MATCHED', now() - interval '1 day')", b);
        jdbc.update("INSERT INTO lead_intake (business_id, channel, source, email, outcome) VALUES (?, 'Form', 'Other', 'other@example.com', 'MATCHED')", b);
        var leads = service.listFor(b, "NEW");
        assertThat(leads).hasSize(2);
        assertThat(leads).filteredOn(l -> l.touches() == 2).hasSize(1).allMatch(LeadFollowUpService.LeadView::academy);
    }

    @Test
    void appointmentNotesReachTheLeadDetail() {
        long id = lead("+16195550077", "CONTACTED", "C77", "3 days", false);
        jdbc.update("""
                INSERT INTO square_booking (business_id, square_booking_id, square_customer_id, status, start_at, created_at, seller_note, customer_note, appointment_segments_json)
                VALUES (?, 'BKN1', 'C77', 'ACCEPTED', now() + interval '2 days', now(), '  Wants lip blush, allergic to latex ', 'Please call after 5pm', '[]'::jsonb),
                       (?, 'BKN2', 'C77', 'ACCEPTED', now() + interval '3 days', now(), '   ', NULL, '[]'::jsonb)
                """, b, b);
        var appts = service.appointments(service.listFor(b, null).stream().filter(v -> v.id() == id).findFirst().orElseThrow());
        var withNotes = appts.stream().filter(a -> a.id().equals("BKN1")).findFirst().orElseThrow();
        var blank = appts.stream().filter(a -> a.id().equals("BKN2")).findFirst().orElseThrow();
        assertThat(withNotes.sellerNote()).isEqualTo("Wants lip blush, allergic to latex");
        assertThat(withNotes.customerNote()).isEqualTo("Please call after 5pm");
        assertThat(blank.sellerNote()).isNull();
        assertThat(blank.customerNote()).isNull();
    }

    @Test
    void mailchimpActivityShowsDirectCampaignsWithOpensAndClicks() throws Exception {
        long id = lead("+16195550088", "CONTACTED", "C88", "3 days", false);
        jdbc.update("UPDATE lead_intake SET email = 'Pat@Example.com' WHERE id = ?", id);
        jdbc.update("INSERT INTO mailchimp_sent_email (business_id, email, subject, campaign_title, campaign_id, sent_at) VALUES (?, 'pat@example.com', 's', 'Known one', '0a0b0c0d0e', now() - interval '2 days')", b);
        var config = new com.salonreview.domain.MailchimpConfig();
        config.setBusinessId(b); config.setApiKey("k-us1"); config.setAudienceId("aud"); config.setFromName("n"); config.setFromEmail("f@x.com"); config.setReplyToEmail("r@x.com");
        var configs = mock(com.salonreview.repo.MailchimpConfigRepository.class);
        when(configs.findByBusinessId(b)).thenReturn(java.util.Optional.of(config));
        var mc = mock(com.salonreview.sms.MailchimpClient.class);
        var t0 = java.time.Instant.parse("2026-11-01T10:00:00Z");
        when(mc.fetchMemberActivity(config, "pat@example.com")).thenReturn(java.util.List.of(
                new com.salonreview.sms.MailchimpClient.MemberEvent("sent", "ab12cd34ef", "Black Friday", t0),
                new com.salonreview.sms.MailchimpClient.MemberEvent("open", "ab12cd34ef", "Black Friday", t0.plusSeconds(600)),
                new com.salonreview.sms.MailchimpClient.MemberEvent("open", "ab12cd34ef", "Black Friday", t0.plusSeconds(60)),
                new com.salonreview.sms.MailchimpClient.MemberEvent("click", "ab12cd34ef", "Black Friday", t0.plusSeconds(900)),
                new com.salonreview.sms.MailchimpClient.MemberEvent("sent", "0a0b0c0d0e", "Known one", t0),
                new com.salonreview.sms.MailchimpClient.MemberEvent("open", "0a0b0c0d0e", "Known one", t0.plusSeconds(30))));
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(b);
        var svc = new LeadFollowUpService(jdbc, ctx, null, null, null, mock(LeadIntakeService.class), mock(ConsultationCatalog.class), configs, mc, null, null, null, "https://x");
        var mails = svc.emails(svc.find(id));
        assertThat(mails).hasSize(2);
        var direct = mails.stream().filter(m -> m.kind().equals("Black Friday")).findFirst().orElseThrow();
        assertThat(direct.sentAt()).isEqualTo("2026-11-01T10:00:00Z");
        assertThat(direct.openedAt()).isEqualTo("2026-11-01T10:01:00Z");
        assertThat(direct.clickedAt()).isEqualTo("2026-11-01T10:15:00Z");
        var known = mails.stream().filter(m -> !m.kind().equals("Black Friday")).findFirst().orElseThrow();
        assertThat(known.openedAt()).isEqualTo("2026-11-01T10:00:30Z");
        assertThat(known.clickedAt()).isNull();
    }
}
