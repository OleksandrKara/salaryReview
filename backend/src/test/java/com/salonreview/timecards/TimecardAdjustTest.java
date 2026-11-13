package com.salonreview.timecards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import com.salonreview.square.SquareLaborClient;
import com.salonreview.timecards.TimecardDayService.DayCalc;
import com.salonreview.timecards.TimecardDtos.SubmitRequest;
import com.salonreview.timecards.TimecardTypes.DayTimecard;
import com.salonreview.timecards.TimecardTypes.BreakKind;
import com.salonreview.timecards.TimecardTypes.TimeSpan;
import com.salonreview.timecards.TimecardTypes.TimecardBreak;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class TimecardAdjustTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    private static TimecardBreak meal(String a, String b) {
        return new TimecardBreak(new TimeSpan(Instant.parse(a), Instant.parse(b)), BreakKind.UNPAID);
    }

    private static TimecardBreak rest(String a, String b) {
        return new TimecardBreak(new TimeSpan(Instant.parse(a), Instant.parse(b)), BreakKind.PAID_REST);
    }

    private static final String START = "2026-11-06T23:45:00Z";
    private static final String END_OLD = "2026-11-07T02:45:00Z";
    private static final String END_NEW = "2026-11-07T03:31:00Z";

    private JdbcTemplate jdbc;
    private TimecardDayService days;
    private SquareLaborClient labor;
    private SquareConnectionRepository connections;
    private TimecardSyncService sync;
    private TimecardReviewService svc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        days = mock(TimecardDayService.class);
        labor = mock(SquareLaborClient.class);
        connections = mock(SquareConnectionRepository.class);
        sync = mock(TimecardSyncService.class);
        svc = new TimecardReviewService(jdbc, days, labor, connections, sync) {
            @Override long ensureReview(Long b, Long p, LocalDate d, String mode) { return 1L; }
        };
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of("W2"));
    }

    private DayCalc calc(String source, String status) {
        List<DayTimecard> cards = List.of(new DayTimecard(
                new TimeSpan(Instant.parse(START), Instant.parse(END_OLD)), List.of()));
        return new DayCalc(DAY, 43L, source, cards, List.of(), null, 1850, null, status, false, false, false);
    }

    @Test
    void approvedDayWorkerSubmitGetsHelpfulConflict() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "APPROVED"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.submit(1L, 43L,
                new SubmitRequest("2026-11-06", START, END_NEW, null, null, false, false, null, null)));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("reopen"));
    }

    @Test
    void workerCannotChangeHoursOfSquareDay() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.submit(1L, 43L,
                new SubmitRequest("2026-11-06", START, END_NEW, null, null, false, false, null, null)));
        assertEquals(400, e.getStatusCode().value());
        verify(jdbc, never()).update(contains("WORKER_CONFIRMED"), any(Object[].class));
    }

    @Test
    void adjustNeedsReasonAndSaneHours() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "APPROVED"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_NEW, " ")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 18L, "OWNER", 43L, DAY, END_NEW, START, "x")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 18L, "OWNER", 43L, DAY, "2026-11-05T23:45:00Z", END_NEW, "x")).getStatusCode().value());
        verify(labor, never()).updateTimecard(anyLong(), anyString(), any());
    }

    @Test
    void nonApproverCannotAdjust() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 5L, "MANAGER", 43L, DAY, START, END_NEW, "x")).getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    @Test
    void approvedSquareDayIsUpdatedInSquareAndReopened() throws Exception {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "APPROVED"));
        when(days.memberIds(43L)).thenReturn(List.of("TM1"));
        SquareConnection conn = new SquareConnection();
        conn.setLocationId("LOC");
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(conn));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<Object[]>of(new Object[] {"TC1", 1, "[]", "America/Los_Angeles", 1850}));
        JsonNode updated = new ObjectMapper().readTree("{\"id\":\"TC1\",\"version\":2}");
        when(labor.updateTimecard(eq(1L), eq("TC1"), any())).thenReturn(updated);

        svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_NEW, "Complex pedicure ran long");

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(labor).updateTimecard(eq(1L), eq("TC1"), body.capture());
        assertEquals(END_NEW, body.getValue().get("end_at").asText());
        assertEquals(1, body.getValue().get("version").asInt());
        verify(sync).upsert(1L, 43L, updated);
        verify(jdbc).update(contains("status = 'NEEDS_REVIEW'"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO timecard_audit"), any(Object[].class));
    }

    @Test
    void removeMealDropsUnpaidBreakAndKeepsPaidRest() throws Exception {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        when(days.memberIds(43L)).thenReturn(List.of("TM1"));
        SquareConnection conn = new SquareConnection();
        conn.setLocationId("LOC");
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(conn));
        when(labor.listBreakTypes(1L, "LOC")).thenReturn(List.of(new ObjectMapper().readTree("{\"id\":\"BT2\",\"break_name\":\"Paid rest 10 min\"}")));
        String breaks = "[{\"start_at\":\"2026-11-07T00:15:00Z\",\"end_at\":\"2026-11-07T00:30:00Z\",\"is_paid\":false},"
                + "{\"start_at\":\"2026-11-07T01:00:00Z\",\"end_at\":\"2026-11-07T01:10:00Z\",\"is_paid\":true}]";
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<Object[]>of(new Object[] {"TC1", 1, breaks, "America/Los_Angeles", 1850}));
        JsonNode updated = new ObjectMapper().readTree("{\"id\":\"TC1\",\"version\":2}");
        when(labor.updateTimecard(eq(1L), eq("TC1"), any())).thenReturn(updated);

        svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_OLD, "No meal was taken",
                List.of(rest("2026-11-07T01:00:00Z", "2026-11-07T01:10:00Z")));

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(labor).updateTimecard(eq(1L), eq("TC1"), body.capture());
        assertEquals(1, body.getValue().get("breaks").size());
        assertTrue(body.getValue().get("breaks").get(0).get("is_paid").asBoolean());
    }

    @Test
    void twoUnpaidBreaksReplaceOldMealAndKeepPaidRest() throws Exception {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        when(days.memberIds(43L)).thenReturn(List.of("TM1"));
        SquareConnection conn = new SquareConnection();
        conn.setLocationId("LOC");
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(conn));
        String breaks = "[{\"start_at\":\"2026-11-07T00:15:00Z\",\"end_at\":\"2026-11-07T00:30:00Z\",\"is_paid\":false},"
                + "{\"start_at\":\"2026-11-07T01:00:00Z\",\"end_at\":\"2026-11-07T01:10:00Z\",\"is_paid\":true}]";
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<Object[]>of(new Object[] {"TC1", 1, breaks, "America/Los_Angeles", 1850}));
        when(labor.listBreakTypes(1L, "LOC")).thenReturn(List.of(new ObjectMapper().readTree("{\"id\":\"BT1\",\"break_name\":\"Unpaid break\"}"),
                new ObjectMapper().readTree("{\"id\":\"BT2\",\"break_name\":\"Paid rest 10 min\"}")));
        JsonNode updated = new ObjectMapper().readTree("{\"id\":\"TC1\",\"version\":2}");
        when(labor.updateTimecard(eq(1L), eq("TC1"), any())).thenReturn(updated);

        svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_OLD, "Two real breaks", List.of(
                meal("2026-11-07T00:30:00Z", "2026-11-07T01:26:00Z"),
                rest("2026-11-07T01:30:00Z", "2026-11-07T01:40:00Z"),
                meal("2026-11-07T01:45:00Z", "2026-11-07T02:30:00Z")));

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(labor).updateTimecard(eq(1L), eq("TC1"), body.capture());
        JsonNode sent = body.getValue().get("breaks");
        assertEquals(3, sent.size());
        assertEquals("PT56M", sent.get(0).get("expected_duration").asText());
        assertTrue(sent.get(1).get("is_paid").asBoolean());
        assertEquals("PT45M", sent.get(2).get("expected_duration").asText());
    }

    @Test
    void emptyBreakListRemovesPaidRestToo() throws Exception {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        when(days.memberIds(43L)).thenReturn(List.of("TM1"));
        SquareConnection conn = new SquareConnection();
        conn.setLocationId("LOC");
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(conn));
        String breaks = "[{\"start_at\":\"2026-11-07T01:00:00Z\",\"end_at\":\"2026-11-07T01:10:00Z\",\"is_paid\":true}]";
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<Object[]>of(new Object[] {"TC1", 1, breaks, "America/Los_Angeles", 1850}));
        when(labor.updateTimecard(eq(1L), eq("TC1"), any())).thenReturn(new ObjectMapper().readTree("{\"id\":\"TC1\",\"version\":2}"));

        svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_OLD, "No rest was taken", List.of());

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(labor).updateTimecard(eq(1L), eq("TC1"), body.capture());
        assertEquals(0, body.getValue().get("breaks").size());
    }

    @Test
    void overlappingOrOutsideBreaksAreRejected() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        ResponseStatusException overlap = assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_OLD, "x", List.of(
                        meal("2026-11-07T00:00:00Z", "2026-11-07T00:40:00Z"),
                        meal("2026-11-07T00:30:00Z", "2026-11-07T01:00:00Z"))));
        assertEquals(400, overlap.getStatusCode().value());
        ResponseStatusException outside = assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_OLD, "x", List.of(
                        meal("2026-11-07T02:30:00Z", "2026-11-07T03:00:00Z"))));
        assertEquals(400, outside.getStatusCode().value());
    }

    @Test
    void squareRejectionBecomesReadableError() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        when(days.memberIds(43L)).thenReturn(List.of("TM1"));
        SquareConnection conn = new SquareConnection();
        conn.setLocationId("LOC");
        when(connections.findByBusinessId(1L)).thenReturn(Optional.of(conn));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<Object[]>of(new Object[] {"TC1", 1, "[]", "America/Los_Angeles", 1850}));
        when(labor.updateTimecard(eq(1L), eq("TC1"), any())).thenThrow(new RuntimeException("409 VERSION_MISMATCH"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_NEW, "x"));
        assertEquals(400, e.getStatusCode().value());
        verify(sync, never()).upsert(anyLong(), anyLong(), any());
    }

    @Test
    void draftDayChangesProposedHours() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("DRAFT", "WORKER_CONFIRMED"));
        svc.adjust(1L, 18L, "OWNER", 43L, DAY, START, END_NEW, "Ran long");
        verify(jdbc).update(contains("SET proposed_json"), any(Object[].class));
        verify(jdbc).update(contains("status = 'NEEDS_REVIEW'"), any(Object[].class));
        verify(labor, never()).updateTimecard(anyLong(), anyString(), any());
    }
}
