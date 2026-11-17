package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.marketing.LeadFollowUpService.LeadFilter;
import com.salonreview.repo.BusinessRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** CRM leads filters and sort: server-side, Los Angeles day boundaries, last-activity definition. */
@SpringBootTest
@Transactional
class LeadFilterSearchTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    private LeadFollowUpService service;
    private long b;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("Filters").shortCode("lf-" + System.nanoTime()).timezone("America/Los_Angeles").active(true).build()).getId();
        LeadIntakeService intake = mock(LeadIntakeService.class);
        when(intake.enabledBusinesses()).thenReturn(Set.of(b));
        ConsultationCatalog catalog = mock(ConsultationCatalog.class);
        when(catalog.ids(b)).thenReturn(Set.of("C"));
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(b);
        service = new LeadFollowUpService(jdbc, ctx, null, null, null, intake, catalog, null, null, null, null, null, "https://x");
    }

    private long lead(String phone, String source, String createdUtc, String status, String channel) {
        Long id = jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, traffic_source, phone_number, outcome, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?::timestamptz) RETURNING id
                """, Long.class, b, channel, source, source, phone, status, createdUtc);
        return id;
    }

    private long lead(String phone, String source, String createdUtc) {
        return lead(phone, source, createdUtc, "NEW", "Form");
    }

    private void event(long leadId, String type, String atUtc) {
        jdbc.update("INSERT INTO lead_events (lead_id, business_id, type, text, created_by, created_at) VALUES (?, ?, ?, 'x', 'tester', ?::timestamptz)", leadId, b, type, atUtc);
    }

    private LeadFilter f(List<String> sources, List<String> statuses, LocalDate cf, LocalDate ct, LocalDate af, LocalDate at, String sort, boolean asc) {
        return new LeadFilter(sources, statuses, cf, ct, af, at, sort, asc, false);
    }

    private List<String> phones(LeadFilter filter) {
        return service.search(filter, 0, 100).items().stream().map(i -> i.lead().phoneNumber()).toList();
    }

    @Test
    void createDateUsesLosAngelesDays() {
        lead("+16195550001", "Direct", "2026-11-17T07:30:00Z"); // Nov 16, 23:30 in LA
        lead("+16195550002", "Direct", "2026-11-17T08:30:00Z"); // Nov 17, 00:30 in LA
        lead("+16195550003", "Direct", "2026-11-18T07:59:00Z"); // Nov 17, 23:59 in LA
        lead("+16195550004", "Direct", "2026-11-18T08:01:00Z"); // Nov 18, 00:01 in LA
        var day = LocalDate.of(2026, 11, 17);
        assertThat(phones(f(null, null, day, day, null, null, "created", true))).containsExactly("+16195550002", "+16195550003");
        assertThat(phones(f(null, null, null, LocalDate.of(2026, 11, 16), null, null, "created", true))).containsExactly("+16195550001");
        assertThat(phones(f(null, null, LocalDate.of(2026, 11, 18), null, null, null, "created", true))).containsExactly("+16195550004");
    }

    @Test
    void createDateIsCorrectAcrossTheNovemberDstChange() {
        lead("+16195550011", "Direct", "2026-11-02T07:30:00Z"); // Nov 1, 23:30 PST (DST ended that morning)
        lead("+16195550012", "Direct", "2026-11-02T08:30:00Z"); // Nov 2, 00:30 PST
        var nov1 = LocalDate.of(2026, 11, 1);
        assertThat(phones(f(null, null, nov1, nov1, null, null, "created", true))).containsExactly("+16195550011");
        var nov2 = LocalDate.of(2026, 11, 2);
        assertThat(phones(f(null, null, nov2, nov2, null, null, "created", true))).containsExactly("+16195550012");
    }

    @Test
    void sourceAndStatusAreMultiSelectAndCombine() {
        lead("+16195550021", "Google Ads", "2026-11-10T18:00:00Z", "NEW", "Form");
        lead("+16195550022", "Instagram (organic)", "2026-11-10T19:00:00Z", "BOOKED", "Form");
        lead("+16195550023", "Yelp", "2026-11-10T20:00:00Z", "BOOKED", "Form");
        lead("+16195550024", "Google Ads", "2026-11-10T21:00:00Z", "LOST", "Form");
        assertThat(phones(f(List.of("Google Ads", "Yelp"), null, null, null, null, null, "created", true)))
                .containsExactly("+16195550021", "+16195550023", "+16195550024");
        assertThat(phones(f(List.of("Google Ads", "Yelp"), List.of("booked", "new"), null, null, null, null, "created", true)))
                .containsExactly("+16195550021", "+16195550023");
        assertThat(phones(f(null, List.of("NOPE"), null, null, null, null, "created", true))).hasSize(4);
    }

    @Test
    void sortByCreateDateBothDirections() {
        lead("+16195550031", "Direct", "2026-11-01T18:00:00Z");
        lead("+16195550032", "Direct", "2026-11-03T18:00:00Z");
        lead("+16195550033", "Direct", "2026-11-02T18:00:00Z");
        assertThat(phones(f(null, null, null, null, null, null, "created", true))).containsExactly("+16195550031", "+16195550033", "+16195550032");
        assertThat(phones(f(null, null, null, null, null, null, "created", false))).containsExactly("+16195550032", "+16195550033", "+16195550031");
    }

    @Test
    void lastActivityCountsNotesStatusChangesAndStaffTextsButNotAutomatedTexts() {
        long a = lead("+16195550041", "Direct", "2026-11-01T18:00:00Z");
        long c = lead("+16195550042", "Direct", "2026-11-02T18:00:00Z");
        long d = lead("+16195550043", "Direct", "2026-11-03T18:00:00Z");
        lead("+16195550044", "Direct", "2026-11-04T18:00:00Z");
        event(a, "STATUS", "2026-11-09T18:00:00Z");
        jdbc.update("INSERT INTO sms_message (business_id, direction, phone_number, body, status, sent_by_user_id, created_at) VALUES (?, 'OUTBOUND', '+16195550042', 'hi', 'sent', 7, '2026-11-10T18:00:00Z')", b);
        jdbc.update("INSERT INTO sms_message (business_id, direction, phone_number, body, status, created_at) VALUES (?, 'OUTBOUND', '+16195550043', 'auto', 'sent', '2026-11-20T18:00:00Z')", b);
        jdbc.update("INSERT INTO sms_message (business_id, direction, phone_number, body, status, created_at) VALUES (?, 'INBOUND', '+16195550044', 'reply', 'received', '2026-11-08T18:00:00Z')", b);
        var items = service.search(f(null, null, null, null, null, null, "activity", false), 0, 100).items();
        assertThat(items.stream().map(i -> i.lead().phoneNumber()).toList())
                .containsExactly("+16195550042", "+16195550041", "+16195550044", "+16195550043");
        assertThat(items.get(0).lastActivityAt()).startsWith("2026-11-10T18:00");
        assertThat(items.get(1).lastActivityAt()).startsWith("2026-11-09T18:00");
        assertThat(items.get(3).lastActivityAt()).startsWith("2026-11-03T18:00");
        var nov9 = LocalDate.of(2026, 11, 9);
        assertThat(phones(f(null, null, null, null, nov9, nov9, "activity", false))).containsExactly("+16195550041");
        assertThat(c).isNotEqualTo(d);
    }

    @Test
    void pagingReturnsTotalAndRespectsOffset() {
        for (int i = 0; i < 5; i++) lead("+1619555005" + i, "Direct", "2026-11-0" + (i + 1) + "T18:00:00Z");
        var first = service.search(f(null, null, null, null, null, null, "created", true), 0, 2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.items()).hasSize(2);
        var third = service.search(f(null, null, null, null, null, null, "created", true), 4, 2);
        assertThat(third.total()).isEqualTo(5);
        assertThat(third.items()).hasSize(1);
        var past = service.search(f(null, null, null, null, null, null, "created", true), 10, 2);
        assertThat(past.total()).isEqualTo(5);
        assertThat(past.items()).isEmpty();
    }

    @Test
    void academyOnlyUsersNeverSeeNonAcademyLeads() {
        lead("+16195550061", "Direct", "2026-11-01T18:00:00Z", "NEW", "Form");
        lead("+16195550062", "Google Ads", "2026-11-02T18:00:00Z", "NEW", "Academy");
        var academy = new LeadFilter(null, null, null, null, null, null, "created", true, true);
        assertThat(service.search(academy, 0, 10).items()).extracting(i -> i.lead().phoneNumber()).containsExactly("+16195550062");
        assertThat(service.filterOptions(academy).sources()).extracting(LeadFollowUpService.SourceCount::source).containsExactly("Google Ads");
    }

    @Test
    void optionsListSourcesAndStatusesFromTheData() {
        lead("+16195550071", "Google Ads", "2026-11-01T18:00:00Z", "NEW", "Form");
        lead("+16195550072", "Google Ads", "2026-11-02T18:00:00Z", "BOOKED", "Form");
        lead("+16195550073", "Yelp", "2026-11-03T18:00:00Z", "NEW", "Form");
        var all = service.filterOptions(f(null, null, null, null, null, null, null, false));
        assertThat(all.sources()).extracting(LeadFollowUpService.SourceCount::source).containsExactly("Google Ads", "Yelp");
        assertThat(all.sources().get(0).count()).isEqualTo(2);
        assertThat(all.statuses()).containsEntry("NEW", 2).containsEntry("BOOKED", 1).containsEntry("LOST", 0);
        var may = LocalDate.of(2026, 11, 3);
        assertThat(service.filterOptions(f(null, null, may, may, null, null, null, false)).sources()).extracting(LeadFollowUpService.SourceCount::source).containsExactly("Yelp");
    }

    @Test
    void defaultOrderKeepsNewLeadsFirst() {
        lead("+16195550081", "Direct", "2026-11-05T18:00:00Z", "CONTACTED", "Form");
        lead("+16195550082", "Direct", "2026-11-01T18:00:00Z", "NEW", "Form");
        assertThat(phones(f(null, null, null, null, null, null, null, false))).containsExactly("+16195550082", "+16195550081");
    }
}
