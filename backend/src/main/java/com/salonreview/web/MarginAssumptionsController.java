package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * The rough cost assumptions behind ROMI. Open on purpose: any signed-in user of the business can read,
 * change or clear them (they are estimates, not accounting data), so this sits under the plain
 * "authenticated" rule rather than an owner-only one.
 */
@RestController
@RequestMapping("/api/margins")
public class MarginAssumptionsController {

    public record Margins(BigDecimal materialPerProcedure, BigDecimal masterPct, BigDecimal squarePct,
                          String updatedBy, String updatedAt) {}

    public record MarginsRequest(BigDecimal materialPerProcedure, BigDecimal masterPct, BigDecimal squarePct) {}

    private final JdbcTemplate jdbc;
    private final CurrentBusinessContext business;

    public MarginAssumptionsController(JdbcTemplate jdbc, CurrentBusinessContext business) {
        this.jdbc = jdbc;
        this.business = business;
    }

    /** The current assumptions, or 204 when none are set. */
    @GetMapping
    public ResponseEntity<Margins> get() {
        Margins m = current();
        return m == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(m);
    }

    private Margins current() {
        var rows = jdbc.query("SELECT material_per_procedure, master_pct, square_pct, updated_by, updated_at "
                        + "FROM margin_assumptions WHERE business_id = ?",
                (rs, i) -> new Margins(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant().toString()), business.id());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @PutMapping
    public ResponseEntity<Margins> put(@RequestBody MarginsRequest req, Authentication auth) {
        validate(req);
        jdbc.update("INSERT INTO margin_assumptions (business_id, material_per_procedure, master_pct, square_pct, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, now()) ON CONFLICT (business_id) DO UPDATE SET "
                        + "material_per_procedure = EXCLUDED.material_per_procedure, master_pct = EXCLUDED.master_pct, "
                        + "square_pct = EXCLUDED.square_pct, updated_by = EXCLUDED.updated_by, updated_at = now()",
                business.id(), req.materialPerProcedure(), req.masterPct(), req.squarePct(),
                auth == null ? null : auth.getName());
        return ResponseEntity.ok(current());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        jdbc.update("DELETE FROM margin_assumptions WHERE business_id = ?", business.id());
    }

    static void validate(MarginsRequest r) {
        if (r == null || r.materialPerProcedure() == null || r.masterPct() == null || r.squarePct() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "all three values are required");
        }
        if (r.materialPerProcedure().signum() < 0 || r.materialPerProcedure().compareTo(BigDecimal.valueOf(10000)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "material cost must be between 0 and 10000");
        }
        if (r.masterPct().signum() < 0 || r.squarePct().signum() < 0
                || r.masterPct().add(r.squarePct()).compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "percentages must be positive and add up to less than 100");
        }
    }
}
