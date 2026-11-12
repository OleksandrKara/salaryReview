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
class ResponseOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T20:00:00Z");

    @Autowired private BusinessRepository businesses;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    private ResponseOverviewService service;
    private long b1, b2;
    private AppUser anna;
    private AppUser solo;

    @BeforeEach
    void setUp() {
        b1 = businesses.save(Business.builder().name("First").shortCode("o1-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        b2 = businesses.save(Business.builder().name("Second").shortCode("o2-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        anna = users.save(AppUser.builder().businessId(b1).username("anna-" + System.nanoTime()).passwordHash("x").role(Role.MANAGER).active(true).build());
        solo = users.save(AppUser.builder().businessId(b1).username("solo-" + System.nanoTime()).passwordHash("x").role(Role.MANAGER).active(true).build());
        for (long biz : new long[]{b1, b2}) member(anna, biz);
        member(solo, b1);
        LeadFollowUpService leads = mock(LeadFollowUpService.class);
        when(leads.listFor(b1, "NEW")).thenReturn(List.of());
        ManagerMetricsService texts = new ManagerMetricsService(jdbc, users, leads, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ResponseOverviewService(jdbc, texts, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void member(AppUser u, long biz) {
        jdbc.update("INSERT INTO business_membership (business_id, user_id, role) VALUES (?, ?, 'MANAGER')", biz, u.getId());
    }

    private long lead(long biz, String channel, String phone, String at, String status) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, created_at)
                VALUES (?, ?, 'Test', ?, 'CREATED', ?, ?) RETURNING id
                """, Long.class, biz, channel, phone, status, Timestamp.from(Instant.parse(at)));
    }

    @Test
    void membershipDecidesWhichBusinessesAreVisible() {
        assertThat(service.visibleBusinesses(anna.getId())).extracting(ResponseOverviewService.Biz::id).containsExactlyInAnyOrder(b1, b2);
        assertThat(service.visibleBusinesses(solo.getId())).extracting(ResponseOverviewService.Biz::id).containsExactly(b1);
    }

    @Test
    void firstTouchCountsNewLeadsOnlyAndUsesMembershipShifts() {
        jdbc.update("INSERT INTO manager_time_entry (user_id, work_date, start_at, end_at) VALUES (?, '2026-11-11', ?, NULL)",
                anna.getId(), Timestamp.from(Instant.parse("2026-11-11T16:00:00Z")));
        lead(b2, "Form", "+15550002222", "2026-11-11T17:00:00Z", "CONTACTED");
        jdbc.update("""
                INSERT INTO sms_message (business_id, direction, phone_number, body, status, created_at, sent_by_user_id)
                VALUES (?, 'OUTBOUND', '+15550002222', 'Hi!', 'SENT', ?, ?)
                """, b2, Timestamp.from(Instant.parse("2026-11-11T17:06:00Z")), anna.getId());
        lead(b2, "Square", "+15550003333", "2026-11-11T17:00:00Z", "NEW");
        lead(b2, "SMS", "+15550004444", "2026-11-11T19:50:00Z", "NEW");
        lead(b2, "Form", "+15550005555", "2026-11-11T18:00:00Z", "SPAM");

        var report = service.overview(anna.getId(), "today").businesses().stream().filter(r -> r.businessId() == b2).findFirst().orElseThrow();

        var nl = report.newLeads();
        assertThat(nl.created()).isEqualTo(3);
        assertThat(nl.firstTouch().replies()).isEqualTo(1);
        assertThat(nl.firstTouch().medianSeconds()).isEqualTo(360);
        assertThat(nl.pending()).isEqualTo(1);
        assertThat(nl.oldestPendingSeconds()).isEqualTo(600);
        assertThat(nl.closedWithoutContact()).isEqualTo(1);
        assertThat(nl.managers()).extracting(ResponseOverviewService.LeadManager::userId).containsExactly(anna.getId());
    }

    @Test
    void secondContactFromSamePersonIsNotANewLead() {
        jdbc.update("INSERT INTO manager_time_entry (user_id, work_date, start_at, end_at) VALUES (?, '2026-11-11', ?, NULL)",
                anna.getId(), Timestamp.from(Instant.parse("2026-11-11T08:00:00Z")));
        lead(b1, "Form", "+15550006666", "2026-11-01T10:00:00Z", "CONTACTED");
        lead(b1, "SMS", "+15550006666", "2026-11-11T19:00:00Z", "NEW");

        var report = service.overview(anna.getId(), "today").businesses().stream().filter(r -> r.businessId() == b1).findFirst().orElseThrow();

        assertThat(report.newLeads().created()).isZero();
        assertThat(report.newLeads().pending()).isZero();
    }
}
