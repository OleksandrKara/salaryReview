-- sops predates multi-tenancy and has zero tenant boundary (tasks.md 2.6). sop_versions and
-- sop_acknowledgments stay unchanged — both are only ever reached through a sop_id that the service
-- layer has already resolved+verified against a business-scoped sops row, so a join-based filter on
-- sops.business_id (same idiom as staff_documents joining through Provider/AppUser) is enough; no
-- separate business_id column needed on either child table.
ALTER TABLE sops ADD COLUMN business_id BIGINT;

UPDATE sops SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');

ALTER TABLE sops ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE sops ADD CONSTRAINT sops_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);

CREATE INDEX idx_sops_business_id ON sops (business_id);
