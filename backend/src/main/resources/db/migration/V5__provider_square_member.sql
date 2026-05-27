-- Maps Square team members to providers. One provider (person) can own several Square team-member
-- IDs over time (e.g. a rehired stylist with a new account), so their monthly service count merges
-- correctly. The Square id is the primary key: each Square member belongs to exactly one provider.
CREATE TABLE provider_square_member (
    square_team_member_id TEXT   PRIMARY KEY,
    provider_id           BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE
);

CREATE INDEX idx_provider_square_member_provider ON provider_square_member (provider_id);
