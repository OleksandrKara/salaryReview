package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A website SMS or a new consultation booking returns the lead to NEW from every status except SPAM; real Postgres, rolled back. */
@SpringBootTest
@Transactional
class LeadReopenOnActivityTest {

    private static final String CONSULT = "CONSULT_VAR";

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    private LeadIntakeService intake;
    private long b;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("Reopen").shortCode("ro-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        ConsultationCatalog catalog = mock(ConsultationCatalog.class);
        when(catalog.ids(b)).thenReturn(Set.of(CONSULT));
        SquareClient square = mock(SquareClient.class);
        SquareClientProvider squares = mock(SquareClientProvider.class);
        when(squares.forBusiness(b)).thenReturn(square);
        when(square.findCustomersExact(anyString(), any())).thenAnswer(inv ->
                java.util.List.of(new SquareClient.Customer("CUST-" + inv.getArgument(0), "T", "T", "2020-01-01T00:00:00Z", inv.getArgument(0), null, null)));
        intake = new LeadIntakeService(jdbc, squares, catalog, String.valueOf(b));
    }

    private long lead(String phone, String status, String customer) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, square_customer_id, created_at, legacy)
                VALUES (?, 'Form', 'Other', ?, 'MATCHED', ?, ?, now() - interval '5 days', ?) RETURNING id
                """, Long.class, b, phone, status, customer, "OUTDATED".equals(status));
    }

    private void booking(String id, String customer, String variation, String createdAgo, String startsIn) {
        jdbc.update("""
                INSERT INTO square_booking (business_id, square_booking_id, square_customer_id, status, start_at, created_at, appointment_segments_json)
                VALUES (?, ?, ?, 'ACCEPTED', now() + ?::interval, now() - ?::interval, ?::jsonb)
                """, b, id, customer, startsIn, createdAgo, "[{\"serviceVariationId\":\"" + variation + "\"}]");
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM lead_intake WHERE id = ?", String.class, id);
    }

    private LeadIntakeService.Result capture(String channel, String phone) {
        return intake.capture(new LeadIntakeService.Lead(b, channel, "Google (organic)", phone, null, null, null, channel + "-" + System.nanoTime()));
    }

    @Test
    void websiteSmsFromAKnownPersonReopensEveryStatusButSpam() {
        String[] statuses = {"CONTACTED", "BOOKED", "LOST", "OUTDATED", "SPAM"};
        long[] ids = new long[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            String phone = "+161955510" + String.format("%02d", i);
            ids[i] = lead(phone, statuses[i], null);
            assertThat(capture("SMS", phone).outcome()).isEqualTo("MATCHED");
        }
        for (int i = 0; i < 4; i++) assertThat(status(ids[i])).isEqualTo("NEW");
        assertThat(status(ids[4])).isEqualTo("SPAM");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lead_events WHERE lead_id = ? AND text = 'NEW'", Integer.class, ids[0])).isEqualTo(1);
    }

    @Test
    void plainTextNeverReopensALead() {
        long id = lead("+16195551020", "CONTACTED", null);
        assertThat(capture("Text", "+16195551020").outcome()).isEqualTo("SKIPPED_PLAIN_TEXT");
        assertThat(status(id)).isEqualTo("CONTACTED");
    }

    @Test
    void websiteSmsKeepsALeadWithAFuturePaidBooking() {
        long id = lead("+16195551006", "BOOKED", "C6");
        booking("BK6", "C6", "PROC", "1 day", "3 days");
        capture("SMS", "+16195551006");
        assertThat(status(id)).isEqualTo("BOOKED");
    }

    @Test
    void newConsultationBookingReopensButOldOrProcedureBookingDoesNot() {
        long fresh = lead("+16195551007", "CONTACTED", "C7");
        long old = lead("+16195551008", "LOST", "C8");
        long proc = lead("+16195551009", "LOST", "C9");
        booking("BK7", "C7", CONSULT, "1 minute", "2 days");
        booking("BK8", "C8", CONSULT, "3 days", "2 days");
        booking("BK9", "C9", "PROC", "1 minute", "2 days");
        intake.reopenOnConsultationBooking(b, "BK7");
        intake.reopenOnConsultationBooking(b, "BK8");
        intake.reopenOnConsultationBooking(b, "BK9");
        assertThat(status(fresh)).isEqualTo("NEW");
        assertThat(status(old)).isEqualTo("LOST");
        assertThat(status(proc)).isEqualTo("LOST");
    }
}
