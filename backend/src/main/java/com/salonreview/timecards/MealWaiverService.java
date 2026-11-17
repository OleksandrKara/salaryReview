package com.salonreview.timecards;

import com.salonreview.timecards.TimecardDayService.DayCalc;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Owner-managed meal-break waivers: status per W2 employee, effective dates, and preview/apply of past-dated changes. */
@Service
public class MealWaiverService {
    private static final ZoneId PT = ZoneId.of("America/Los_Angeles");

    public record DocLink(long id, String documentType, String label, String fileName) {}

    public record WaiverRow(Long providerId, String name, boolean w2, boolean signed, String status, String signedDate,
                            String effectiveFrom, String revokedDate, String source, String recordedBy,
                            String signedName, DocLink document) {}

    public record DocOption(long id, String documentType, String label, String fileName) {}

    public record AffectedDay(String date, String status, int premiumBeforeCents, int premiumAfterCents,
                              int changeCents, boolean returnedToReview) {}

    public record Preview(Long providerId, String name, String from, String effect, List<AffectedDay> days,
                          int totalChangeCents, int returnedToReviewCount, boolean applied) {}

    public record RecordRequest(String signedDate, String effectiveFrom, Long documentId, String source,
                                String signedName, Boolean apply) {}

    public record RevokeRequest(String effectiveDate, String note, Boolean apply) {}

    private final JdbcTemplate jdbc;
    private final TimecardDayService days;

    public MealWaiverService(JdbcTemplate jdbc, TimecardDayService days) {
        this.jdbc = jdbc;
        this.days = days;
    }

    public List<WaiverRow> overview(Long businessId) {
        return jdbc.query("SELECT id, display_name, worker_type FROM providers WHERE business_id = ? AND active "
                        + "ORDER BY display_name",
                (rs, i) -> row(businessId, rs.getLong(1), rs.getString(2), "W2".equals(rs.getString(3))), businessId);
    }

    private WaiverRow row(Long businessId, Long providerId, String name, boolean w2) {
        List<WaiverRow> r = jdbc.query("""
                SELECT a.signed_at, a.effective_from, a.revoked_at, a.revoked_effective, a.signed_via, a.recorded_by,
                       a.signed_name, d.id, d.document_type, d.label, d.file_name
                FROM meal_waiver_agreement a LEFT JOIN staff_documents d ON d.id = a.document_id
                WHERE a.business_id = ? AND a.provider_id = ? ORDER BY a.signed_at DESC, a.id DESC LIMIT 1""",
                (rs, i) -> {
                    LocalDate signed = rs.getTimestamp(1).toInstant().atZone(PT).toLocalDate();
                    LocalDate eff = rs.getDate(2) == null ? signed : rs.getDate(2).toLocalDate();
                    LocalDate rev = rs.getTimestamp(3) == null ? null
                            : rs.getDate(4) != null ? rs.getDate(4).toLocalDate()
                            : rs.getTimestamp(3).toInstant().atZone(PT).toLocalDate();
                    LocalDate today = LocalDate.now(PT);
                    String status = rev != null && !rev.isAfter(today) ? "REVOKED"
                            : rev != null ? "REVOKING" : eff.isAfter(today) ? "PENDING" : "ACTIVE";
                    DocLink doc = rs.getObject(8) == null ? null
                            : new DocLink(rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11));
                    return new WaiverRow(providerId, name, w2, true, status, signed.toString(), eff.toString(),
                            rev == null ? null : rev.toString(), rs.getString(5), rs.getString(6), rs.getString(7), doc);
                }, businessId, providerId);
        if (!r.isEmpty()) return r.get(0);
        return new WaiverRow(providerId, name, w2, false, "NONE", null, null, null, null, null, null, null);
    }

    public List<DocOption> documents(Long businessId, Long providerId) {
        return jdbc.query("SELECT d.id, d.document_type, d.label, d.file_name FROM staff_documents d "
                        + "JOIN providers p ON p.id = d.provider_id WHERE p.business_id = ? AND d.provider_id = ? "
                        + "ORDER BY d.created_at DESC",
                (rs, i) -> new DocOption(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                businessId, providerId);
    }

    @Transactional
    public Preview record(Long businessId, Long providerId, RecordRequest req, String actor) {
        String name = providerName(businessId, providerId);
        requireW2(businessId, providerId);
        LocalDate signed = date(req.signedDate(), "Signed date");
        LocalDate eff = date(req.effectiveFrom(), "Effective from");
        if (signed.isAfter(LocalDate.now(PT))) throw bad("Signed date cannot be in the future");
        if (eff.isBefore(signed)) throw bad("Effective date cannot be before the signed date");
        String source = req.source() == null || req.source().isBlank() ? "UPLOADED" : req.source().trim().toUpperCase();
        if (!List.of("SIGNNOW", "UPLOADED").contains(source)) throw bad("Source must be SIGNNOW or UPLOADED");
        if (req.documentId() != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM staff_documents d JOIN providers p ON p.id = d.provider_id "
                    + "WHERE d.id = ? AND d.provider_id = ? AND p.business_id = ?", Integer.class,
                    req.documentId(), providerId, businessId);
            if (n == null || n == 0) throw bad("Document not found for this employee");
        }
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM meal_waiver_agreement WHERE business_id = ? AND provider_id = ? "
                + "AND revoked_at IS NULL", Integer.class, businessId, providerId);
        if (open != null && open > 0) throw bad("A waiver is already on file. Revoke it first to record a new one");
        boolean apply = Boolean.TRUE.equals(req.apply());
        List<AffectedDay> list = affected(businessId, providerId, eff, true);
        if (apply) {
            String signer = req.signedName() == null || req.signedName().isBlank() ? name : req.signedName().trim();
            jdbc.update("INSERT INTO meal_waiver_agreement (business_id, provider_id, text_version, text_body, signed_name, "
                            + "signed_at, signed_via, effective_from, document_id, recorded_by) "
                            + "VALUES (?, ?, 'EXTERNAL', 'Signed outside the app; see the linked document', ?, "
                            + "(?::date)::timestamp AT TIME ZONE 'America/Los_Angeles' + interval '12 hours', ?, ?, ?, ?)",
                    businessId, providerId, signer, Date.valueOf(signed), source, Date.valueOf(eff),
                    req.documentId(), actor);
            list = reopen(businessId, providerId, list, actor, "MEAL_WAIVER_RECORDED");
        }
        return preview(providerId, name, eff, "WAIVER_ADDED", list, apply);
    }

    @Transactional
    public Preview revoke(Long businessId, Long providerId, RevokeRequest req, String actor) {
        String name = providerName(businessId, providerId);
        LocalDate eff = req.effectiveDate() == null || req.effectiveDate().isBlank()
                ? LocalDate.now(PT) : date(req.effectiveDate(), "Effective date");
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM meal_waiver_agreement WHERE business_id = ? AND provider_id = ? "
                + "AND revoked_at IS NULL", Integer.class, businessId, providerId);
        if (open == null || open == 0) throw bad("No active waiver to revoke");
        boolean apply = Boolean.TRUE.equals(req.apply());
        List<AffectedDay> list = affected(businessId, providerId, eff, false);
        if (apply) {
            jdbc.update("UPDATE meal_waiver_agreement SET revoked_at = now(), revoked_effective = ?, revoked_note = ?, "
                            + "revoked_by = ? WHERE business_id = ? AND provider_id = ? AND revoked_at IS NULL",
                    Date.valueOf(eff), req.note(), actor, businessId, providerId);
            list = reopen(businessId, providerId, list, actor, "MEAL_WAIVER_REVOKED");
        }
        return preview(providerId, name, eff, "WAIVER_REVOKED", list, apply);
    }

    /** Days from {@code from} through today whose premium would change if the waiver were present (adding) or absent (revoking). */
    private List<AffectedDay> affected(Long businessId, Long providerId, LocalDate from, boolean adding) {
        LocalDate today = LocalDate.now(PT);
        List<AffectedDay> out = new ArrayList<>();
        List<Object[]> reviews = jdbc.query("SELECT work_date, status FROM timecard_review WHERE business_id = ? "
                        + "AND provider_id = ? AND work_date >= ? AND work_date <= ? ORDER BY work_date",
                (rs, i) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getString(2)},
                businessId, providerId, Date.valueOf(from), Date.valueOf(today));
        for (Object[] rv : reviews) {
            LocalDate d = (LocalDate) rv[0];
            String status = (String) rv[1];
            DayCalc now = days.calc(businessId, providerId, d);
            DayCalc hyp = days.calc(businessId, providerId, d, adding);
            int before = days.premiumCents(now);
            int after = days.premiumCents(hyp);
            if (before == after) continue;
            boolean approved = "APPROVED".equals(status) || "CONFIRMED_PENDING_WRITE".equals(status);
            out.add(new AffectedDay(d.toString(), status, before, after, after - before, approved));
        }
        return out;
    }

    private List<AffectedDay> reopen(Long businessId, Long providerId, List<AffectedDay> list, String actor, String action) {
        for (AffectedDay a : list) {
            if (!a.returnedToReview()) continue;
            List<Long> ids = jdbc.queryForList("SELECT id FROM timecard_review WHERE business_id = ? AND provider_id = ? "
                    + "AND work_date = ?", Long.class, businessId, providerId, Date.valueOf(a.date()));
            if (ids.isEmpty()) continue;
            jdbc.update("UPDATE timecard_review SET status = 'NEEDS_REVIEW', approved_at = NULL, approved_by_user_id = NULL "
                    + "WHERE id = ? AND business_id = ?", ids.get(0), businessId);
            String detail = "{\"premiumBeforeCents\":" + a.premiumBeforeCents() + ",\"premiumAfterCents\":"
                    + a.premiumAfterCents() + "}";
            jdbc.update("INSERT INTO timecard_audit (business_id, review_id, actor, action, before_json, after_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", businessId, ids.get(0), actor, action,
                    "{\"status\":\"" + a.status() + "\"}", detail);
        }
        return list;
    }

    private Preview preview(Long providerId, String name, LocalDate from, String effect, List<AffectedDay> list, boolean applied) {
        int total = list.stream().mapToInt(AffectedDay::changeCents).sum();
        int returned = (int) list.stream().filter(AffectedDay::returnedToReview).count();
        return new Preview(providerId, name, from.toString(), effect, list, total, returned, applied);
    }

    private String providerName(Long businessId, Long providerId) {
        List<String> n = jdbc.queryForList("SELECT display_name FROM providers WHERE id = ? AND business_id = ?",
                String.class, providerId, businessId);
        if (n.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found");
        return n.get(0);
    }

    private void requireW2(Long businessId, Long providerId) {
        List<String> t = jdbc.queryForList("SELECT worker_type FROM providers WHERE id = ? AND business_id = ?",
                String.class, providerId, businessId);
        if (t.isEmpty() || !"W2".equals(t.get(0))) throw bad("Only W2 employees can have a meal waiver");
    }

    private static LocalDate date(String s, String label) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw bad(label + " is required (yyyy-MM-dd)");
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
