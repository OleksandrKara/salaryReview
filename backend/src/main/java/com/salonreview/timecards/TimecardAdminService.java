package com.salonreview.timecards;

import com.salonreview.square.SquareClientProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/** Owner-side setup of W2 workers: type switch, hourly rate history, phone, flags, approvers. */
@Service
public class TimecardAdminService {

    public record Approver(Long userId, String username, String email, String role) {}

    public record ProviderSetup(Long providerId, String name, boolean active, String workerType,
                                boolean showCommissionComparisonToProvider, Integer hourlyRateCents,
                                String rateEffectiveFrom, String mobilePhone, boolean payrollAutomationEnabled,
                                boolean alertsEnabled, boolean smsEnabled, List<Approver> approvers,
                                boolean squareLinked) {}

    public record UpdateRequest(String workerType, Boolean showCommissionComparisonToProvider,
                                Integer hourlyRateCents, String rateEffectiveFrom, String mobilePhone,
                                Boolean payrollAutomationEnabled, Boolean alertsEnabled, Boolean smsEnabled,
                                List<Long> approverUserIds) {}

    private final JdbcTemplate jdbc;
    private final SquareClientProvider squares;
    private final TimecardDayService days;

    public TimecardAdminService(JdbcTemplate jdbc, SquareClientProvider squares, TimecardDayService days) {
        this.jdbc = jdbc;
        this.squares = squares;
        this.days = days;
    }

    /** Only providers Square reports as ACTIVE. If Square cannot be reached, nothing is shown rather than stale people. */
    public List<ProviderSetup> list(Long businessId) {
        java.util.Set<String> activeMembers = activeSquareMembers(businessId);
        List<Long> ids = jdbc.queryForList("""
                SELECT p.id FROM providers p JOIN provider_square_member m ON m.provider_id = p.id
                WHERE p.business_id = ? AND m.square_team_member_id = ANY (?) ORDER BY p.display_name""",
                Long.class, businessId, activeMembers.toArray(new String[0]));
        return ids.stream().map(id -> get(businessId, id)).toList();
    }

    private java.util.Set<String> activeSquareMembers(Long businessId) {
        return squares.forBusiness(businessId).activeTeamMembers().stream()
                .map(com.salonreview.square.SquareClient.TeamMember::id).collect(java.util.stream.Collectors.toSet());
    }

    public ProviderSetup get(Long businessId, Long providerId) {
        List<ProviderSetup> r = jdbc.query("""
                SELECT p.id, p.display_name, p.active, p.worker_type, p.show_commission_comparison_to_provider,
                       e.hourly_rate_cents, e.effective_from, e.mobile_phone,
                       COALESCE(e.payroll_automation_enabled, false), COALESCE(e.alerts_enabled, true),
                       COALESCE(e.sms_enabled, false)
                FROM providers p
                LEFT JOIN LATERAL (SELECT * FROM provider_employment x WHERE x.business_id = p.business_id
                    AND x.provider_id = p.id AND x.effective_to IS NULL ORDER BY x.effective_from DESC LIMIT 1) e ON true
                WHERE p.id = ? AND p.business_id = ?""",
                (rs, i) -> new ProviderSetup(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        rs.getBoolean(5), (Integer) rs.getObject(6),
                        rs.getDate(7) == null ? null : rs.getDate(7).toString(), rs.getString(8),
                        rs.getBoolean(9), rs.getBoolean(10), rs.getBoolean(11), List.of(), false),
                providerId, businessId);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found");
        ProviderSetup s = r.get(0);
        List<Approver> approvers = jdbc.query("""
                SELECT u.id, u.username, u.email, u.role FROM provider_time_approver a JOIN app_user u ON u.id = a.app_user_id
                WHERE a.business_id = ? AND a.provider_id = ? ORDER BY u.id""",
                (rs, i) -> new Approver(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                businessId, providerId);
        boolean linked = !days.memberIds(providerId).isEmpty();
        return new ProviderSetup(s.providerId(), s.name(), s.active(), s.workerType(),
                s.showCommissionComparisonToProvider(), s.hourlyRateCents(), s.rateEffectiveFrom(), s.mobilePhone(),
                s.payrollAutomationEnabled(), s.alertsEnabled(), s.smsEnabled(), approvers, linked);
    }

    public List<Approver> approverCandidates(Long businessId) {
        return jdbc.query("SELECT id, username, email, role FROM app_user WHERE business_id = ? "
                        + "AND role IN ('OWNER', 'MANAGER') ORDER BY id",
                (rs, i) -> new Approver(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), businessId);
    }

    @Transactional
    public ProviderSetup update(Long businessId, Long providerId, UpdateRequest req) {
        get(businessId, providerId);
        Integer activeInSquare = jdbc.queryForObject("SELECT COUNT(*) FROM provider_square_member WHERE provider_id = ? AND square_team_member_id = ANY (?)",
                Integer.class, providerId, activeSquareMembers(businessId).toArray(new String[0]));
        if (activeInSquare == null || activeInSquare == 0) throw TimecardReviewService.bad("This person is not active in Square");
        if (req.workerType() != null) {
            if (!req.workerType().equals("W2") && !req.workerType().equals("1099")) {
                throw TimecardReviewService.bad("workerType must be W2 or 1099");
            }
            if (req.workerType().equals("W2")) {
                ProviderSetup cur = get(businessId, providerId);
                Integer rate = req.hourlyRateCents() != null ? req.hourlyRateCents() : cur.hourlyRateCents();
                if (rate == null) throw TimecardReviewService.bad("Set an hourly rate before switching to W2");
            }
            jdbc.update("UPDATE providers SET worker_type = ? WHERE id = ? AND business_id = ?",
                    req.workerType(), providerId, businessId);
        }
        if (req.showCommissionComparisonToProvider() != null) {
            jdbc.update("UPDATE providers SET show_commission_comparison_to_provider = ? WHERE id = ? AND business_id = ?",
                    req.showCommissionComparisonToProvider(), providerId, businessId);
        }
        if (req.hourlyRateCents() != null) {
            if (req.hourlyRateCents() < 1 || req.hourlyRateCents() > 20000) throw TimecardReviewService.bad("Rate out of range");
            LocalDate from = req.rateEffectiveFrom() == null ? LocalDate.now(TimecardDayService.RULES.zone())
                    : TimecardReviewService.parseDate(req.rateEffectiveFrom());
            setRate(businessId, providerId, req.hourlyRateCents(), from);
        }
        boolean hasEmployment = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM provider_employment WHERE business_id = ? AND provider_id = ? AND effective_to IS NULL)",
                Boolean.class, businessId, providerId));
        if (hasEmployment) {
            if (req.mobilePhone() != null) updateCurrent(businessId, providerId, "mobile_phone", req.mobilePhone().isBlank() ? null : req.mobilePhone().trim());
            if (req.payrollAutomationEnabled() != null) updateCurrent(businessId, providerId, "payroll_automation_enabled", req.payrollAutomationEnabled());
            if (req.alertsEnabled() != null) updateCurrent(businessId, providerId, "alerts_enabled", req.alertsEnabled());
            if (req.smsEnabled() != null) updateCurrent(businessId, providerId, "sms_enabled", req.smsEnabled());
        } else if (req.mobilePhone() != null || req.payrollAutomationEnabled() != null || req.smsEnabled() != null) {
            throw TimecardReviewService.bad("Set an hourly rate first");
        }
        if (req.approverUserIds() != null) {
            List<Long> valid = approverCandidates(businessId).stream().map(Approver::userId).toList();
            for (Long id : req.approverUserIds()) {
                if (!valid.contains(id)) throw TimecardReviewService.bad("User " + id + " cannot approve time");
            }
            jdbc.update("DELETE FROM provider_time_approver WHERE business_id = ? AND provider_id = ?", businessId, providerId);
            for (Long id : req.approverUserIds()) {
                jdbc.update("INSERT INTO provider_time_approver (business_id, provider_id, app_user_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT DO NOTHING", businessId, providerId, id);
            }
        }
        return get(businessId, providerId);
    }

    private void updateCurrent(Long businessId, Long providerId, String column, Object value) {
        jdbc.update("UPDATE provider_employment SET " + column + " = ? WHERE business_id = ? AND provider_id = ? "
                + "AND effective_to IS NULL", value, businessId, providerId);
    }

    /** New rate from a date: closes the open row the day before, or replaces it when it starts the same day. */
    void setRate(Long businessId, Long providerId, int cents, LocalDate from) {
        var open = jdbc.query("SELECT effective_from, mobile_phone, payroll_automation_enabled, alerts_enabled, sms_enabled "
                        + "FROM provider_employment WHERE business_id = ? AND provider_id = ? AND effective_to IS NULL",
                (rs, i) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getString(2), rs.getBoolean(3),
                        rs.getBoolean(4), rs.getBoolean(5)}, businessId, providerId);
        if (open.isEmpty()) {
            jdbc.update("INSERT INTO provider_employment (business_id, provider_id, hourly_rate_cents, effective_from) "
                    + "VALUES (?, ?, ?, ?)", businessId, providerId, cents, java.sql.Date.valueOf(from));
            return;
        }
        Object[] o = open.get(0);
        LocalDate openFrom = (LocalDate) o[0];
        if (openFrom.equals(from)) {
            jdbc.update("UPDATE provider_employment SET hourly_rate_cents = ? WHERE business_id = ? AND provider_id = ? "
                    + "AND effective_to IS NULL", cents, businessId, providerId);
            return;
        }
        if (openFrom.isAfter(from)) throw TimecardReviewService.bad("A newer rate already starts after that date");
        jdbc.update("UPDATE provider_employment SET effective_to = ? WHERE business_id = ? AND provider_id = ? "
                + "AND effective_to IS NULL", java.sql.Date.valueOf(from.minusDays(1)), businessId, providerId);
        jdbc.update("INSERT INTO provider_employment (business_id, provider_id, hourly_rate_cents, effective_from, "
                        + "mobile_phone, payroll_automation_enabled, alerts_enabled, sms_enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                businessId, providerId, cents, java.sql.Date.valueOf(from), o[1], o[2], o[3], o[4]);
    }

    /** Reads the provider's mobile number from Square and stores it on the current employment row. */
    public ProviderSetup importPhoneFromSquare(Long businessId, Long providerId) {
        List<String> members = days.memberIds(providerId);
        if (members.isEmpty()) throw TimecardReviewService.bad("Provider is not linked to a Square team member");
        String phone = squares.forBusiness(businessId).teamMemberPhone(members.get(0));
        if (phone == null) throw TimecardReviewService.bad("Square has no phone number for this team member");
        updateCurrentChecked(businessId, providerId, phone);
        return get(businessId, providerId);
    }

    private void updateCurrentChecked(Long businessId, Long providerId, String phone) {
        int n = jdbc.update("UPDATE provider_employment SET mobile_phone = ? WHERE business_id = ? AND provider_id = ? "
                + "AND effective_to IS NULL", phone, businessId, providerId);
        if (n == 0) throw TimecardReviewService.bad("Set an hourly rate first");
    }
}
