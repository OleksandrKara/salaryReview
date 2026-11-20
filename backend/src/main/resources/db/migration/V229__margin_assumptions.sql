-- Rough per-business cost assumptions behind ROMI (material cost per procedure, the master's share and
-- the card processing fee). Deliberately light: any signed-in user may edit or clear them, and the numbers
-- are approximate (rent, insurance and other costs are not included). No row means "not set".
CREATE TABLE margin_assumptions (
    business_id           bigint PRIMARY KEY REFERENCES business(id),
    material_per_procedure numeric(10,2) NOT NULL CHECK (material_per_procedure >= 0 AND material_per_procedure <= 10000),
    master_pct            numeric(5,2) NOT NULL CHECK (master_pct >= 0 AND master_pct <= 100),
    square_pct            numeric(5,2) NOT NULL CHECK (square_pct >= 0 AND square_pct <= 100),
    updated_by            text,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CHECK (master_pct + square_pct < 100)
);

INSERT INTO margin_assumptions (business_id, material_per_procedure, master_pct, square_pct, updated_by)
SELECT id, 50, 45, 3, 'initial' FROM business;
