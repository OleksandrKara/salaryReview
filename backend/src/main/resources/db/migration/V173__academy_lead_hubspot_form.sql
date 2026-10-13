-- Academy leads reach HubSpot through a HubSpot form submission (no token), which returns no
-- contact id, so record whether the submission was accepted instead.
ALTER TABLE academy_lead DROP COLUMN hubspot_contact_id;
ALTER TABLE academy_lead ADD COLUMN hubspot_submitted BOOLEAN NOT NULL DEFAULT FALSE;
