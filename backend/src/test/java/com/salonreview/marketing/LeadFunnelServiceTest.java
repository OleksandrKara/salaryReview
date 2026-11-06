package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.marketing.LeadFunnelService.AcademyRow;
import com.salonreview.marketing.LeadFunnelService.ConsultRow;
import com.salonreview.marketing.LeadFunnelService.Data;
import com.salonreview.marketing.LeadFunnelService.Funnel;
import com.salonreview.marketing.LeadFunnelService.SiteRow;
import com.salonreview.sms.SmsAutomationService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LeadFunnelServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T20:00:00Z");
    private SmsAutomationService automations;
    private LeadFunnelService svc;

    @BeforeEach
    void setUp() {
        automations = mock(SmsAutomationService.class);
        when(automations.isEnabled(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        svc = new LeadFunnelService(mock(JdbcTemplate.class), automations, mock(LeadFollowUpService.class));
    }

    private static LeadView lead(String status, boolean academy, String consultState) {
        return new LeadView(1, "Form", "Google", "+15550001111", null, "Jane", "PMU course", status, null,
                "2026-11-06T10:00:00Z", "2026-11-06T10:00:00Z", 1, null, null, null, null, false, null, null, null,
                "2026-11-06T10:00:00Z", academy, consultState, null, 0, false, null);
    }

    private static Data data(List<AcademyRow> academy, Map<Long, Map<String, String[]>> touches, List<ConsultRow> consult, List<SiteRow> site) {
        Map<String, List<AcademyRow>> a = new HashMap<>();
        Map<String, List<ConsultRow>> c = new HashMap<>();
        Map<String, List<SiteRow>> s = new HashMap<>();
        academy.forEach(r -> a.computeIfAbsent("5550001111", k -> new java.util.ArrayList<>()).add(r));
        consult.forEach(r -> c.computeIfAbsent("5550001111", k -> new java.util.ArrayList<>()).add(r));
        site.forEach(r -> s.computeIfAbsent("5550001111", k -> new java.util.ArrayList<>()).add(r));
        return new Data(a, touches, c, s);
    }

    private Funnel one(List<Funnel> fs, String key) {
        return fs.stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void academyLeadWithoutFunnelCanBeApplied() {
        List<Funnel> fs = svc.evaluate(2, lead("NEW", true, null), data(List.of(), Map.of(), List.of(), List.of()), NOW);
        Funnel f = one(fs, "academy");
        assertEquals("NOT_APPLIED", f.state());
        assertTrue(f.canApply());
    }

    @Test
    void repeatRequestMarkedDuplicateDoesNotHideTheFunnelThatRan() {
        Instant t = NOW.minusSeconds(3600);
        List<AcademyRow> rows = List.of(new AcademyRow(10, "+15550001111", null, "replied", t),
                new AcademyRow(11, "+15550001111", null, "duplicate", t), new AcademyRow(12, "+15550001111", null, "duplicate", t));
        Funnel f = one(svc.evaluate(2, lead("NEW", true, null), data(rows, Map.of(), List.of(), List.of()), NOW), "academy");
        assertEquals("REPLIED", f.reason());
    }

    @Test
    void nonAcademyLeadHasNoAcademyFunnel() {
        List<Funnel> fs = svc.evaluate(2, lead("NEW", false, null), data(List.of(), Map.of(), List.of(), List.of()), NOW);
        assertTrue(fs.stream().noneMatch(f -> f.key().equals("academy")));
    }

    @Test
    void runningAcademyFunnelIsActiveAndStoppable() {
        AcademyRow row = new AcademyRow(7, "+15550001111", null, "new", NOW.minusSeconds(3600));
        Map<Long, Map<String, String[]>> touches = Map.of(7L, Map.of("confirm_email", new String[] {"sent", NOW.toString()}));
        Funnel f = one(svc.evaluate(2, lead("NEW", true, null), data(List.of(row), touches, List.of(), List.of()), NOW), "academy");
        assertEquals("ACTIVE", f.state());
        assertTrue(f.canStop());
        assertFalse(f.canApply());
        assertEquals("DONE", f.steps().get(0).state());
        assertEquals("PENDING", f.steps().get(1).state());
    }

    @Test
    void academyStoppedByStaffAndReapplyOnlyAfterCooldown() {
        AcademyRow recent = new AcademyRow(7, "+15550001111", null, "contacted", NOW.minusSeconds(86400 * 3));
        Funnel f = one(svc.evaluate(2, lead("CONTACTED", true, null), data(List.of(recent), Map.of(), List.of(), List.of()), NOW), "academy");
        assertEquals("STOPPED", f.state());
        assertEquals("STAFF", f.reason());
        assertFalse(f.canApply());
        AcademyRow old = new AcademyRow(7, "+15550001111", null, "contacted", NOW.minusSeconds(86400 * 20));
        assertTrue(one(svc.evaluate(2, lead("CONTACTED", true, null), data(List.of(old), Map.of(), List.of(), List.of()), NOW), "academy").canApply());
    }

    @Test
    void academyOldRowIsFinishedAndPausedWhenAutomationOff() {
        AcademyRow old = new AcademyRow(7, "+15550001111", null, "new", NOW.minusSeconds(86400 * 11));
        assertEquals("FINISHED", one(svc.evaluate(2, lead("NEW", true, null), data(List.of(old), Map.of(), List.of(), List.of()), NOW), "academy").state());
        when(automations.isEnabled(eq(2L), eq("academy_lead_funnel"))).thenReturn(false);
        AcademyRow fresh = new AcademyRow(8, "+15550001111", null, "new", NOW.minusSeconds(3600));
        Funnel f = one(svc.evaluate(2, lead("NEW", true, null), data(List.of(fresh), Map.of(), List.of(), List.of()), NOW), "academy");
        assertEquals("PAUSED", f.state());
        assertEquals("AUTOMATION_OFF", f.reason());
    }

    @Test
    void closedLeadCannotBeApplied() {
        assertFalse(one(svc.evaluate(2, lead("LOST", true, null), data(List.of(), Map.of(), List.of(), List.of()), NOW), "academy").canApply());
    }

    @Test
    void consultFunnelStatesAndNoManualApply() {
        Funnel none = one(svc.evaluate(2, lead("NEW", false, "DONE"), data(List.of(), Map.of(), List.of(), List.of()), NOW), "consult");
        assertEquals("NOT_APPLIED", none.state());
        assertFalse(none.canApply());
        ConsultRow active = new ConsultRow(1, "+15550001111", null, NOW.minusSeconds(86400 * 3), List.of("SENT", "", "", ""));
        Funnel a = one(svc.evaluate(2, lead("NEW", false, "DONE"), data(List.of(), Map.of(), List.of(active), List.of()), NOW), "consult");
        assertEquals("ACTIVE", a.state());
        assertTrue(a.canStop());
        ConsultRow booked = new ConsultRow(1, "+15550001111", "BOOKED", NOW.minusSeconds(86400 * 3), List.of("SENT", "", "", ""));
        Funnel b = one(svc.evaluate(2, lead("NEW", false, "DONE"), data(List.of(), Map.of(), List.of(booked), List.of()), NOW), "consult");
        assertEquals("STOPPED", b.state());
        assertEquals("BOOKED", b.reason());
        ConsultRow done = new ConsultRow(1, "+15550001111", null, NOW.minusSeconds(86400 * 50), List.of("SENT", "SENT", "SENT", "SENT"));
        assertEquals("FINISHED", one(svc.evaluate(2, lead("NEW", false, "DONE"), data(List.of(), Map.of(), List.of(done), List.of()), NOW), "consult").state());
    }

    @Test
    void siteFunnelStates() {
        SiteRow fresh = new SiteRow("+15550001111", "SENT", null, null, NOW.minusSeconds(3600));
        assertEquals("ACTIVE", one(svc.evaluate(1, lead("NEW", false, null), data(List.of(), Map.of(), List.of(), List.of(fresh)), NOW), "site").state());
        SiteRow booked = new SiteRow("+15550001111", "SKIPPED_BOOKED", null, null, NOW.minusSeconds(3600));
        Funnel b = one(svc.evaluate(1, lead("NEW", false, null), data(List.of(), Map.of(), List.of(), List.of(booked)), NOW), "site");
        assertEquals("STOPPED", b.state());
        assertEquals("BOOKED", b.reason());
        SiteRow full = new SiteRow("+15550001111", "SENT", "SENT", "SENT", NOW.minusSeconds(86400));
        assertEquals("FINISHED", one(svc.evaluate(1, lead("NEW", false, null), data(List.of(), Map.of(), List.of(), List.of(full)), NOW), "site").state());
        assertFalse(one(svc.evaluate(1, lead("NEW", false, null), data(List.of(), Map.of(), List.of(), List.of()), NOW), "site").canApply());
    }

    @Test
    void phoneKeyNormalises() {
        assertEquals("5550001111", LeadFunnelService.phoneKey("+1 (555) 000-1111"));
        assertEquals(null, LeadFunnelService.phoneKey("123"));
    }
}
