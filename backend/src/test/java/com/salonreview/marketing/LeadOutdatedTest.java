package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadOutdatedTest {
    private JdbcTemplate jdbc;
    private LeadFollowUpService svc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(2L);
        ConsultationCatalog catalog = mock(ConsultationCatalog.class);
        svc = new LeadFollowUpService(jdbc, ctx, null, null, null, null, catalog, null, null, null, null, null, "https://x");
    }

    @Test
    void nothingCanBeMovedIntoOutdated() {
        var req = new LeadFollowUpService.UpdateRequest("OUTDATED", null, null, null, null, null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.update(1, req, "tester"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void outdatedQueryCapturesOnlyArchiveRows() {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("total", 5, "clients", 3));
        svc.outdated("anna 619", "clients", 0);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(sql.capture(), any(Object[].class));
        assertEquals(true, sql.getValue().contains("f.status = 'OUTDATED'"));
        var page = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(page.capture(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        assertEquals(true, page.getValue().contains("WHERE visits > 0"));
    }
}
