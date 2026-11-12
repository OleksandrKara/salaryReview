package com.salonreview.telegram;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves the CRM lead (by phone or email), its Academy flag and who must handle it, for Telegram
 * alerts. Never throws: any failure yields {@link LeadAlertContext#UNKNOWN} so the alert still sends. */
@Service
public class LeadAlertContextService {

    private static final Logger log = LoggerFactory.getLogger(LeadAlertContextService.class);
    static final String ACADEMY_SCOPE = "ACADEMY";

    private final JdbcTemplate jdbc;

    public LeadAlertContextService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LeadAlertContext lookup(Long businessId, String phone, String email) {
        try {
            return doLookup(businessId, phone, email);
        } catch (RuntimeException e) {
            log.warn("Lead context lookup failed (alert still sent): {}", e.toString());
            return LeadAlertContext.UNKNOWN;
        }
    }

    /** The Academy request is captured into the CRM right after its alert, so the lead is known to exist. */
    public LeadAlertContext forAcademyRequest(Long businessId) {
        try {
            List<String> owners = new ArrayList<>();
            String def = academyDefaultName(businessId);
            if (def != null) owners.add(def);
            return new LeadAlertContext(LeadAlertContext.State.FOUND, null, true, owners, null);
        } catch (RuntimeException e) {
            log.warn("Academy lead context lookup failed (alert still sent): {}", e.toString());
            return new LeadAlertContext(LeadAlertContext.State.UNKNOWN, null, true, List.of(), null);
        }
    }

    private LeadAlertContext doLookup(Long businessId, String phone, String email) {
        if (businessId == null) return LeadAlertContext.UNKNOWN;
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        String tail = digits.length() >= 10 ? digits.substring(digits.length() - 10) : "";
        String mail = email == null || email.isBlank() ? "" : email.trim().toLowerCase();
        if (tail.isEmpty() && mail.isEmpty()) return LeadAlertContext.MISSING;
        List<Object[]> rows = new ArrayList<>();
        jdbc.query("""
                SELECT id, channel, academy_override, owner_user_id, traffic_source FROM lead_intake
                WHERE business_id = ? AND outcome <> 'DUPLICATE'
                  AND ((? <> '' AND right(regexp_replace(COALESCE(phone_number, ''), '\\D', '', 'g'), 10) = ?)
                    OR (? <> '' AND lower(email) = ?))
                ORDER BY created_at DESC, id DESC
                """, rs -> {
            rows.add(new Object[]{rs.getLong("id"), rs.getString("channel"), rs.getObject("academy_override"),
                    rs.getObject("owner_user_id"), rs.getString("traffic_source")});
        }, businessId, tail, tail, mail, mail);
        if (rows.isEmpty()) return LeadAlertContext.MISSING;

        Boolean override = null;
        boolean academyChannel = false;
        Long owner = null;
        String source = null;
        for (Object[] r : rows) {
            if (override == null && r[2] != null) override = (Boolean) r[2];
            if ("Academy".equals(r[1])) academyChannel = true;
            if (owner == null && r[3] != null) owner = ((Number) r[3]).longValue();
            if (source == null && r[4] != null && !((String) r[4]).isBlank()) source = (String) r[4];
        }
        boolean academy = override != null ? override : academyChannel;
        List<String> owners = new ArrayList<>();
        String name = owner != null ? userName(owner) : null;
        if (name == null && academy) name = academyDefaultName(businessId);
        if (name != null) owners.add(name);
        return new LeadAlertContext(LeadAlertContext.State.FOUND, (Long) rows.get(0)[0], academy, owners, source);
    }

    private String academyDefaultName(Long businessId) {
        List<Long> ids = jdbc.queryForList("SELECT owner_user_id FROM lead_owner_default WHERE business_id = ? AND scope = ?",
                Long.class, businessId, ACADEMY_SCOPE);
        return ids.isEmpty() ? null : userName(ids.get(0));
    }

    private String userName(long userId) {
        List<String> r = jdbc.query("""
                SELECT a.role, p.display_name AS provider_name, a.display_name AS account_name, a.username FROM app_user a LEFT JOIN providers p ON p.id = a.provider_id
                WHERE a.id = ? AND a.active
                """, (rs, i) -> com.salonreview.marketing.OwnerLabels.label(rs.getString("role"), rs.getString("provider_name"), rs.getString("account_name"), rs.getString("username")), userId);
        return r.isEmpty() ? null : r.get(0);
    }
}
