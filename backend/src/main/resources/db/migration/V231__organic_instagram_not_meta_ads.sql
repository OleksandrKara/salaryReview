-- Leads whose site tap carried an Instagram/Facebook app click id were labelled "Meta Ads (ig / social)" although the
-- medium says social (organic bio link taps). They are organic. Keep the old values so the change can be undone.
CREATE TABLE lead_intake_meta_organic_fix_20261125 AS
SELECT id, source, traffic_source FROM lead_intake WHERE traffic_source = 'Meta Ads (ig / social)' AND NOT source_manual;

UPDATE lead_intake SET traffic_source = 'Instagram (organic)', source = 'Instagram (organic)'
WHERE traffic_source = 'Meta Ads (ig / social)' AND NOT source_manual;

UPDATE marketing.contacts SET original_traffic_source = 'Instagram (organic)'
WHERE original_traffic_source = 'Meta Ads (ig / social)';
UPDATE marketing.contacts SET marketing_traffic_source = 'Instagram (organic)'
WHERE marketing_traffic_source = 'Meta Ads (ig / social)';
