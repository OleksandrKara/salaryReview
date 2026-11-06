package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.domain.Business;
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
        service = new LeadFollowUpService(jdbc, null, null, null, null, intake, catalog, null, null, null, null, null, "https://x");
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
}
