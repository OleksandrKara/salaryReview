-- Remove legacy/demo providers that aren't linked to a Square team member (the V2 demo seed Anna/Bea
-- and stray test rows). Real providers are auto-provisioned from Square with a mapping, so this also
-- ensures a fresh install starts with no demo data. period_entries cascade-delete via their FK.
DELETE FROM providers
WHERE id NOT IN (SELECT provider_id FROM provider_square_member);
