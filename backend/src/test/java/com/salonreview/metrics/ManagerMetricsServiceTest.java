package com.salonreview.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.Role;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessRepository;
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
class ManagerMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T20:00:00Z");

    @Autowired private BusinessRepository businesses;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    private ManagerMetricsService service;
    private long b;
    private AppUser anna;
    private AppUser bob;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("Metrics").shortCode("mm-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        anna = users.save(AppUser.builder().businessId(b).username("anna-" + System.nanoTime()).passwordHash("x").role(Role.MANAGER).active(true).build());
        bob = users.save(AppUser.builder().businessId(b).username("bob-" + System.nanoTime()).passwordHash("x").role(Role.MANAGER).active(true).build());
        LeadFollowUpService leads = mock(LeadFollowUpService.class);
        when(leads.listFor(b, "NEW")).thenReturn(List.of());
        service = new ManagerMetricsService(jdbc, users, leads, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void shift(AppUser u, String start, String end) {
        jdbc.update("INSERT INTO manager_time_entry (user_id, work_date, start_at, end_at) VALUES (?, ?::date, ?, ?)",
                u.getId(), start.substring(0, 10), Timestamp.from(Instant.parse(start)), end == null ? null : Timestamp.from(Instant.parse(end)));
    }

    private void sms(String dir, String at, String body, Long sender, String automationKey) {
        jdbc.update("""
                INSERT INTO sms_message (business_id, direction, phone_number, body, status, created_at, sent_by_user_id, automation_key)
                VALUES (?, ?, '+15550001111', ?, ?, ?, ?, ?)
                """, b, dir, body, "INBOUND".equals(dir) ? "RECEIVED" : "SENT", Timestamp.from(Instant.parse(at)), sender, automationKey);
    }

    @Test
    void leaderboardRanksManagerWithDirectReply() {
        shift(anna, "2026-11-11T16:00:00Z", null);
        sms("INBOUND", "2026-11-11T18:00:00Z", "hi, do you have a slot?", null, null);
        sms("OUTBOUND", "2026-11-11T18:03:00Z", "Yes!", anna.getId(), null);
        sms("OUTBOUND", "2026-11-11T18:04:00Z", "automated reminder", null, "appt_reminder");

        var lb = service.leaderboard(b, "today");

        var row = lb.managers().stream().filter(r -> r.userId() == anna.getId()).findFirst().orElseThrow();
        assertThat(row.replies()).isEqualTo(1);
        assertThat(row.medianSeconds()).isEqualTo(180);
        assertThat(lb.managers().stream().anyMatch(r -> r.userId() == bob.getId())).as("manager with no data is hidden").isFalse();
        assertThat(lb.unansweredNow()).isZero();
    }

    @Test
    void shiftSummaryCountsUnansweredAndEmptyState() {
        var empty = service.shiftSummary(b, bob.getId(), bob.getUsername());
        assertThat(empty.onShift()).isFalse();
        assertThat(empty.shiftStart()).isNull();

        shift(bob, "2026-11-11T17:00:00Z", null);
        sms("INBOUND", "2026-11-11T19:30:00Z", "are you open?", null, null);

        var s = service.shiftSummary(b, bob.getId(), bob.getUsername());
        assertThat(s.onShift()).isTrue();
        assertThat(s.unansweredCount()).isEqualTo(1);
        assertThat(s.oldestUnansweredSeconds()).isEqualTo(1800);
        assertThat(s.waiting()).hasSize(1);
        assertThat(s.waiting().get(0).seconds()).isEqualTo(1800);
    }

    @Test
    void optOutAndThanksAreNotWaiting() {
        shift(bob, "2026-11-11T17:00:00Z", null);
        sms("INBOUND", "2026-11-11T19:30:00Z", "Too far ...sorry", null, null);
        sms("INBOUND", "2026-11-11T19:31:00Z", "STOP", null, null);
        var s = service.shiftSummary(b, bob.getId(), bob.getUsername());
        assertThat(s.unansweredCount()).isZero();
        assertThat(s.waiting()).isEmpty();
    }

    @Test
    void shiftSummaryShowsWeekRankAgainstTeam() {
        shift(anna, "2026-11-11T16:00:00Z", null);
        shift(bob, "2026-11-11T16:00:00Z", null);
        sms("INBOUND", "2026-11-11T18:00:00Z", "q1", null, null);
        sms("OUTBOUND", "2026-11-11T18:03:00Z", "a1", anna.getId(), null);
        sms("INBOUND", "2026-11-11T19:00:00Z", "q2", null, null);
        sms("OUTBOUND", "2026-11-11T19:10:00Z", "a2", bob.getId(), null);

        var s = service.shiftSummary(b, bob.getId(), bob.getUsername());
        for (String period : List.of("7d", "30d", "all")) {
            var c = s.comparisons().stream().filter(x -> x.period().equals(period)).findFirst().orElseThrow();
            assertThat(c.myMedianSeconds()).as(period).isEqualTo(600);
            assertThat(c.rank()).as(period).isEqualTo(2);
            assertThat(c.ranked()).as(period).isEqualTo(2);
            assertThat(c.teamMedianSeconds()).as(period).isEqualTo(390);
        }

        var board = service.leaderboard(b, "7d");
        var bobRow = board.managers().stream().filter(r -> r.userId() == bob.getId()).findFirst().orElseThrow();
        assertThat(bobRow.slowest()).hasSize(1);
        assertThat(bobRow.slowest().get(0).seconds()).isEqualTo(600);
        assertThat(bobRow.slowest().get(0).phoneLast4()).hasSizeLessThanOrEqualTo(4);
    }

    @Test
    void allTimeStartsAtFirstShiftAndRanksOnlyManagersWithData() {
        shift(anna, "2026-09-01T16:00:00Z", "2026-09-01T20:00:00Z");
        sms("INBOUND", "2026-09-01T18:00:00Z", "old q", null, null);
        sms("OUTBOUND", "2026-09-01T18:02:00Z", "old a", anna.getId(), null);

        var all = service.leaderboard(b, "all");
        assertThat(all.managers()).extracting(ManagerMetricsService.ManagerRow::userId).containsExactly(anna.getId());
        assertThat(all.managers().get(0).replies()).isEqualTo(1);
        assertThat(service.leaderboard(b, "30d").managers()).isEmpty();

        var s = service.shiftSummary(b, bob.getId(), bob.getUsername());
        var c = s.comparisons().stream().filter(x -> x.period().equals("all")).findFirst().orElseThrow();
        assertThat(c.myMedianSeconds()).isNull();
        assertThat(c.rank()).isNull();
        assertThat(c.ranked()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownPeriod() {
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.leaderboard(b, "year"));
    }
}
