-- Standard Operating Procedures: owner-authored, versioned, approval-gated policy documents that
-- staff must explicitly acknowledge per version. Distinct from KB articles (no approval/versioning).
--
-- Three tables: sops (stable identity + the live version pointer + audience), sop_versions
-- (immutable content snapshots), sop_acknowledgments (write-once signatures keyed to a VERSION, so
-- publishing a new version automatically requires fresh acknowledgment). audience targets who must
-- acknowledge and who can see the SOP at all.

CREATE TABLE sops (
    id                  BIGSERIAL    PRIMARY KEY,
    title               VARCHAR(512) NOT NULL,
    category            VARCHAR(128) NOT NULL,
    -- MANAGER | PROVIDER | BOTH
    audience            VARCHAR(16)  NOT NULL,
    -- The live/published version (null until first publish). FK added after sop_versions exists
    -- (the two tables reference each other).
    current_version_id  BIGINT,
    -- ACTIVE | ARCHIVED  (archived: hidden from staff, retained for owner audit)
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by          VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sop_versions (
    id              BIGSERIAL    PRIMARY KEY,
    sop_id          BIGINT       NOT NULL REFERENCES sops (id),
    version_number  INT          NOT NULL,
    body            TEXT         NOT NULL DEFAULT '',
    -- DRAFT | PUBLISHED
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sop_version_number UNIQUE (sop_id, version_number)
);

-- Close the cycle now that sop_versions exists. SET NULL is defensive — versions are never deleted
-- (archive-not-delete), so this should not fire in practice.
ALTER TABLE sops
    ADD CONSTRAINT fk_sops_current_version
    FOREIGN KEY (current_version_id) REFERENCES sop_versions (id) ON DELETE SET NULL;

CREATE TABLE sop_acknowledgments (
    id              BIGSERIAL    PRIMARY KEY,
    sop_version_id  BIGINT       NOT NULL REFERENCES sop_versions (id),
    user_id         BIGINT       NOT NULL REFERENCES app_user (id),
    acknowledged_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- One signature per (version, user) — write-once; idempotent acknowledge relies on this.
    CONSTRAINT uq_sop_ack_version_user UNIQUE (sop_version_id, user_id)
);

CREATE INDEX idx_sop_versions_sop ON sop_versions (sop_id);
CREATE INDEX idx_sops_audience ON sops (audience);
CREATE INDEX idx_sops_status ON sops (status);
