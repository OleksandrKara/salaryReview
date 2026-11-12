package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.marketing.LeadOwnerService.Candidate;
import com.salonreview.marketing.LeadOwnerService.Owner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeadOwnerServiceTest {

    private LeadFollowUpService leads;
    private LeadOwnerService svc;

    @BeforeEach
    void setUp() {
        leads = mock(LeadFollowUpService.class);
        svc = new LeadOwnerService(null, null, leads) {
            @Override public List<Candidate> candidates(Long meId) {
                return List.of(new Candidate(31, "Alex", "OWNER"), new Candidate(42, "Anastasiia", "MANAGER"), new Candidate(17, "Manager", "MANAGER"));
            }
            @Override public Long academyDefault() { return 42L; }
            @Override protected Map<Long, String> assignedLabels(List<Long> ids) {
                return ids.contains(1L) ? Map.of(1L, "Owner (owner)") : Map.of();
            }
        };
    }

    private static LeadView lead(long id, boolean academy) {
        return new LeadView(id, "Form", "Google", "+15550001111", null, "Jane", null, "NEW", null,
                "2026-11-06T10:00:00Z", "2026-11-06T10:00:00Z", 1, null, null, null, null, false, null, null, null,
                "2026-11-06T10:00:00Z", academy, null, null, 0, false, null);
    }

    @Test
    void academyLeadFallsBackToDefaultAndAssignedWins() {
        when(leads.explicitOwners()).thenReturn(Map.of(2L, 31L));
        Map<Long, Owner> o = svc.forLeads(List.of(lead(1, true), lead(2, true)));
        assertEquals("DEFAULT", o.get(1L).kind());
        assertEquals(42L, o.get(1L).userId());
        assertEquals("ASSIGNED", o.get(2L).kind());
        assertEquals("Alex", o.get(2L).name());
    }

    @Test
    void assignedOwnerOutsideTheViewersCandidatesStillWins() {
        when(leads.explicitOwners()).thenReturn(Map.of(1L, 1L));
        Owner o = svc.forLeads(List.of(lead(1, true)), 42L).get(1L);
        assertEquals("ASSIGNED", o.kind());
        assertEquals(1L, o.userId());
        assertEquals("Owner (owner)", o.name());
        when(leads.lead(1L)).thenReturn(lead(1, true));
        assertEquals(1L, svc.info(1L, 42L).owner().userId());
    }

    @Test
    void nailLeadWithoutOwnerBelongsToTheTeam() {
        when(leads.explicitOwners()).thenReturn(Map.of());
        Owner o = svc.forLeads(List.of(lead(3, false))).get(3L);
        assertEquals("TEAM", o.kind());
        assertNull(o.userId());
    }

    @Test
    void mineIncludesTeamLeadsOnlyForManagers() {
        Map<Long, Owner> owners = Map.of(1L, new Owner(31L, "Alex", "ASSIGNED"), 2L, new Owner(null, null, "TEAM"), 3L, new Owner(42L, "A", "DEFAULT"));
        assertEquals(List.of(1L, 2L), svc.mine(owners, 31, true).stream().sorted().toList());
        assertEquals(List.of(3L), svc.mine(owners, 42, false));
    }

    @Test
    void changeEventCarriesFromToAndActor() throws Exception {
        var m = new com.fasterxml.jackson.databind.ObjectMapper();
        var n = m.readTree(LeadOwnerService.changeEvent(new Owner(42L, "Anastasiia", "DEFAULT"), new Owner(31L, "Alex (owner)", "ASSIGNED"), "Alex (owner)", false));
        assertEquals(1, n.get("v").asInt());
        assertEquals("Anastasiia", n.get("from").get("name").asText());
        assertEquals("DEFAULT", n.get("from").get("kind").asText());
        assertEquals("Alex (owner)", n.get("to").get("name").asText());
        assertEquals("Alex (owner)", n.get("by").asText());
        assertEquals(false, n.get("reset").asBoolean());
    }

    @Test
    void changeEventUsesSystemWhenNoActorAndTeamWhenNoOwner() throws Exception {
        var m = new com.fasterxml.jackson.databind.ObjectMapper();
        var n = m.readTree(LeadOwnerService.changeEvent(new Owner(31L, "Alex", "ASSIGNED"), new Owner(null, null, "TEAM"), null, true));
        assertEquals("System", n.get("by").asText());
        assertEquals("TEAM", n.get("to").get("kind").asText());
        assertEquals(true, n.get("reset").asBoolean());
    }
}
