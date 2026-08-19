-- salonLandings (mani/AK PMU landing pages) resolves a business by the incoming Host header via a
-- new internal API — see ~/salonLandings/docs/multi-tenant-akpmu-design.md. This is the domain
-- salonLandings serves that business's public landing page on; null until one is configured, since
-- not every business has a public landing yet (Business A's own onboarding predates salonLandings'
-- multi-tenant support).
ALTER TABLE business ADD COLUMN public_domain VARCHAR(255) UNIQUE;

UPDATE business SET public_domain = 'mani.akluxnails.com' WHERE short_code = 'akluxnails';
