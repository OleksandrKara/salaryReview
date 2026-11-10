package com.salonreview.marketing;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.marketing.LeadFollowUpService.LeadView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is responsible for a lead. An explicit owner wins; otherwise Academy leads belong to the Academy default
 * (Anastasia, or the owner while she is away) and everything else belongs to the team (the managers on shift).
 */
@Service
public class LeadOwnerService {

    public static final String ACADEMY = "ACADEMY";

    /** kind: ASSIGNED (picked for this lead), DEFAULT (Academy default) or TEAM (nobody in particular). */
    public record Owner(Long userId, String name, String kind) {}

    public record Candidate(long userId, String name, String role) {}

    public record Info(Owner owner, List<Candidate> candidates, Long academyDefault) {}

    private static final Owner TEAM = new Owner(null, null, "TEAM");

    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext business;
    private final LeadFollowUpService leads;

    public LeadOwnerService(JdbcTemplate jdbc, CurrentBusinessContext business, LeadFollowUpService leads) {
        this.jdbc = jdbc;
        this.business = business;
        this.leads = leads;
    }

    /** Owners and the managers on the team are the people a lead can be handed to, plus anyone with Academy access. */
    public List<Candidate> candidates() {
        return candidates(null);
    }

    /** meId: the signed-in owner or manager is always a candidate, even without a membership row for this business. */
    public List<Candidate> candidates(Long meId) {
        return jdbc.query("""
                SELECT a.id, a.role, p.display_name, a.username FROM app_user a
                LEFT JOIN providers p ON p.id = a.provider_id
                WHERE a.active AND (
                    (a.id = ? AND a.role IN ('OWNER', 'MANAGER'))
                    OR a.id IN (SELECT m.user_id FROM business_membership m WHERE m.business_id = ? AND m.role IN ('OWNER', 'MANAGER'))
                    OR a.id IN (SELECT up.user_id FROM user_permission up WHERE up.business_id = ? AND up.permission IN ('LEADS_ACADEMY', 'LEADS_ALL')))
                ORDER BY CASE a.role WHEN 'OWNER' THEN 0 ELSE 1 END, a.username
                """, (rs, i) -> new Candidate(rs.getLong("id"), label(rs.getString("role"), rs.getString("display_name"), rs.getString("username")),
                rs.getString("role")), meId == null ? -1L : meId, business.id(), business.id());
    }

    private static String label(String role, String displayName, String username) {
        if (displayName != null && !displayName.isBlank()) return displayName.trim();
        return "OWNER".equals(role) ? "Owner (" + username + ")" : username;
    }

    public Long academyDefault() {
        List<Long> r = jdbc.queryForList("SELECT owner_user_id FROM lead_owner_default WHERE business_id = ? AND scope = ?",
                Long.class, business.id(), ACADEMY);
        return r.isEmpty() ? null : r.get(0);
    }

    private Map<Long, String> names(Long meId) {
        Map<Long, String> m = new HashMap<>();
        for (Candidate c : candidates(meId)) m.put(c.userId(), c.name());
        return m;
    }

    private Owner resolve(Long explicit, boolean academy, Long academyDefault, Map<Long, String> names) {
        if (explicit != null && names.containsKey(explicit)) return new Owner(explicit, names.get(explicit), "ASSIGNED");
        if (academy && academyDefault != null && names.containsKey(academyDefault)) {
            return new Owner(academyDefault, names.get(academyDefault), "DEFAULT");
        }
        return TEAM;
    }

    public Map<Long, Owner> forLeads(List<LeadView> list) {
        return forLeads(list, null);
    }

    public Map<Long, Owner> forLeads(List<LeadView> list, Long meId) {
        Map<Long, Long> explicit = leads.explicitOwners();
        Long def = academyDefault();
        Map<Long, String> names = names(meId);
        Map<Long, Owner> out = new HashMap<>();
        for (LeadView l : list) out.put(l.id(), resolve(explicit.get(l.id()), l.academy(), def, names));
        return out;
    }

    public Info info(long leadId, Long meId) {
        LeadView lead = leads.lead(leadId);
        Long explicit = leads.explicitOwners().get(leadId);
        Long def = academyDefault();
        return new Info(resolve(explicit, lead.academy(), def, names(meId)), candidates(meId), def);
    }

    /** userId null hands the lead back to the default (Academy default or the team). */
    public void assign(long leadId, Long userId, String by, Long meId) {
        LeadView lead = leads.lead(leadId);
        String name = null;
        if (userId != null) {
            name = names(meId).get(userId);
            if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "that person cannot own leads");
        }
        leads.setOwner(lead.id(), userId);
        leads.addEvent(lead.id(), "OWNER", name == null ? "none" : name, by);
    }

    public void setAcademyDefault(long userId, String by, Long meId) {
        if (!names(meId).containsKey(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "that person cannot own leads");
        jdbc.update("""
                INSERT INTO lead_owner_default (business_id, scope, owner_user_id, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (business_id, scope) DO UPDATE SET owner_user_id = EXCLUDED.owner_user_id, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, business.id(), ACADEMY, userId, by);
    }

    /** Lead ids the user is answerable for: assigned or defaulted to them, plus team leads when they are a manager. */
    public List<Long> mine(Map<Long, Owner> owners, long userId, boolean includeTeam) {
        List<Long> ids = new ArrayList<>();
        owners.forEach((id, o) -> {
            if (o.userId() != null ? o.userId() == userId : includeTeam) ids.add(id);
        });
        return ids;
    }
}
