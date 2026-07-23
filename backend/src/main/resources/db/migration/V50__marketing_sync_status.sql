-- Single-row record of when "Sync appointments" (MarketingContactsService#syncSquareLinks) was
-- last actually invoked — independent of whether that particular run found anything new to link,
-- so the owner can trust "Last synced: 5 minutes ago" on the button even after a run that
-- resolved zero new contacts (MAX(last_synced_at) off marketing_contact_square_link would go
-- stale in exactly that case, since that column only moves when a new link is actually written).
-- Seeded NULL: never synced yet until the button is clicked for the first time after this deploys.
CREATE TABLE marketing_sync_status (
    id             BOOLEAN     PRIMARY KEY DEFAULT true CHECK (id),
    last_synced_at TIMESTAMPTZ
);

INSERT INTO marketing_sync_status (id, last_synced_at) VALUES (true, NULL);
